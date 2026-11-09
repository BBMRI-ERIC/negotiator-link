#!/usr/bin/env python3
"""
Idempotent provisioning of the Negotiator setup in the LOCAL DEV environment.
Performs the configuration described in [docs/negotiator-init-setup.md](docs/negotiator-init-setup.md):
- webhook registration
- assigning service accounts to the resources of the organization defined in [docker/organizations.yml](docker/organizations.yml)
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent

# Fixed local dev-stack coordinates
NEGOTIATOR_URL = "http://negotiator:8081/api"
OIDC_URL = "http://oidc-server-mock:8080"
WEBHOOK_LISTENER_URL = "http://negotiator-link:8080"

# The dev-only OIDC client from oidc-server-config/clients.json
OIDC_CLIENT_ID = "dev-admin-script"
OIDC_CLIENT_SECRET = "dev-admin-script-secret"
# admin user from oidc-server-config/users.yaml
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "admin"

OIDC_SCOPE = "openid profile email permissions some-app-scope-1"

ORGANIZATIONS_YML = REPO_ROOT / "docker" / "organizations.yml"


# --------------------------------------------------------------------------- #
# Config readers
# --------------------------------------------------------------------------- #
def read_webhook_secret() -> str:
    """NEGOTIATOR_WEBHOOK_SECRET from env (compose injects it from .env.dev)."""
    value = os.environ.get("NEGOTIATOR_WEBHOOK_SECRET", "")
    if not value:
        env_file = REPO_ROOT / ".env.dev"
        if env_file.exists():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line.startswith("NEGOTIATOR_WEBHOOK_SECRET="):
                    value = line.split("=", 1)[1].strip()
    return value


def read_org_configs() -> list[dict]:
    """Parse organizationExternalId + resourceId pairs from organizations.yml."""
    text = ORGANIZATIONS_YML.read_text(encoding="utf-8")
    org_ids = re.findall(r'organizationExternalId:\s*"([^"]+)"', text)

    # Map org external id to the seeded resource DB id.
    ORG_TO_RESOURCE_ID = {
        "bbmri-eric:ID:SE_890": 1,
        "bbmri-eric:ID:CZ_MMCI": 2,
    }

    result = []
    for org_id in org_ids:
        resource_id = ORG_TO_RESOURCE_ID.get(org_id)
        if resource_id is None:
            print(f"  ! no resource mapping for org '{org_id}' — skipping")
            continue
        result.append({"org_id": org_id, "resource_id": resource_id})
    return result


# --------------------------------------------------------------------------- #
# Minimal Negotiator REST client
# --------------------------------------------------------------------------- #
class Negotiator:
    def __init__(self, base_url: str, token: str):
        self.base = base_url.rstrip("/")
        self.token = token

    def _request(self, method: str, path: str, body=None) -> tuple[int, any]:
        url = f"{self.base}/{path.lstrip('/')}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read()
                return resp.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read()
            detail = raw.decode("utf-8", "replace") if raw else ""
            raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {detail}") from None
        except urllib.error.URLError as e:
            raise RuntimeError(f"{method} {path} -> connection error: {e.reason}") from None

    def get(self, path) -> any:
        _, body = self._request("GET", path)
        return body

    def post(self, path, body) -> tuple[int, any]:
        return self._request("POST", path, body)

    def patch(self, path, body) -> tuple[int, any]:
        return self._request("PATCH", path, body)


# --------------------------------------------------------------------------- #
# OIDC token fetch
# --------------------------------------------------------------------------- #
def fetch_token() -> str:
    """Obtain an admin bearer token via the dev-only password grant."""
    url = f"{OIDC_URL}/connect/token"
    payload = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": OIDC_CLIENT_ID,
        "client_secret": OIDC_CLIENT_SECRET,
        "username": ADMIN_USERNAME,
        "password": ADMIN_PASSWORD,
        "scope": OIDC_SCOPE,
    }).encode("utf-8")
    req = urllib.request.Request(url, data=payload, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read())
            token = data.get("access_token")
            if not token or token == "null":
                raise RuntimeError(f"no access_token in response: {data}")
            return token
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise RuntimeError(f"token fetch failed HTTP {e.code}: {detail}") from None
    except urllib.error.URLError as e:
        raise RuntimeError(f"token fetch connection error: {e.reason}") from None


# --------------------------------------------------------------------------- #
# Provisioning steps
# --------------------------------------------------------------------------- #
def provision_webhook(n: Negotiator, webhook_secret: str) -> None:
    """Create the negotiator-link webhook if it does not already exist."""
    print("Webhook…")
    url = f"{WEBHOOK_LISTENER_URL}/webhooks/negotiator"

    existing = n.get("v3/webhooks")
    embedded = (existing or {}).get("_embedded", {})
    webhooks = embedded.get("webhookResponseDTOList", [])
    for wh in webhooks:
        if wh.get("url") == url:
            print(f"  = webhook already registered (id={wh.get('id')})")
            return

    status, body = n.post("v3/webhooks", {
        "url": url,
        "secret": webhook_secret,
        "sslVerification": False,
        "active": True,
    })
    print(f"  + created webhook (id={body.get('id') if body else '?'})")


def provision_resource_assignments(n: Negotiator, orgs: list[dict]) -> None:
    """Assign each negotiator-link service user to its resource.

    The service user subject ids are stable (assigned deterministically by
    Negotiator on first OAuth2 login from the client-credentials clients):
      negotiator-link        -> subject 10000 -> resource 1 (SE_890)
      negotiator-link-cz-mmci -> subject 10001 -> resource 2 (CZ_MMCI)

    Failures are logged but do not abort the script.
    """
    print("Resource assignments…")

    ORG_TO_SUBJECT = {
        "bbmri-eric:ID:SE_890": 10000,
        "bbmri-eric:ID:CZ_MMCI": 10001,
    }

    for org in orgs:
        org_id = org["org_id"]
        resource_id = org["resource_id"]
        subject_id = ORG_TO_SUBJECT.get(org_id)
        if subject_id is None:
            print(f"  ! no subject id mapping for org '{org_id}' — skipping")
            continue

        # Check existing assignments to stay idempotent.
        try:
            existing = n.get(f"v3/users/{subject_id}/resources")
            embedded = (existing or {}).get("_embedded", {})
            resources = embedded.get("resources", [])
            already_assigned = any(r.get("id") == resource_id for r in resources)
            if already_assigned:
                print(f"  = user {subject_id} already assigned to resource {resource_id} (org '{org_id}')")
                continue
        except RuntimeError as e:
            print(f"  ! could not fetch assignments for user {subject_id} (org '{org_id}'): {e} — skipping")
            continue

        try:
            status, _ = n.patch(f"v3/users/{subject_id}/resources", {"id": resource_id})
            print(f"  + assigned user {subject_id} to resource {resource_id} (org '{org_id}')")
        except RuntimeError as e:
            print(f"  ! failed to assign user {subject_id} to resource {resource_id} (org '{org_id}'): {e}")


# --------------------------------------------------------------------------- #
def wait_for_negotiator(timeout: int) -> str:
    """Wait until the Negotiator OIDC mock is ready and return a token."""
    print(f"Waiting for Negotiator + OIDC at {NEGOTIATOR_URL} …")
    deadline = time.time() + timeout
    last_err = None
    while time.time() < deadline:
        try:
            token = fetch_token()
            # Also verify the Negotiator API itself is reachable.
            n = Negotiator(NEGOTIATOR_URL, token)
            n.get("v3/webhooks")
            return token
        except RuntimeError as e:
            last_err = e
            time.sleep(5)
    raise SystemExit(f"Negotiator not reachable after {timeout}s: {last_err}")


def main() -> int:
    webhook_secret = read_webhook_secret()
    if not webhook_secret:
        print("ERROR: NEGOTIATOR_WEBHOOK_SECRET not set.", file=sys.stderr)
        return 1

    orgs = read_org_configs()
    if not orgs:
        print("ERROR: no organizations found in docker/organizations.yml.", file=sys.stderr)
        return 1

    token = wait_for_negotiator(timeout=180)
    print(f"Authenticated as {ADMIN_USERNAME}\n")

    n = Negotiator(NEGOTIATOR_URL, token)
    provision_webhook(n, webhook_secret)
    provision_resource_assignments(n, orgs)

    print("\n✅ Negotiator provisioning complete.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
