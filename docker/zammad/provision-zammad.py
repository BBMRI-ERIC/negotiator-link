#!/usr/bin/env python3
"""
Idempotent provisioning of the Zammad setup in the LOCAL DEV environment.

Automates the manual Zammad setup described in [docs/zammad-init-setup.md](docs/zammad-init-setup.md):
  - a dedicated group
  - a dedicated role
  - provision bot user role and group membership
  - three custom ticket fields + the object-manager DB migration
  - two webhooks (plain + ticket-state-updated custom payload)
  - three triggers wired to those webhooks
  - the Core Workflows that hide / lock the custom fields
"""

import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import socket
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent

# Fixed local dev-stack coordinates
ZAMMAD_URL = "http://zammad-nginx:8080"
WEBHOOK_BASE = "http://negotiator-link:8080"
ZAMMAD_URL_HOST = "http://localhost:8888"

AUTOWIZARD_JSON = REPO_ROOT / "docker" / "zammad" / "auto_wizard.json"
ORGANIZATIONS_YML = REPO_ROOT / "docker" / "organizations.yml"
ENV_FILE = REPO_ROOT / ".env.dev"

# The custom ticket fields
NEGOTIATION_STATE_OPTIONS = {
    "SUBMITTED": "Submitted",
    "APPROVED": "Approved",
    "DECLINED": "Declined",
    "IN_PROGRESS": "In Progress",
    "PAUSED": "Paused",
    "CONCLUDED": "Concluded",
    "ABANDONED": "Abandoned",
}

RESOURCE_STATE_OPTIONS = {
    "SUBMITTED": "Submitted",
    "REPRESENTATIVE_UNREACHABLE": "Representative unreachable",
    "REPRESENTATIVE_CONTACTED": "Representative contacted",
    "CHECKING_AVAILABILITY": "Checking availability",
    "RESOURCE_UNAVAILABLE_WILLING_TO_COLLECT": "Resource unavailable willing to collect",
    "RESOURCE_UNAVAILABLE": "Resource unavailable",
    "RESOURCE_AVAILABLE": "Resource available",
    "ACCESS_CONDITIONS_INDICATED": "Access conditions indicated",
    "ACCESS_CONDITIONS_MET": "Access conditions met",
    "RESOURCE_NOT_MADE_AVAILABLE": "Resource not made available",
    "RESOURCE_MADE_AVAILABLE": "Resource made available",
}

TRANSITION_EVENT_OPTIONS = {
    "MARK_AS_CHECKING_AVAILABILITY": "Mark as checking availability",
    "MARK_AS_AVAILABLE": "Mark as available",
    "MARK_AS_UNAVAILABLE": "Mark as unavailable",
    "MARK_AS_CURRENTLY_UNAVAILABLE_BUT_WILLING_TO_COLLECT": "Mark as currently unavailable but willing to collect",
    "STEP_AWAY": "Step away",
    "INDICATE_ACCESS_CONDITIONS": "Indicate access conditions",
    "GRANT_ACCESS_TO_RESOURCE": "Grant access to resource",
}

# Resource states for which the transition-event field must be read-only
LOCKED_RESOURCE_STATES = [
    "SUBMITTED",
    "REPRESENTATIVE_UNREACHABLE",
    "ACCESS_CONDITIONS_INDICATED",
    "RESOURCE_MADE_AVAILABLE",
    "RESOURCE_NOT_MADE_AVAILABLE",
    "RESOURCE_UNAVAILABLE",
]


# --------------------------------------------------------------------------- #
# Single-source config readers
# --------------------------------------------------------------------------- #
def read_env(key: str) -> str:
    """Env var first (compose injects it), then .env.dev as a fallback."""
    if os.environ.get(key):
        return os.environ[key]
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith(f"{key}="):
                return line.split("=", 1)[1].strip()
    return ""


def read_admin_credentials() -> tuple:
    """Admin login + password from the auto-wizard JSON."""
    data = json.loads(AUTOWIZARD_JSON.read_text(encoding="utf-8"))
    user = data["Users"][0]
    return user["login"], user["password"]


def read_org_config() -> dict:
    """Org external id, group and bot email from organizations.yml (first org)."""
    text = ORGANIZATIONS_YML.read_text(encoding="utf-8")
    return {
        "org_id": re.search(r'organizationExternalId:\s*"([^"]+)"', text).group(1),
        "group": re.search(r'group:\s*"([^"]+)"', text).group(1),
        "bot_email": re.search(r'botEmail:\s*"([^"]+)"', text).group(1),
    }


# --------------------------------------------------------------------------- #
# Minimal Zammad REST client
# --------------------------------------------------------------------------- #
class Zammad:
    def __init__(self, base_url: str, token: str = None, basic_auth: tuple = None):
        self.base = base_url.rstrip("/")
        self.token = token
        self.basic_auth = basic_auth  # (user, password)

    def _request(self, method: str, path: str, body=None):
        url = f"{self.base}/api/v1/{path.lstrip('/')}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        if self.token:
            req.add_header("Authorization", f"Token token={self.token}")
        elif self.basic_auth:
            raw = f"{self.basic_auth[0]}:{self.basic_auth[1]}".encode("utf-8")
            req.add_header("Authorization", "Basic " + base64.b64encode(raw).decode("ascii"))
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

    def get(self, path):
        return self._request("GET", path)[1]

    def post(self, path, body):
        return self._request("POST", path, body)[1]

    def put(self, path, body):
        return self._request("PUT", path, body)[1]

    def ping(self):
        """Return the authenticated user dict, or raise."""
        return self.get("users/me")


# --------------------------------------------------------------------------- #
# Upsert helpers
# --------------------------------------------------------------------------- #
def _find_by(items, key, value):
    for it in items or []:
        if it.get(key) == value:
            return it
    return None


def upsert(z: Zammad, collection: str, name_key: str, name: str, payload: dict,
           list_path: str = None, label: str = None) -> dict:
    """Generic name-matched upsert. Returns the resulting object."""
    label = label or collection
    existing = _find_by(z.get(list_path or collection), name_key, name)
    if existing:
        obj = z.put(f"{collection}/{existing['id']}", payload)
        print(f"  ~ updated {label}: {name} (id={existing['id']})")
        return obj or existing
    obj = z.post(collection, payload)
    print(f"  + created {label}: {name} (id={obj['id']})")
    return obj


# --------------------------------------------------------------------------- #
# Provisioning steps
# --------------------------------------------------------------------------- #
def provision_group(z: Zammad, group_name: str) -> dict:
    print("Group…")
    return upsert(z, "groups", "name", group_name, {
        "name": group_name,
        "active": True,
        "note": "Owns all tickets created by negotiator-link.",
    })


def provision_role(z: Zammad, role_name: str, group_id: int) -> dict:
    print("Role…")
    return upsert(z, "roles", "name", role_name, {
        "name": role_name,
        "active": True,
        "note": "Role for the negotiator-link service account.",
        "permissions": ["ticket.agent", "user_preferences.access_token"],
        "group_ids": {str(group_id): ["full"]},
    })


def provision_bot_user(z: Zammad, email: str, firstname: str,
                       role_id: int, group_id: int) -> dict:
    """Ensure the bot user exists, has the negotiator-link role, and full access to
    the group."""
    print("Bot user…")
    existing = _find_by(z.get("users?query=" + urllib.parse.quote(email)), "email", email)
    if existing:
        role_ids = sorted(set(existing.get("role_ids", []) + [role_id]))
        group_ids = dict(existing.get("group_ids") or {})
        group_ids[str(group_id)] = ["full"]
        obj = z.put(f"users/{existing['id']}", {
            "role_ids": role_ids,
            "group_ids": group_ids,
        })
        print(f"  ~ updated bot user: {email} (id={existing['id']}, roles={role_ids})")
        return obj or existing
    obj = z.post("users", {
        "firstname": firstname,
        "email": email,
        "login": email,
        "active": True,
        "role_ids": [role_id],
        "group_ids": {str(group_id): ["full"]},
    })
    print(f"  + created bot user: {email} (id={obj['id']})")
    return obj


def grant_group_to_user(z: Zammad, email: str, group_id: int) -> None:
    """Give an existing user full access to the group"""
    print(f"Group access for {email}…")
    existing = _find_by(z.get("users?query=" + urllib.parse.quote(email)), "email", email)
    if not existing:
        print(f"  ! user {email} not found — skipping")
        return
    group_ids = dict(existing.get("group_ids") or {})
    if group_ids.get(str(group_id)) == ["full"]:
        print(f"  = {email} already has full access to group {group_id}")
        return
    group_ids[str(group_id)] = ["full"]
    z.put(f"users/{existing['id']}", {"group_ids": group_ids})
    print(f"  ~ granted {email} full access to group {group_id}")


def _select_attr_payload(name: str, display: str, options: dict, position: int) -> dict:
    return {
        "name": name,
        "display": display,
        "object": "Ticket",
        "data_type": "select",
        "active": True,
        "editable": True,
        "position": position,
        "data_option": {
            "options": options,
            "default": "",
            "null": True,
            "nulloption": True,
            "maxlength": 255,
            "translate": False,
            "relation": "",
            "linktemplate": "",
        },
        "screens": {
            "edit": {
                "ticket.agent": {"shown": True, "required": False},
                "ticket.customer": {"shown": False, "required": False},
            },
            "create_middle": {
                "ticket.agent": {"shown": False, "required": False, "item_class": "column"},
                "ticket.customer": {"shown": False, "required": False, "item_class": "column"},
            },
        },
    }


def provision_attributes(z: Zammad) -> None:
    print("Custom ticket fields…")
    attrs = [
        _select_attr_payload("negotiation_state", "Negotiation Status",
                             NEGOTIATION_STATE_OPTIONS, 1000),
        _select_attr_payload("negotiator_resource_state", "Resource Status",
                             RESOURCE_STATE_OPTIONS, 1100),
        _select_attr_payload("negotiator_resource_transition_event",
                             "Request Resource Status Transition",
                             TRANSITION_EVENT_OPTIONS, 1200),
    ]
    existing = z.get("object_manager_attributes")
    changed = False
    for a in attrs:
        match = _find_by(existing, "name", a["name"])
        if match:
            z.put(f"object_manager_attributes/{match['id']}", a)
            print(f"  ~ updated field: {a['name']} (id={match['id']})")
        else:
            obj = z.post("object_manager_attributes", a)
            print(f"  + created field: {a['name']} (id={obj['id']})")
        changed = True
    if changed:
        print("  running object-manager DB migration…")
        z.post("object_manager_attributes_execute_migrations", {})
        # The migration restarts Zammad to apply the schema change
        wait_for_ready_again(z)


def wait_for_ready_again(z: Zammad, timeout: int = 180) -> None:
    """After an object-manager migration, wait until the API is healthy again."""
    time.sleep(5)
    deadline = time.time() + timeout
    last_err = None
    while time.time() < deadline:
        try:
            z.get("object_manager_attributes")
            print("  Zammad healthy again after migration.")
            return
        except RuntimeError as e:
            last_err = e
            time.sleep(3)
    raise SystemExit(f"Zammad did not recover after migration in {timeout}s: {last_err}")


def provision_webhooks(z: Zammad, webhook_endpoint: str, callback_secret: str) -> dict:
    print("Webhooks…")
    plain = upsert(z, "webhooks", "name", "negotiator-link: whole payload", {
        "name": "negotiator-link: whole payload",
        "endpoint": webhook_endpoint,
        "http_method": "post",
        "ssl_verify": False,  # local dev only
        "signature_token": callback_secret,
        "customized_payload": False,
        "active": True,
    }, label="webhook")

    custom_payload = json.dumps({
        "ticket": {
            "state_updated": True,
            "id": "#{ticket.id}",
            "state": "#{ticket.state.name}",
        }
    }, indent=2)
    state_wh = upsert(z, "webhooks", "name", "negotiator-link: ticket state updated", {
        "name": "negotiator-link: ticket state updated",
        "endpoint": webhook_endpoint,
        "http_method": "post",
        "ssl_verify": False,
        "signature_token": callback_secret,
        "customized_payload": True,
        "custom_payload": custom_payload,
        "active": True,
    }, label="webhook")

    return {"plain": plain["id"], "state": state_wh["id"]}


def provision_triggers(z: Zammad, bot_user_id: int, webhook_ids: dict) -> None:
    print("Triggers…")
    bot = str(bot_user_id)
    plain_wh = str(webhook_ids["plain"])
    state_wh = str(webhook_ids["state"])

    # 1) New public article (post) by anyone other than the bot -> forward to Negotiator.
    upsert(z, "triggers", "name", "negotiator-link: new article", {
        "name": "negotiator-link: new article",
        "active": True,
        "execution_condition_mode": "selective",
        "condition": {
            "article.created_by_id": {"operator": "is not", "pre_condition": "specific",
                                      "value": [bot], "value_completion": ""},
            "article.action": {"operator": "is", "value": "create"},
            "article.internal": {"operator": "is", "value": ["false"]},
        },
        "perform": {"notification.webhook": {"webhook_id": plain_wh}},
    }, label="trigger")

    # 2) Representative requests a resource transition via the custom field.
    transition_or = [
        {"name": "ticket.negotiator_resource_transition_event", "operator": "is", "value": [ev]}
        for ev in TRANSITION_EVENT_OPTIONS.keys()
    ]
    upsert(z, "triggers", "name", "negotiator-link: resource transition", {
        "name": "negotiator-link: resource transition",
        "active": True,
        "execution_condition_mode": "selective",
        "condition": {
            "operator": "AND",
            "conditions": [
                {"operator": "OR", "conditions": transition_or},
                {"name": "ticket.updated_by_id", "operator": "is not",
                 "pre_condition": "specific", "value": [bot]},
                {"operator": "OR", "conditions": [
                    {"name": "article.action", "operator": "is not", "value": "create"},
                    {"name": "article.internal", "operator": "is", "value": ["true"]},
                ]},
            ],
        },
        "perform": {"notification.webhook": {"webhook_id": plain_wh}},
    }, label="trigger")

    # 3) Native ticket state change -> drive resource state from ticket lifecycle.
    upsert(z, "triggers", "name", "negotiator-link: ticket state updated", {
        "name": "negotiator-link: ticket state updated",
        "active": True,
        "execution_condition_mode": "selective",
        "condition": {
            "operator": "AND",
            "conditions": [
                {"name": "ticket.state_id", "operator": "has changed", "value": []},
                {"name": "ticket.updated_by_id", "operator": "is not",
                 "pre_condition": "specific", "value": [bot]},
            ],
        },
        "perform": {"notification.webhook": {"webhook_id": state_wh}},
    }, label="trigger")


def provision_core_workflows(z: Zammad, group_id: int) -> None:
    print("Core Workflows…")
    hide = {"operator": "hide", "hide": "true"}
    readonly = {"operator": "set_readonly", "set_readonly": "true"}

    # Hide the three custom fields on tickets outside the negotiator-link group.
    upsert(z, "core_workflows", "name",
           "negotiator-link: hide custom fields when different group than negotiator-link-group", {
               "name": "negotiator-link: hide custom fields when different group than negotiator-link-group",
               "object": "Ticket",
               "active": True,
               "stop_after_match": False,
               "changeable": True,
               "priority": 400,
               "condition_saved": {
                   "ticket.group_id": {"operator": "is not", "value": [str(group_id)],
                                       "value_completion": ""},
               },
               "condition_selected": {},
               "perform": {
                   "ticket.negotiation_state": hide,
                   "ticket.negotiator_resource_state": hide,
                   "ticket.negotiator_resource_transition_event": hide,
               },
           }, label="core workflow")

    # Lock the two display-only fields read-only on every ticket.
    upsert(z, "core_workflows", "name", "negotiator-link: lock display fields", {
        "name": "negotiator-link: lock display fields",
        "object": "Ticket",
        "active": True,
        "stop_after_match": False,
        "changeable": True,
        "priority": 500,
        "condition_saved": {},
        "condition_selected": {},
        "perform": {
            "ticket.negotiation_state": readonly,
            "ticket.negotiator_resource_state": readonly,
        },
    }, label="core workflow")

    # Lock the transition field when negotiation is not IN_PROGRESS.
    upsert(z, "core_workflows", "name",
           "negotiator-link: lock resource transition when negotiation status is not IN_PROGRESS", {
               "name": "negotiator-link: lock resource transition when negotiation status is not IN_PROGRESS",
               "object": "Ticket",
               "active": True,
               "stop_after_match": False,
               "changeable": True,
               "priority": 505,
               "condition_saved": {},
               "condition_selected": {
                   "ticket.negotiation_state": {"operator": "is not", "value": ["IN_PROGRESS"]},
               },
               "perform": {"ticket.negotiator_resource_transition_event": readonly},
           }, label="core workflow")

    # Lock the transition field for each terminal/inbound-only resource state.
    priority = 510
    for state in LOCKED_RESOURCE_STATES:
        name = f"negotiator-link: lock resource transition when status is {state}"
        upsert(z, "core_workflows", "name", name, {
            "name": name,
            "object": "Ticket",
            "active": True,
            "stop_after_match": False,
            "changeable": True,
            "priority": priority,
            "condition_saved": {},
            "condition_selected": {
                "ticket.negotiator_resource_state": {"operator": "is", "value": [state]},
            },
            "perform": {"ticket.negotiator_resource_transition_event": readonly},
        }, label="core workflow")
        priority += 10


# --------------------------------------------------------------------------- #
def wait_for_zammad(z: Zammad, timeout: int) -> dict:
    deadline = time.time() + timeout
    last_err = None
    while time.time() < deadline:
        try:
            me = z.ping()
            return me
        except RuntimeError as e:
            last_err = e
            time.sleep(3)
    raise SystemExit(f"Zammad not reachable / token invalid after {timeout}s: {last_err}")


def build_webhook_endpoint(webhook_base: str, org_id: str) -> str:
    # negotiator-link expects the org external id URL-encoded in the path.
    return f"{webhook_base.rstrip('/')}/webhooks/helpdesk/{urllib.parse.quote(org_id, safe='')}"


def reachable_zammad_url() -> str:
    host = urllib.parse.urlparse(ZAMMAD_URL).hostname
    try:
        socket.getaddrinfo(host, None)
        return ZAMMAD_URL
    except socket.gaierror:
        return ZAMMAD_URL_HOST


def main() -> int:
    admin_user, admin_password = read_admin_credentials()
    org = read_org_config()
    secret = read_env("ZAMMAD_CALLBACK_SECRET")
    if not secret:
        print("ERROR: ZAMMAD_CALLBACK_SECRET not set (env or .env.dev).", file=sys.stderr)
        return 1

    zammad_url = reachable_zammad_url()

    # Provisioning requires admin permissions
    z = Zammad(zammad_url, basic_auth=(admin_user, admin_password))
    print(f"Waiting for Zammad at {zammad_url} …")
    me = wait_for_zammad(z, timeout=180)
    print(f"Authenticated as {me.get('login')} (id={me.get('id')})")

    webhook_endpoint = build_webhook_endpoint(WEBHOOK_BASE, org["org_id"])
    print(f"Webhook endpoint: {webhook_endpoint}\n")

    group = provision_group(z, org["group"])
    role = provision_role(z, "negotiator-link-role", group["id"])
    bot = provision_bot_user(z, org["bot_email"], "Negotiator Service Account",
                             role["id"], group["id"])
    # Let the admin also see/work on negotiator-link tickets.
    grant_group_to_user(z, admin_user, group["id"])
    # Custom fields first: their DB migration restarts Zammad, and the helper
    # waits for recovery.
    provision_attributes(z)
    webhook_ids = provision_webhooks(z, webhook_endpoint, secret)
    provision_triggers(z, bot["id"], webhook_ids)
    provision_core_workflows(z, group["id"])

    print("\n✅ Zammad provisioning complete.")
    print(f"   group id={group['id']}  role id={role['id']}  bot id={bot['id']}")
    print("   Remember: assign the bot user to resources in Negotiator (README Step 8).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
