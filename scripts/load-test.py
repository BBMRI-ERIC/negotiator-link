#!/usr/bin/env python3
"""
Repeatable load-test driver for negotiator-link.

Phases:
  1. seed    — generate + apply N synthetic IN_PROGRESS negotiations to the
               Negotiator DB (all resources in state REPRESENTATIVE_CONTACTED).
  2. approve — send negotiation.state.updated/APPROVE for each, creating tickets.
  3. wait    — poll the negotiator_link DB's ticket_mapping until the tickets
               exist, discovering the real Zammad ticket ids.
               (Phases 1-3 are setup and are NOT measured.)
  4. load    — THE MEASURED CAPTURE PHASE. Run a weighted webhook mix of events for --iterations
               rounds, from both the Negotiator side and the Zammad side.
  5. process — wait until inbox events and outbox intents have fully drained to a
               terminal state (end-to-end processing measurement).
  6. report  — two separate measurements:
               A. Webhook capture — HTTP acceptance (latency/throughput) of the load
                  phase webhooks only.
               B. End-to-end processing — how long THIS run's inbox events and outbox
                  intents took to fully drain to a terminal state, plus per-run counts.

Prerequisites: the development environment stack is up and configured according to the README.

Usage (run with defaults):
  python3 scripts/load-test.py

  With no arguments this seeds a fresh batch of 6 negotiations (--count 6,
  --seed-offset 0), runs 4 message-mix rounds (--iterations 4) with up to 5
  webhooks in flight at once (--concurrency 5), and injects no failure cases
  (--error 0). It targets negotiator-link at http://localhost:8090 and reads the
  webhook secrets from .env (NEGOTIATOR_WEBHOOK_SECRET / ZAMMAD_MHH_CALLBACK_SECRET).
  It waits up to 120 s for the tickets to be created (--wait-timeout 120) and up to
  180 s for the inbox/outbox to fully drain (--processing-timeout 180).

All available options and their descriptions can be seen with:
  python3 scripts/load-test.py --help
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import statistics
import subprocess
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import urllib.error
import urllib.parse
import urllib.request

REPO_ROOT = Path(__file__).resolve().parent.parent
GENERATOR = Path(__file__).resolve().parent / "generate-seed-sql.py"
RESOURCE_ORG = {
    "2351": "bbmri-eric:ID:SE_890",
    "2354": "bbmri-eric:ID:SE_890",
    "2352": "bbmri-eric:ID:CZ_MMCI",
    "2353": "bbmri-eric:ID:CZ_MMCI",
}
ORG_EXT_TO_ID = {"bbmri-eric:ID:SE_890": 1, "bbmri-eric:ID:CZ_MMCI": 2}

SEED_PREFIX = "550e8400-e29b-41d4-a716-"
SEED_NEG_BASE = 0x667700000000
SEED_POST_BASE = 0x667800000000
SEED_STRIDE = 1_000_000

TRANSITION_CHAIN = [
    ("MARK_AS_CHECKING_AVAILABILITY", "CHECKING_AVAILABILITY"),
    ("INDICATE_ACCESS_CONDITIONS", "ACCESS_CONDITIONS_INDICATED"),
]


def seed_post_uuids(neg_uuid: str, offset: int) -> tuple[str, str]:
    """The two PUBLIC post UUIDs the seed created for `neg_uuid` at this offset."""
    i = int(neg_uuid.replace("-", "")[-12:], 16) - SEED_NEG_BASE - offset * SEED_STRIDE
    base = SEED_POST_BASE + offset * SEED_STRIDE * 2 + i * 2
    return f"{SEED_PREFIX}{base:012x}", f"{SEED_PREFIX}{base + 1:012x}"

# --------------------------------------------------------------------------- #
# Signing
# --------------------------------------------------------------------------- #
def _raw_negotiator_secret(whsec: str) -> bytes:
    """Standard Webhooks: strip 'whsec_' and base64-decode the remainder."""
    if whsec.startswith("whsec_"):
        whsec = whsec[len("whsec_"):]
    return base64.b64decode(whsec)


def sign_negotiator(secret_bytes: bytes, webhook_id: str, ts: int, body: bytes) -> str:
    """HMAC-SHA256('<id>.<ts>.<body>') -> base64, prefixed 'v1,'."""
    to_sign = f"{webhook_id}.{ts}.".encode("utf-8") + body
    sig = hmac.new(secret_bytes, to_sign, hashlib.sha256).digest()
    return "v1," + base64.b64encode(sig).decode("ascii")


def sign_zammad(secret: str, body: bytes) -> str:
    """HMAC-SHA1(raw body) -> lowercase hex, prefixed 'sha1='."""
    sig = hmac.new(secret.encode("utf-8"), body, hashlib.sha1).hexdigest()
    return "sha1=" + sig


# --------------------------------------------------------------------------- #
# HTTP
# --------------------------------------------------------------------------- #
def post(url: str, body: bytes, headers: dict) -> tuple[int | None, float, str | None]:
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, (time.perf_counter() - t0) * 1000, None
    except urllib.error.HTTPError as e:
        return e.code, (time.perf_counter() - t0) * 1000, f"{e.code} {e.reason}"
    except Exception as e:  # noqa: BLE001 - surface any transport error in the report
        return None, (time.perf_counter() - t0) * 1000, str(e)


class Sender:
    """Signs and sends webhooks to the two inbound endpoints."""

    def __init__(self, base_url: str, neg_secret: str, zammad_secret: str):
        self.base_url = base_url.rstrip("/")
        self.neg_secret_bytes = _raw_negotiator_secret(neg_secret)
        self.zammad_secret = zammad_secret

    def negotiator(self, payload_json: str, bad_signature: bool = False) -> tuple[int | None, float, str | None]:
        body = payload_json.encode("utf-8")
        wid = str(uuid.uuid4())
        ts = int(time.time())
        # bad_signature: sign with a wrong key so headers are well-formed but the HMAC
        # does not verify → the controller rejects with HTTP 401 before any inbox capture.
        secret = b"wrong-secret-" + self.neg_secret_bytes if bad_signature else self.neg_secret_bytes
        headers = {
            "Content-Type": "application/json",
            "Webhook-Id": wid,
            "Webhook-Timestamp": str(ts),
            "Webhook-Signature": sign_negotiator(secret, wid, ts, body),
        }
        return post(f"{self.base_url}/webhooks/negotiator", body, headers)

    def zammad(self, org_ext_id: str, payload: dict, bad_signature: bool = False) -> tuple[int | None, float, str | None]:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        secret = ("wrong-" + self.zammad_secret) if bad_signature else self.zammad_secret
        headers = {
            "Content-Type": "application/json",
            "X-Hub-Signature": sign_zammad(secret, body),
            "X-Zammad-Delivery": str(uuid.uuid4()),
        }
        # Path segment must be URL-encoded (colons).
        org_path = urllib.parse.quote(org_ext_id, safe="")
        return post(f"{self.base_url}/webhooks/helpdesk/{org_path}", body, headers)


# --------------------------------------------------------------------------- #
# Payload builders
# --------------------------------------------------------------------------- #
def negotiator_approve(nid: str) -> str:
    return json.dumps(
        {
            "type": "negotiation.state.updated",
            "data": {"negotiationId": nid, "fromState": "SUBMITTED",
                     "toState": "IN_PROGRESS", "event": "APPROVE"},
        },
        separators=(",", ":"),
    )


def negotiator_state(nid: str, from_state: str, to_state: str, event: str) -> str:
    return json.dumps(
        {
            "type": "negotiation.state.updated",
            "data": {"negotiationId": nid, "fromState": from_state,
                     "toState": to_state, "event": event},
        },
        separators=(",", ":"),
    )


def negotiator_post_added(nid: str, post_id: str, org_id: int) -> str:
    return json.dumps(
        {
            "type": "negotiation.post.added",
            "data": {"postId": post_id, "negotiationId": nid, "userId": 1,
                     "organizationId": org_id},
        },
        separators=(",", ":"),
    )


def negotiator_updated(nid: str) -> str:
    return json.dumps(
        {"type": "negotiation.updated", "data": {"negotiationId": nid}},
        separators=(",", ":"),
    )


def negotiator_resource_state(nid: str, rid: str, from_state: str, to_state: str, event: str) -> str:
    return json.dumps(
        {
            "type": "negotiation.resource.state.updated",
            "data": {"negotiationId": nid, "resourceId": rid, "fromState": from_state,
                     "toState": to_state, "event": event},
        },
        separators=(",", ":"),
    )


def _zammad_article(body_text: str, internal: bool) -> dict:
    return {
        "body": body_text,
        "internal": internal,
        "created_by": {"firstname": "Rep", "lastname": "Loadtest",
                       "email": "rep.loadtest@example.org"},
    }


def zammad_article(ticket_id: str, body_text: str, internal: bool) -> dict:
    """New-article callback (no resource transition)."""
    return {"ticket": {"id": ticket_id}, "article": _zammad_article(body_text, internal)}


def zammad_resource_transition(ticket_id: str, transition_event: str,
                               resource_state: str = "REPRESENTATIVE_CONTACTED") -> dict:
    """Representative requests a resource state transition via the ticket field."""
    return {
        "ticket": {
            "id": ticket_id,
            "negotiator_resource_state": resource_state,
            "negotiator_resource_transition_event": transition_event,
        },
        "article": {},
    }


# --------------------------------------------------------------------------- #
# DB access via docker exec psql
# --------------------------------------------------------------------------- #
def psql(container: str, user: str, db: str, sql: str, stdin: str | None = None) -> str:
    cmd = ["docker", "exec", "-i", container, "psql", "-U", user, "-d", db, "-v", "ON_ERROR_STOP=1"]
    if stdin is None:
        cmd += ["-tAc", sql]
        proc = subprocess.run(cmd, capture_output=True, text=True)
    else:
        proc = subprocess.run(cmd, input=stdin, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"psql failed ({container}): {proc.stderr.strip()}")
    return proc.stdout


def apply_seed_file(container: str, seed_path: Path) -> None:
    # The seed file begins with \c negotiator, so connect to any db and let it switch.
    sql = seed_path.read_text(encoding="utf-8")
    psql(container, "negotiator", "negotiator", "", stdin=sql)


def discover_tickets(container: str, neg_ids: list[str]) -> dict[str, list[dict]]:
    """negotiation_id -> [{ticketId, resourceId, resourceSourceId, orgExtId}] from ticket_mapping."""
    in_list = ",".join(f"'{n}'" for n in neg_ids)
    rows = psql(
        container, "negotiator_link", "negotiator_link",
        "SELECT negotiation_id, helpdesk_ticket_id, resource_id, resource_source_id, organization_ext_id "
        f"FROM ticket_mapping WHERE negotiation_id IN ({in_list});",
    )
    out: dict[str, list[dict]] = {}
    for line in rows.splitlines():
        if not line.strip():
            continue
        nid, ticket_id, resource_id, resource_source_id, org_ext_id = line.split("|")
        out.setdefault(nid, []).append(
            {"ticketId": ticket_id, "resourceId": resource_id,
             "resourceSourceId": resource_source_id, "orgExtId": org_ext_id}
        )
    return out


POISON_TICKET_BASE = 88_000_000


def seed_poison_mappings(container: str, poison_negs: list[str]) -> list[str]:
    """Insert one ticket_mapping row per negotiation pointing at a non-existent Zammad ticket."""
    fake_ids = []
    values = []
    for n in poison_negs:
        fake = str(POISON_TICKET_BASE + (int(n.replace("-", "")[-6:], 16) % 1_000_000))
        fake_ids.append(fake)
        # resource_id 2351 belongs to org SE_890 (org id 1) — a real seeded resource so fan-out includes it.
        values.append(
            f"('{n}', '{fake}', '2351', 'bbmri-eric:ID:SE_890:collection:lts_dummy_collection_1', "
            f"'1', 'bbmri-eric:ID:SE_890')"
        )
    sql = (
        "INSERT INTO ticket_mapping "
        "(negotiation_id, helpdesk_ticket_id, resource_id, resource_source_id, organization_id, organization_ext_id) "
        "VALUES " + ", ".join(values) + " ON CONFLICT DO NOTHING;"
    )
    psql(container, "negotiator_link", "negotiator_link", sql)
    return fake_ids


def count_failed(container: str) -> tuple[int, int, int]:
    """(inbox FAILED, outbox FAILED_PERMANENT, outbox DEAD_LETTER) — for the --error report."""
    inbox = psql(container, "negotiator_link", "negotiator_link",
                 "SELECT count(*) FROM inbox_event WHERE status = 'FAILED';").strip() or "0"
    perm = psql(container, "negotiator_link", "negotiator_link",
                "SELECT count(*) FROM outbox_intent WHERE status = 'FAILED_PERMANENT';").strip() or "0"
    dead = psql(container, "negotiator_link", "negotiator_link",
                "SELECT count(*) FROM outbox_intent WHERE status = 'DEAD_LETTER';").strip() or "0"
    return int(inbox), int(perm), int(dead)


# --------------------------------------------------------------------------- #
# Performance measurement — run isolation by row_id watermark
# --------------------------------------------------------------------------- #
def row_watermarks(container: str) -> tuple[int, int]:
    """(max inbox_event.row_id, max outbox_intent.row_id) right now; 0 if a table is empty."""
    inbox = psql(container, "negotiator_link", "negotiator_link",
                 "SELECT COALESCE(MAX(row_id), 0) FROM inbox_event;").strip() or "0"
    outbox = psql(container, "negotiator_link", "negotiator_link",
                  "SELECT COALESCE(MAX(row_id), 0) FROM outbox_intent;").strip() or "0"
    return int(inbox), int(outbox)


def count_pending_since(container: str, inbox_wm: int, outbox_wm: int) -> tuple[int, int]:
    """(inbox RECEIVED, outbox PENDING+IN_PROGRESS) among THIS run's rows only."""
    inbox = psql(container, "negotiator_link", "negotiator_link",
                 f"SELECT count(*) FROM inbox_event WHERE row_id > {inbox_wm} "
                 "AND status = 'RECEIVED';").strip() or "0"
    outbox = psql(container, "negotiator_link", "negotiator_link",
                  f"SELECT count(*) FROM outbox_intent WHERE row_id > {outbox_wm} "
                  "AND status IN ('PENDING','IN_PROGRESS');").strip() or "0"
    return int(inbox), int(outbox)


def status_breakdown_since(container: str, inbox_wm: int, outbox_wm: int
                           ) -> tuple[dict[str, int], dict[str, int]]:
    """(inbox status→count, outbox status→count) among THIS run's rows only."""
    inbox_rows = psql(container, "negotiator_link", "negotiator_link",
                      f"SELECT status, count(*) FROM inbox_event WHERE row_id > {inbox_wm} "
                      "GROUP BY status;")
    outbox_rows = psql(container, "negotiator_link", "negotiator_link",
                       f"SELECT status, count(*) FROM outbox_intent WHERE row_id > {outbox_wm} "
                       "GROUP BY status;")

    def parse(rows: str) -> dict[str, int]:
        out: dict[str, int] = {}
        for line in rows.splitlines():
            if not line.strip():
                continue
            status, cnt = line.split("|")
            out[status] = int(cnt)
        return out

    return parse(inbox_rows), parse(outbox_rows)


def wait_until_drained(container: str, inbox_wm: int, outbox_wm: int,
                       timeout_s: int) -> tuple[float, bool]:
    """Poll until inbox/outbox rows are fully processed (terminal).

    Returns (elapsed_seconds, drained): elapsed measured from entry to the moment the queue
    is observed empty for this run; drained=False if the timeout was hit first.
    """
    t0 = time.perf_counter()
    deadline = time.time() + timeout_s
    pending_inbox = pending_outbox = -1
    while time.time() < deadline:
        pending_inbox, pending_outbox = count_pending_since(container, inbox_wm, outbox_wm)
        print(f"      draining: inbox RECEIVED={pending_inbox}, "
              f"outbox PENDING/IN_PROGRESS={pending_outbox}   ", end="\r")
        if pending_inbox == 0 and pending_outbox == 0:
            print()
            return time.perf_counter() - t0, True
        time.sleep(0.5)
    print()
    print(f"      WARNING: drain timed out after {timeout_s}s with "
          f"inbox RECEIVED={pending_inbox}, outbox PENDING/IN_PROGRESS={pending_outbox}")
    return time.perf_counter() - t0, False


# --------------------------------------------------------------------------- #
# Phases
# --------------------------------------------------------------------------- #
def phase_seed(args) -> list[str]:
    print(f"[1/6] Seeding {args.count} negotiations (offset {args.seed_offset}) -> {args.seed_file}")
    proc = subprocess.run(
        [sys.executable, str(GENERATOR), "--count", str(args.count),
         "--seed-offset", str(args.seed_offset), "--out", str(args.seed_file)],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"seed generation failed: {proc.stderr.strip()}")
    neg_ids = [ln.strip() for ln in proc.stdout.splitlines() if ln.strip()]
    apply_seed_file(args.negotiator_db_container, Path(args.seed_file))
    print(f"      applied {len(neg_ids)} negotiations to the Negotiator DB")
    return neg_ids


def neg_ids_from_seed_file(seed_file: Path) -> list[str]:
    for line in seed_file.read_text(encoding="utf-8").splitlines():
        if line.startswith("-- NEGOTIATION_IDS:"):
            return [x for x in line.split(":", 1)[1].strip().split(",") if x]
    raise RuntimeError(f"no NEGOTIATION_IDS header found in {seed_file}")


def phase_approve(sender: Sender, neg_ids: list[str], concurrency: int, results: list):
    print(f"[2/6] Sending APPROVE for {len(neg_ids)} negotiations (ticket creation)")
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futs = {pool.submit(sender.negotiator, negotiator_approve(n)): n for n in neg_ids}
        errors = 0
        for fut in as_completed(futs):
            status, ms, err = fut.result()
            results.append(("approve", status, ms))
            if status != 200:
                errors += 1
        if errors:
            print(f"      WARNING: {errors} APPROVE webhooks did not return 200")


def phase_wait(container: str, neg_ids: list[str], timeout_s: int) -> dict[str, list[dict]]:
    # Expected ticket_mapping rows per negotiation: resources 2351-2354 -> 4 rows.
    expected_rows = len(neg_ids) * 4
    print(f"[3/6] Waiting for ticket_mapping ({expected_rows} rows expected, timeout {timeout_s}s)")
    deadline = time.time() + timeout_s
    mapping: dict[str, list[dict]] = {}
    while time.time() < deadline:
        mapping = discover_tickets(container, neg_ids)
        have = sum(len(v) for v in mapping.values())
        print(f"      ticket_mapping rows: {have}/{expected_rows}", end="\r")
        if have >= expected_rows:
            print()
            return mapping
        time.sleep(1.0)
    print()
    have = sum(len(v) for v in mapping.values())
    print(f"      WARNING: timed out with {have}/{expected_rows} rows; continuing with what exists")
    return mapping


def distinct_tickets(entries: list[dict]) -> list[dict]:
    seen = {}
    for e in entries:
        seen.setdefault(e["ticketId"], e)
    return list(seen.values())


def distinct_resources(entries: list[dict]) -> list[dict]:
    """One entry per resource_source_id. Unlike distinct_tickets, this keeps every resource
    even when several share a ticket (PER_NEGOTIATION orgs)"""
    seen = {}
    for e in entries:
        seen.setdefault(e["resourceSourceId"], e)
    return list(seen.values())


def phase_load(sender: Sender, mapping: dict[str, list[dict]], args, results: list):
    neg_ids = list(mapping.keys())
    print(f"[4/6] Load phase: {args.iterations} iterations over {len(neg_ids)} negotiations")

    def post_added_tasks(nid: str) -> list:
        # Public post from each org that has a ticket for this negotiation.
        tasks = []
        org_ids = {ORG_EXT_TO_ID[e["orgExtId"]] for e in mapping[nid]}
        post_a, post_b = seed_post_uuids(nid, args.seed_offset)
        for oid in org_ids:
            tasks.append(("post.added", lambda n=nid, p=post_a, o=oid:
                          sender.negotiator(negotiator_post_added(n, p, o))))
            tasks.append(("post.added", lambda n=nid, p=post_b, o=oid:
                          sender.negotiator(negotiator_post_added(n, p, o))))
        return tasks

    def round_tasks(nid: str) -> list:
        tasks = list(post_added_tasks(nid))
        tasks.append(("updated", lambda n=nid: sender.negotiator(negotiator_updated(n))))
        # PAUSE then resume (middleware never reads real negotiation state back).
        tasks.append(("state.pause", lambda n=nid:
                      sender.negotiator(negotiator_state(n, "IN_PROGRESS", "PAUSED", "PAUSE"))))
        tasks.append(("state.resume", lambda n=nid:
                      sender.negotiator(negotiator_state(n, "PAUSED", "IN_PROGRESS", "UNPAUSE"))))
        # Zammad -> link: articles on each distinct ticket.
        for e in distinct_tickets(mapping[nid]):
            tid, org = e["ticketId"], e["orgExtId"]
            tasks.append(("zammad.public", lambda t=tid, o=org:
                          sender.zammad(o, zammad_article(t, "Public reply from representative.", False))))
            tasks.append(("zammad.internal", lambda t=tid, o=org:
                          sender.zammad(o, zammad_article(t, "Internal note from representative.", True))))
        return tasks

    def run_batch(tasks: list):
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            futs = {pool.submit(fn): label for label, fn in tasks}
            for fut in as_completed(futs):
                label = futs[fut]
                status, ms, err = fut.result()
                results.append((label, status, ms))

    for it in range(1, args.iterations + 1):
        batch = [t for nid in neg_ids for t in round_tasks(nid)]
        run_batch(batch)
        print(f"      iteration {it}/{args.iterations}: sent {len(batch)} webhooks")

    # Resource transitions (once, after the message mix), per distinct ticket
    print("      running resource-transition chain ("
          + " -> ".join(ev for ev, _ in TRANSITION_CHAIN) + ")")
    for step, (event, target_state) in enumerate(TRANSITION_CHAIN, start=1):
        batch = []
        for nid in neg_ids:
            for e in distinct_tickets(mapping[nid]):
                tid, org = e["ticketId"], e["orgExtId"]
                batch.append((f"transition.{event}",
                              lambda t=tid, o=org, ev=event:
                              sender.zammad(o, zammad_resource_transition(t, ev))))
        run_batch(batch)
        print(f"        step {step}/{len(TRANSITION_CHAIN)} {event}: sent {len(batch)}")

    # A Negotiator-originated resource state change
    batch = []
    for nid in neg_ids:
        for e in distinct_resources(mapping[nid]):
            batch.append(("resource.state",
                          lambda n=nid, r=e["resourceSourceId"]:
                          sender.negotiator(negotiator_resource_state(
                              n, r, "ACCESS_CONDITIONS_INDICATED", "RESOURCE_MADE_AVAILABLE",
                              "GRANT_ACCESS_TO_RESOURCE"))))
    run_batch(batch)
    print(f"      sent {len(batch)} Negotiator-originated resource state changes")

    if args.error > 0:
        phase_error(sender, neg_ids, args, results)


# A postId that does not exist in the Negotiator.
BOGUS_POST_UUID = "550e8400-e29b-41d4-a716-999999999999"


def phase_error(sender: Sender, neg_ids: list[str], args, results: list):
    """Inject deterministic failures: --error N cases each of signature-rejected (HTTP 401),
    inbox-FAILED, and outbox-FAILED_PERMANENT."""
    n = min(args.error, len(neg_ids))
    targets = neg_ids[:n]
    print(f"      injecting {n} signature-401, {n} inbox-FAILED, {n} outbox-FAILED_PERMANENT "
          f"cases (--error {args.error})")

    def run_batch(tasks: list):
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            futs = {pool.submit(fn): label for label, fn in tasks}
            for fut in as_completed(futs):
                status, ms, err = fut.result()
                results.append((futs[fut], status, ms))

    # (0) Signature verification failure — wrong HMAC → rejected with HTTP 401
    sig_batch = []
    for k, nid in enumerate(targets):
        if k % 2 == 0:
            sig_batch.append(("error.bad_signature_negotiator", lambda nid=nid:
                              sender.negotiator(negotiator_updated(nid), bad_signature=True)))
        else:
            sig_batch.append(("error.bad_signature_zammad", lambda:
                              sender.zammad("bbmri-eric:ID:SE_890",
                                            zammad_article("1", "bad sig probe", False),
                                            bad_signature=True)))
    run_batch(sig_batch)

    # (1) Inbox FAILED — post.added with a non-existent postId → Negotiator 404 (PERMANENT).
    inbox_batch = [("error.inbox_failed", lambda nid=nid:
                    sender.negotiator(negotiator_post_added(nid, BOGUS_POST_UUID, 1)))
                   for nid in targets]
    run_batch(inbox_batch)

    # (2) Outbox FAILED_PERMANENT — seed a ticket_mapping row pointing at a non-existent Zammad
    # ticket, then a PUBLIC post.added fans an ADD_COMMENT out to it → Zammad 404 (PERMANENT).
    try:
        seed_poison_mappings(args.link_db_container, targets)
    except RuntimeError as e:
        print(f"      WARNING: could not seed poison mappings ({e}); skipping outbox-failure cases")
        return
    outbox_batch = [("error.outbox_failed_permanent",
                     lambda nid=nid: sender.negotiator(
                         negotiator_post_added(nid, seed_post_uuids(nid, args.seed_offset)[0], 1)))
                    for nid in targets]
    run_batch(outbox_batch)
    print(f"      error injection sent; failures drain and appear in the end-to-end report below")


def _pct(sorted_vals: list[float], q: float) -> float:
    """q-th percentile (q in 0..1) using the nearest-rank convention the old report used."""
    return sorted_vals[min(int(len(sorted_vals) * q), len(sorted_vals) - 1)]


def phase_report(load_results: list, capture_wall_ms: float,
                 processing: dict | None,
                 failed: tuple[int, int, int] | None = None):
    """Two clearly separated measurements:

      A. Webhook capture  — HTTP acceptance of the measured LOAD-PHASE webhooks only
      B. End-to-end processing — how long inbox events and outbox intents took
                            to fully drain to a terminal state, with the per-run counts.
    """
    print("\n[6/6] Report")

    unexpected = 0        # responses that did not match the expected status (drives exit code)
    sig_rejected = 0      # bad-signature webhooks correctly rejected with 401
    for label, status, ms in load_results:
        is_sig = label.startswith("error.bad_signature")
        expected = 401 if is_sig else 200
        if status == 401 and is_sig:
            sig_rejected += 1
        if status != expected:
            unexpected += 1
    total = len(load_results)
    times = [ms for _, _, ms in load_results]

    # ---- A. Webhook capture (HTTP acceptance of the load phase) ------------------------- #
    def box(title: str):
        print("┌" + "─" * 68 + "┐")
        print("│ " + title.ljust(66) + " │")
        print("└" + "─" * 68 + "┘")

    box("A. WEBHOOK CAPTURE  (HTTP acceptance - load phase only)")
    print(f"  Total webhooks sent : {total}")
    print(f"  Accepted (expected) : {total - unexpected}")
    print(f"  Unexpected status   : {unexpected}")
    if sig_rejected:
        print(f"  Signature-rejected  : {sig_rejected} (expected HTTP 401 — correct)")
    print(f"  Wall time           : {capture_wall_ms:,.0f} ms ({capture_wall_ms/1000:.1f} s)")
    if capture_wall_ms > 0:
        print(f"  Throughput          : {total / (capture_wall_ms/1000):.1f} req/s")
    if times:
        s = sorted(times)
        print("  Capture latency (ms, HTTP accept per webhook):")
        print(f"    min {min(s):7.1f};  mean {statistics.mean(s):7.1f};  median {statistics.median(s):7.1f};  "
              f"p95 {_pct(s, 0.95):7.1f};  p99 {_pct(s, 0.99):7.1f};  max {max(s):7.1f}")

    # ---- B. End-to-end processing (inbox → outbox drained for THIS run) ----------------- #
    if processing is not None:
        print()
        box("B. END-TO-END PROCESSING  (this run - inbox -> outbox drained)")
        inbox_counts: dict[str, int] = processing["inbox_counts"]
        outbox_counts: dict[str, int] = processing["outbox_counts"]
        drain_ms: float = processing["drain_ms"]
        drained: bool = processing["drained"]
        inbox_total = sum(inbox_counts.values())
        outbox_total = sum(outbox_counts.values())

        print(f"  Inbox events captured : {inbox_total}  "
              f"(includes any cascade webhooks from the Negotiator)")
        for st in ("PROCESSED", "SKIPPED", "FAILED"):
            if inbox_counts.get(st):
                print(f"      {st:16s}: {inbox_counts[st]}")
        print(f"  Outbox intents produced: {outbox_total}")
        for st in ("DELIVERED", "FAILED_PERMANENT", "DEAD_LETTER"):
            if outbox_counts.get(st):
                print(f"      {st:16s}: {outbox_counts[st]}")

        status_word = "fully processed" if drained else "NOT fully drained (timed out)"
        print(f"  Processing time        : {drain_ms:,.0f} ms ({drain_ms/1000:.1f} s)  "
              f"[send-start → queues empty]")
        total_events = inbox_total + outbox_total
        if drained and drain_ms > 0 and total_events:
            print(f"  Processing throughput  : {total_events / (drain_ms/1000):.1f} events/s  "
                  f"({inbox_total} inbox + {outbox_total} outbox)")
        print(f"  Status                 : {status_word}")
        if not drained:
            print("    (some rows never reached a terminal state within --processing-timeout)")

    # ---- Failure states (only shown with --error) --------------------------------------- #
    if failed is not None:
        print()
        f_inbox, f_perm, f_dead = failed
        print("  Failure states (this run):")
        print(f"      inbox FAILED={f_inbox}, outbox FAILED_PERMANENT={f_perm}, "
              f"DEAD_LETTER={f_dead}")
        print("      (includes failures injected by --error)")

    print("=" * 70)
    print("Watch Grafana / http://localhost:8090/actuator/prometheus during the run.")
    return unexpected


# --------------------------------------------------------------------------- #
def load_env_default(key: str, fallback: str) -> str:
    env_path = REPO_ROOT / ".env"
    if env_path.exists():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith(f"{key}="):
                return line.split("=", 1)[1].strip()
    return os.environ.get(key, fallback)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--link-url", default="http://localhost:8090",
                        help="base URL of negotiator-link")
    parser.add_argument("--neg-secret", default=None,
                        help="Negotiator webhook secret (default: NEGOTIATOR_WEBHOOK_SECRET from .env)")
    parser.add_argument("--zammad-secret", default=None,
                        help="Zammad callback secret (default: ZAMMAD_MHH_CALLBACK_SECRET from .env)")
    parser.add_argument("--count", type=int, default=6, help="number of negotiations to seed")
    parser.add_argument("--seed-offset", type=int, default=0,
                        help="batch offset; bump it for each run to seed a fresh batch of "
                             "negotiations/tickets (clean REPRESENTATIVE_CONTACTED) with no manual SQL reset")
    parser.add_argument("--iterations", type=int, default=4, help="message-mix rounds in the load loop")
    parser.add_argument("--error", type=int, default=0,
                        help="inject N inbox-FAILED and N outbox-FAILED_PERMANENT cases (0 = none)")
    parser.add_argument("--concurrency", type=int, default=5, help="concurrent in-flight webhooks")
    parser.add_argument("--seed-file", default=None,
                        help="seed SQL path (default: scripts/load-seed-<offset>.sql)")
    parser.add_argument("--negotiator-db-container", default="negotiator-db")
    parser.add_argument("--link-db-container", default="negotiator-link-db")
    parser.add_argument("--wait-timeout", type=int, default=120,
                        help="seconds to wait for ticket_mapping rows (phase 3, unmeasured)")
    parser.add_argument("--processing-timeout", type=int, default=180,
                        help="seconds to wait for this run's inbox/outbox rows to fully drain "
                             "to a terminal state (end-to-end processing measurement)")
    parser.add_argument("--skip-seed", nargs="?", type=int, const=-1, default=None,
                        metavar="OFFSET",
                        help="reuse existing seed file + already-created tickets (skip phases 1-3 "
                             "seeding). Bare --skip-seed reuses the --seed-offset batch; "
                             "--skip-seed N reuses scripts/load-seed-N.sql (and sets the offset to "
                             "N so the derived post UUIDs match that batch)")
    args = parser.parse_args()

    args.neg_secret = args.neg_secret or load_env_default("NEGOTIATOR_WEBHOOK_SECRET", "local-dev-webhook-secret")
    args.zammad_secret = args.zammad_secret or load_env_default("ZAMMAD_MHH_CALLBACK_SECRET", "")
    if not args.zammad_secret:
        parser.error("Zammad callback secret not found; pass --zammad-secret or set ZAMMAD_MHH_CALLBACK_SECRET in .env")
    if args.seed_offset < 0:
        parser.error("--seed-offset must be >= 0")

    skip_seed = args.skip_seed is not None
    if skip_seed and args.skip_seed >= 0:
        args.seed_offset = args.skip_seed

    sender = Sender(args.link_url, args.neg_secret, args.zammad_secret)
    if args.seed_file is None:
        args.seed_file = REPO_ROOT / "scripts" / f"load-seed-{args.seed_offset}.sql"
    args.seed_file = Path(args.seed_file)
    # Seed/approve/wait go into a scratch list we never report on — only the LOAD phase is measured.
    setup_results: list = []
    load_results: list = []

    if skip_seed:
        if not args.seed_file.exists():
            parser.error(f"--skip-seed given but {args.seed_file} does not exist")
        neg_ids = neg_ids_from_seed_file(args.seed_file)
        print(f"[1-3/6] --skip-seed: reusing {len(neg_ids)} negotiations from {args.seed_file}")
    else:
        neg_ids = phase_seed(args)
        phase_approve(sender, neg_ids, args.concurrency, setup_results)

    mapping = phase_wait(args.link_db_container, neg_ids, args.wait_timeout)
    if not mapping:
        print("No tickets found — cannot run the load loop. Check the stack and Zammad config.")
        sys.exit(1)

    # --- Measured region begins here -------------------------------------------------- #
    try:
        inbox_wm, outbox_wm = row_watermarks(args.link_db_container)
    except RuntimeError as e:
        print(f"      WARNING: could not read row_id watermarks ({e}); "
              "end-to-end processing measurement disabled")
        inbox_wm = outbox_wm = None

    capture_start = time.perf_counter()
    phase_load(sender, mapping, args, load_results)
    capture_wall_ms = (time.perf_counter() - capture_start) * 1000

    # End-to-end processing: wait until this run's inbox/outbox rows are all terminal.
    processing = None
    if inbox_wm is not None:
        print(f"[5/6] Processing: measuring end-to-end drain (timeout {args.processing_timeout}s)")
        try:
            drain_s, drained = wait_until_drained(
                args.link_db_container, inbox_wm, outbox_wm, args.processing_timeout)
            inbox_counts, outbox_counts = status_breakdown_since(
                args.link_db_container, inbox_wm, outbox_wm)
            # Total processing time spans from the first load-phase send to a drained queue.
            processing = {
                "drain_ms": (time.perf_counter() - capture_start) * 1000,
                "drained": drained,
                "inbox_counts": inbox_counts,
                "outbox_counts": outbox_counts,
            }
        except RuntimeError as e:
            print(f"      WARNING: processing measurement failed ({e})")

    failed = None
    if args.error > 0:
        try:
            failed = count_failed(args.link_db_container)
        except RuntimeError:
            failed = None

    errors = phase_report(load_results, capture_wall_ms, processing, failed)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
