# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# negotiator-link

Middleware that bridges **Negotiator** and external help desk systems (like Zammad). 
It receives webhooks from both sides, durably stores every inbound delivery,
and asynchronously reconciles state and messages between a negotiation and its help desk tickets.

Java and Spring Boot coding standards: [java-guidelines.md](.claude/java-guidelines.md)

## Stack

- Java 21, Spring Boot 4.1.x, Maven (use the `./mvnw` wrapper)
- PostgreSQL + Flyway for persistence; JPA/Hibernate (`ddl-auto: validate`)
- Spring Security OAuth2 Client Credentials for outbound Negotiator auth
- Micrometer + Prometheus for metrics; Spring `@Scheduled` for the pollers
- Lombok for boilerplate reduction
- Testing: JUnit 6 (Jupiter), Testcontainers (PostgreSQL) for integration tests against a real DB;
  ArchUnit (`archunit-junit6`) for the layering test
- Configuration via `application.yml` (prefer over `.properties`)

## Core design: accept-fast, process-asynchronously

A webhook request does the **least possible work** and returns `200` immediately. It never makes a
secondary synchronous call to Negotiator or a help desk inside the request thread. This is a
correctness requirement, not an optimization: Negotiator webhook delivery is **at-most-once with an
8 s timeout and no automatic retry on the original attempt**, so any slow synchronous call in the
request path risks breaching the budget and *losing the event*.

The request path is therefore only:

1. **Verify** — authenticate the delivery (signature + timestamp). Reject with `401` on failure.
2. **Capture** — `InboxService.capture(...)` persists the raw verified body as an `inbox_event` row
   (status `RECEIVED`), deduplicated by `(source, delivery_id)`.
3. **Return `200`** — acknowledgement means *durably accepted*, not *processed*.

All real work (parsing, routing, external I/O) happens later in background pollers.

### The inbox → outbox pipeline

```
 webhook ─► [verify] ─► InboxService.capture() ─► inbox_event (RECEIVED) ─► 200 OK
                                                        │
                        InboxPoller (claims batch, @Scheduled)
                                                        ▼
                        InboxEventProcessor.buildOutcome()  ← routers + handlers
                            (handlers do NO external write I/O; they return intents)
                                                        │
                        InboxEventProcessor.commit()  ── atomically ──►
                            inbox_event → PROCESSED/SKIPPED  +  outbox_intent rows (PENDING)
                                                        │
                        OutboxPoller (claims, per-negotiation workers, @Scheduled)
                                                        ▼
                        OutboxDeliverer → OutboxIntentDispatcher ─► Negotiator / help desk write
                            on success: outbox_intent → DELIVERED (+ ticket mappings, follow-ups)
```

- **Inbox** (`inbox_event`): one row per authenticated inbound delivery. Handlers translate it into
  platform-neutral **intents** without doing external writes; the intents and the inbox terminal
  status are written in **one transaction** (transactional outbox — no lost or orphaned work).
- **Outbox** (`outbox_intent`): one row per pending external action, delivered by `OutboxPoller`.
  Intents for the **same negotiation** are routed to the **same worker thread** (hash on
  negotiation id), so ordering within a negotiation is preserved while different negotiations run in
  parallel.
- Delivery is **at-least-once**: retries with exponential backoff
  (`BackoffPolicy`), claim leases guarded by a `claim_token` (a lease that expires is reclaimed by
  `OutboxClaimReaper`), failure classification (`FailureClass`: TRANSIENT → retry, PERMANENT →
  `FAILED_PERMANENT`, exhausted/unexpected → `DEAD_LETTER`), and supersession (a newer `SET_*` state
  intent cancels an older obsolete one so a retry backlog never replays stale state).

Status lifecycles:
- `InboxStatus`: `RECEIVED` → `PROCESSED` | `SKIPPED` | `FAILED`
- `OutboxStatus`: `PENDING` → `IN_PROGRESS` → `DELIVERED` | `FAILED_PERMANENT` | `DEAD_LETTER`

## The four layers (open/relaxed layering)

Enforced by `LayeredArchitectureTest` (ArchUnit) — a build-breaking test, not a convention.
Dependencies may only point **downward**:

```
inbound  ─►  core  ─►  adapter  ─►  common
```

```
src/main/java/eu/bbmri_eric/negotiator/negotiatorlink/
├── inbound/     # Controllers + webhook verification only. No business logic. Depends on nothing above it.
│               #   NegotiatorWebhookController, HelpdeskCallbackController → verify + InboxService.capture
├── core/        # All business logic. The only layer that may use `adapter`.
│   ├── config/        # OrganizationConfigRegistry, NegotiatorLinkProperties, security, scheduling, Jackson
│   ├── client/        # NegotiatorClient (+ bootstrap) — outbound calls TO Negotiator
│   ├── routing/        # NegotiatorEventRouter, HelpdeskEventRouter → pick a handler, return a RouteOutcome
│   ├── handler/        # One class per event type. Pure decision logic → returns HandlerResult (intents).
│   ├── reliability/    # Inbox/outbox engine: pollers, services, dispatcher, claim/backoff, payloads/
│   ├── service/        # Cross-handler domain services (e.g. AttachmentForwardingService)
│   ├── job/            # Scheduled maintenance (OutboxClaimReaper, StaleRecordsCleanupJob)
│   ├── entity/         # JPA entities (InboxEvent, OutboxIntent, TicketMapping, OriginatedPost, …)
│   ├── repository/     # Spring Data repositories
│   └── util/           # Hashing, formatting, payload helpers
├── adapter/     # HelpDesk integration SPI + per-platform implementations. May use `common`.
│   ├── HelpdeskClient (outbound), HelpdeskCallbackAdapter (inbound), HelpdeskTyped
│   └── zammad/        # ZammadClient, ZammadCallbackAdapter, ZammadFields
└── common/      # Shared DTOs/enums/records used across layers. The lowest layer — depends on NOTHING above.
    ├── config/        # OrganizationConfig, HelpdeskType, TicketStrategy, NegotiatorCredentials
    └── model/         # CreateTicketRequest, Attachment, CommentType, Sender, HelpdeskCallbackEvent, events…
```

## Architectural rules (non-negotiable)

- **Layering is downward-only** (`inbound → core → adapter → common`). Never make a lower layer
  depend on a higher one. If a type is needed by two layers, it belongs in `common`. Run the build;
  `LayeredArchitectureTest` fails the moment a dependency points the wrong way.
- **Controllers are thin.** `inbound` only verifies and captures to the inbox. No routing, no
  parsing of the domain payload, no external calls.
- **Handlers have a single responsibility and do no external write I/O.** A handler receives a
  parsed event and returns a `HandlerResult` of platform-neutral `PendingIntent`s. Routing is
  resolved *before* a handler is called; handlers never route. Side effects happen later in the
  outbox.
- **The adapter layer must never depend on Negotiator DTOs.** Translation Negotiator-model ⇄
  internal-model happens in `core`. Adapters speak only the `common` model + their platform's API.
- **Adding a new outbound action** = add an `OutboxIntentType`, a payload record under
  `reliability/payload/`, and a `case` in `OutboxIntentDispatcher`. **Adding a new help desk
  platform** = implement `HelpdeskClient` + `HelpdeskCallbackAdapter` under `adapter/<platform>/`
  and register its `HelpdeskType`.

## Migrations

- All schema changes via Flyway migrations in `src/main/resources/db/migration/`.
- Naming: `V{n}__{description_in_snake_case}.sql`.
- **Never modify an existing migration. Always add a new one** (`ddl-auto` is `validate`, so the
  entity and schema must match exactly).

## Security rules (non-negotiable)

- Secrets are **never hardcoded**. All credentials and signing secrets come from environment
  variables.
- The application must **fail to start** if a required secret environment variable is absent (note
  the required-vars in `application.yml` have no default, e.g. `${DB_PASSWORD}`).
- **Webhook signature and timestamp must be verified before any processing** (before the inbox
  capture). Reject with HTTP `401` on failure.
- SSL verification is **never** disabled in production.

## Configuration

- All tunables live under the `negotiator-link.*` prefix in `application.yml`, bound to
  `NegotiatorLinkProperties`. Per-organization config (help desk type, base URL, credentials,
  callback secret, ticket strategy, state↔state mappings) is supplied via `organizations:` /
  `ORGANIZATIONS_CONFIG_PATH` and exposed through `OrganizationConfigRegistry`.
- Poller/retry behaviour is under `negotiator-link.inbox.*` and `negotiator-link.outbox.*`
  (batch size, poll delay, max attempts, backoff bounds, worker count, claim timeout).

## Running locally & tests

- **Run the app locally:** `docker compose up -d` brings up a complete self-contained stack
  (Negotiator + Zammad + this service, auto-configured). On first run, copy `.env.dev` to `.env`.
  Startup takes 3–5 min. See the **Quickstart** in [README.md](README.md). Operational/deploy
  details live in [docs/deployment.md](docs/deployment.md); init-setup notes in
  `docs/negotiator-init-setup.md` and `docs/zammad-init-setup.md`.
- **Test split (by filename):** `*Test.java` = unit tests (Surefire, no Docker, fast).
  `*IT.java` = integration tests (Failsafe) that use **Testcontainers** and therefore **require a
  running Docker daemon**.
- **Inner loop (fast):** `./mvnw test` runs only the unit tests — no Docker needed. To run one
  class: `./mvnw test -Dtest=OutboxDelivererTest`. Integration tests run in `verify`/`install`.
- **Run a single integration test:** `./mvnw verify -Dit.test=InboxPollerIT` (Docker must be up).

## Build verification

The final gate before considering a task complete — run it and ensure all tests pass:

```
./mvnw com.spotify.fmt:fmt-maven-plugin:format -q && ./mvnw clean install
```

`clean install` runs the **full** suite including the Testcontainers `*IT.java` tests, so a Docker
daemon must be running. During iteration prefer the fast inner-loop commands above; run the full
gate before finishing.