# Negotiator — Help Desk Integration

## 1. Goal of the Integration

The goal of the integration is to allow representatives of individual organizations to manage access decisions for
resources they own without leaving their organization's existing help desk system. Representatives should be able to
perform all their negotiation-related duties from within the help desk environment they are already familiar with,
rather than having to open the Negotiator application.

The integration deliberately covers only the **representative workflow**. It does not allow researchers to create new
negotiations or navigate the full negotiation lifecycle through a help desk. This scope limitation is intentional:
creating a negotiation is tightly coupled to the Negotiator UI, the Dynamic Form (which is configurable per deployment),
and the resource discovery workflow. These cannot be meaningfully replicated inside a generic help desk ticket.

Within a negotiation, a user with the **Representative** role can:

- Change the state of a resource they are responsible for.
- Add **public** comments (visible to all parties in the negotiation).
- Add **private** comments (visible only to their organization and the requester/admin).

The integration enables representatives to perform the first and third of these actions within their help desk system.
Messages sent from the help desk are always forwarded to Negotiator as **private** posts (see Decision 5).
Representatives who need to send a public message must do so from the Negotiator UI directly.

## 2. Integration Decisions

### Architectural Decisions

#### Decision 1: Standalone Service vs. Negotiator Extension

**Decision:** The help desk integration will be implemented as a standalone middleware service deployed separately from
Negotiator.

**Justification:**

- The integration needs to be opt-in per organization, deployable as a Docker sidecar, and stateless where possible. A
  Negotiator extension would couple the help desk integration into Negotiator's release cycle, require Negotiator
  downtime for deployment, and make it harder to isolate the help desk credentials (all would be part of the Negotiator
  application).
- The webhook dispatch system is completely decoupled from the notification system (see findings Q9). Negotiator does
  not need to be extended to dispatch events to the middleware since the existing webhook mechanism is sufficient. Some
  webhooks would need to be added (e.g. for attachement upload) as described in Section 7 (Implementation plan).
- A standalone service allows independent versioning, independent scaling, and freedom of programming language choice.

#### Decision 2: Generic Configurable Adapter vs. Per-Platform Dedicated Adapters

**Decision:** The middleware will contain a shared integration core and a well-defined adapter interface.
Platform-specific behavior will be implemented through dedicated adapters. The initial release will include one adapter
implementation that serves as a proof of concept and a reference for future integrations.

**Justification:**

- We need to support additional help desk platforms without modifying the middleware core. Platform-specific API
  communication is encapsulated in dedicated adapters, while shared functionality — event processing, routing, retries,
  deduplication, and communication with Negotiator — remains centralized in the middleware core.
- A purely YAML-driven generic adapter is too brittle for production use: help desk platforms have wildly different
  field models, authentication flows, status lifecycle schemas, and callback authentication mechanisms.

#### Decision 3: Per-Organization Routing

**Decision:** The middleware will receive all Negotiator webhook events and perform organization-based routing
internally.

**Justification:**

- Negotiator does not currently support per-organization webhook routing (see findings Q8). Adding this capability would
  require changes to the `Webhook` domain model, repository layer, webhook dispatch logic (`WebhookEventListener`), REST
  APIs, and the administration UI. This would introduce unnecessary complexity into Negotiator for a problem that can be
  solved cleanly within the middleware.
- The middleware will expose a single webhook endpoint (`POST /webhooks/negotiator`) and determine the target
  organization as described in Section 4 (Routing).
- The middleware configuration will define which organizations have help desk integration enabled. Events for
  organizations with no config entry are silently acknowledged and discarded — no dead-letter queue entry, no error.

---

### Solution Decisions

#### Decision 4: Ticket Creation Strategy

Four options were considered for how to map a Negotiator negotiation to help desk tickets when an organization owns
multiple resources in that negotiation.

**Option A — One ticket per organization per negotiation**

A single ticket is created for each organization involved in a negotiation, regardless of how many resources that
organization owns. All resource states are represented as structured fields inside the ticket body. Write-backs from the
help desk apply to all resources of that organization at once using the `PATCH /v3/negotiations/{id}/resources` bulk
endpoint.

Advantages:

- Private posts in Negotiator are org-scoped, not resource-scoped. One ticket maps cleanly to one communication thread
  with no message duplication.
- Simpler ticket lifecycle — one ticket per org covers all state transitions.

Limitations:

- A representative cannot independently accept or refuse individual resources from the help desk. All state changes
  apply to all of the organization's resources in that negotiation simultaneously. Making per-resource decisions via the
  Negotiator UI directly would also become problematic, as the resulting states would be inconsistent with the resource
  states displayed in the single help desk ticket.

**Option B — One ticket per resource**

A separate ticket is created for each resource, even when multiple resources belong to the same organization. All
tickets created for the same negotiation and organization are linked together as related tickets in the help desk, so
representatives can easily navigate between them.

Advantages:

- A representative can independently accept or refuse individual resources from the help desk. Each ticket maps to a
  single resource state machine.
- Related ticket linking groups all of an organization's resource tickets visually within the help desk without
  requiring any content to be split or duplicated.

Limitations:

- Private posts are org-scoped in Negotiator. When a private post occurs, the middleware must fan it out to all resource
  tickets of that organization in that negotiation.
- Negotiation-level state changes (e.g. negotiation info changed, file attached) must be propagated to all resource
  tickets belonging to that negotiation. This is a straightforward fan-out in the middleware, but it means more ticket
  update calls per negotiation-level event.

**Option C — Parent-child ticket structure**

One parent ticket is created per organization per negotiation, holding general negotiation information. One child ticket
is created per resource, handling the individual resource state machine. Child tickets are linked to the parent.

Advantages:

- General negotiation information is written once in the parent ticket rather than duplicated across every resource
  ticket.
- The parent-child hierarchy visually groups all resource tickets under a single parent within the help desk.

Limitations:

- The Negotiator data model does not support a reliable content split between "general" and "resource-specific"
  information. The `payload` column on the `negotiation` table stores a flat JSON object whose sections are determined
  entirely by configurable access forms. There is no deployment-independent rule for deciding what belongs in the parent
  ticket vs. the child tickets — any heuristic produces incorrect results in at least some deployments. See
  `parent-child-tickets-vs-related-tickets.md` for the full analysis.
- Even if a section is common to all resources and placed in the parent ticket, it cannot safely be removed from the
  child tickets, as it may carry resource-specific answers. This leads to either unavoidable duplication or omission of
  relevant context.

**Option D — Configurable per organization (Option A or Option B)**

The ticket creation strategy is configurable per organization. Each organization can independently choose whether it
receives a single ticket per negotiation covering all of its resources (Option A) or a separate ticket per resource with
related ticket linking (Option B). The configuration is expressed as a field in the YAML organization config entry (e.g.
`ticketStrategy: PER_NEGOTIATION` or `ticketStrategy: PER_RESOURCE`).

Advantages:

- Organizations with a simple workflow or few resources per negotiation can opt for a single consolidated ticket,
  reducing noise in their help desk queue.
- Organizations that need to act on resources independently can opt for per-resource tickets.

Limitations:

- It increase in middleware complexity.
- Representatives at `PER_NEGOTIATION` organizations cannot independently accept or refuse individual resources from the
  help desk (same limitation as Option A).

**Decision:** Configurable per organization (Option D).

**Justification:**

- Different organizations have different workflows. Some organizations may have only one or two resources per
  negotiation and prefer a single consolidated ticket; others may have many resources that must be acted on
  independently. A single fixed strategy cannot serve all cases optimally.
- Option D subsumes Options A and B: it delivers the full granularity of Option B for organizations that need it and the
  simplicity of Option A for those that do not.
- Option C (parent-child) was rejected because the Negotiator data model provides no reliable basis for splitting
  content between a parent and child ticket. Related ticket linking (used in the `PER_RESOURCE` strategy of Option D)
  achieves the same grouping benefit without requiring any content split. See
  `parent-child-tickets-vs-related-tickets.md` for the detailed analysis.

#### Decision 5: Private-Only vs. Both Private and Public Posts from the Help Desk

Two options were considered for which post visibility types the middleware should support when forwarding messages from
the help desk to Negotiator.

**Option A — Support both PRIVATE and PUBLIC posts**

The middleware exposes a custom field `negotiator_post_type` on each ticket that the representative can set to either
`PRIVATE` or `PUBLIC` before sending a message. The middleware reads this field and forwards the message to Negotiator
with the corresponding visibility type.

Advantages:

- Representatives can send public messages visible to all negotiation participants directly from the help desk.

Limitations:

- Public messages from the help desk are visible to all organizations participating in the negotiation. A representative
  could inadvertently leak internal deliberation or organization-specific context to other participants by using the
  wrong visibility setting.
- The `negotiator_post_type` custom field adds configuration overhead for the help desk administrator and a cognitive
  burden for the representative on every message.

**Option B — Support only PRIVATE posts**

All messages sent from the help desk are forwarded to Negotiator as `PRIVATE` posts, visible only to the sending
organization, the researcher, and the administrator. No `negotiator_post_type` field is needed. If a help desk platform
distinguishes between internal and external articles, the middleware processes only external articles; internal articles
are ignored since they are used for internal communication within company.

Advantages:

- Eliminates the risk of accidental public disclosure. A representative communicating from the help desk is working
  within their organization's internal tooling, making private visibility the correct default.
- Simplifies the help desk configuration — one fewer custom field to create and maintain. More user-friendly for
  representatives — no need to remember to set the visibility field correctly on every message.
- Simplifies the middleware logic — no visibility field to read or validate.

Limitations:

- Representatives cannot send public messages from the help desk. If they need to communicate publicly with all
  participants, they must use the Negotiator UI directly.

**Decision:** Support only PRIVATE posts from the help desk (Option B).

**Justification:**

- The help desk is an internal organizational tool. Messages originating there are private in nature — representatives
  discussing a request internally or communicating directly with the researcher and admin. Allowing public messages from
  the help desk introduces a disclosure risk that is not justified by the use case.
- Supporting only private posts removes the `negotiator_post_type` custom field entirely, simplifying both the help desk
  setup and the middleware implementation.
- Representatives who need to post a public message can do so from the Negotiator UI, where the visibility of the
  message relative to all participants is explicit and visible in context.

#### Decision 6: Actor Identity for Posts Created via the Help Desk

**Decision:** When the middleware posts a message to Negotiator on behalf of a representative, it passes the
representative's identity from the help desk as an `X-Helpdesk-Actor` request header. Negotiator stores this value in a
new nullable `helpdesk_actor VARCHAR` column on the `Post` entity and returns it in `PostDTO`. The `Post.createdBy` FK
continues to reference the middleware service account. `helpdeskActor` will be a display-only supplement, not a
reference to a Negotiator user.

**Justification:**

- The middleware authenticates to Negotiator using the OAuth 2.0 Client Credentials flow. Without this mechanism, all
  posts would be attributed to the middleware service account in the Negotiator UI and API, making it impossible to
  distinguish which representative sent a given message.
- An alternative would be to prepend the representative's identity to the post body text (e.g.
  `[Via help desk: John Smith <john.smith@mhh.de>]`). This requires no schema changes but embeds identity in free-form
  text, making it fragile. A dedicated `helpdeskActor` column is explicit, queryable, and renders cleanly alongside the
  `createdBy` service account without polluting the post content.
- Requiring the representative to have a Negotiator account is not acceptable. The integration is deliberately designed
  so that representatives never need to interact with Negotiator directly. Maintaining a mirrored user registry across
  both systems would introduce operational burden with no security benefit, since authorization is already enforced by
  the help desk: only users with access to the help desk ticket can trigger the resource state transition.
- The actor identity is therefore treated as a free-form display string (typically the representative's email or full
  name as exposed by the help desk platform), not a foreign key. The `X-Helpdesk-Actor` header is only accepted from
  callers holding `ROLE_HELPDESK_INTEGRATION` (regular API users cannot set it).

#### Decision 7: Actor Identity for Resource State Transitions Triggered via the Help Desk

**Decision:** When the middleware triggers a resource state transition on behalf of a representative, it passes the
representative's identity as an `X-Helpdesk-Actor` request header. Negotiator stores this value in a new nullable
`helpdesk_actor VARCHAR` column on `NegotiationResourceLifecycleRecord` and uses it as the display name in the
negotiation timeline when present, falling back to `createdBy.getName()` for transitions triggered by interactive users.

**Justification:**

- Resource state transitions are recorded in `NegotiationResourceLifecycleRecord`, which extends `AuditEntity`. The
  `createdBy` field is populated automatically by Spring JPA Auditing (`@CreatedBy`) from the currently authenticated
  subject (the middleware service account). Without this mechanism, all help-desk-triggered transitions would appear in
  the timeline as performed by the service account, not the actual representative.
- The same reasoning as Decision 6 applies: requiring representatives to hold Negotiator accounts is not acceptable, and
  the help desk is the correct authorization boundary.
- Because `NegotiationResourceLifecycleRecord` is created inside the state machine persistence listener (
  `ResourcePersistStateChangeListener`), the actor value must be threaded through the state machine message.

#### Decision 8: Implementation Language — Python vs. Java and Spring Boot

**Decision:** The middleware will be implemented in Java using the Spring Boot framework.

**Justification:**

- The middleware is not a simple forwarding script. It must implement a non-trivial amount of logic: webhook signature
  verification, replay attack prevention, org-based routing, idempotency tracking, a pluggable adapter interface
  supporting multiple help desk platforms, OAuth 2.0 Client Credentials token management, and retry and error handling.
  This scope justifies a structured application framework rather than a lightweight scripting approach.
- Spring Boot provides the infrastructure for all of the above out of the box: Spring Security for OAuth 2.0 and webhook
  authentication, Spring Web for REST endpoints, Spring Retry for reliable delivery, and a mature ecosystem for secrets
  management and configuration.
- The technology stack matches Negotiator. The middleware will likely be maintained by the same team. Using the same
  language and framework minimises the context switch between the two codebases, lowers the barrier for contributors
  already familiar with the Negotiator codebase, and makes it easier to share knowledge about the integration design
  decisions.

**Limitations:**

- Java and Spring Boot have a higher startup time and more boilerplate compared to a Python service. A Spring Boot
  application typically takes several seconds to start, which is irrelevant in a long-running container deployment but
  is noticeable during local development or after an unexpected restart.
- The initial project setup and build tooling (Maven, JVM, container image size) are heavier than a typical Python
  project. This is an acceptable trade-off given the expected long-term maintenance requirements.

## 3. Integration Overview

### 3.1 Communication Schema

![Routing](img/routing.png)

### 3.2 Negotiator → Middleware → Help Desk

The integration builds on Negotiator's existing webhook mechanism. When a relevant event occurs in Negotiator (e.g. a
negotiation is approved, a post is added, a resource state changes), Negotiator dispatches a signed webhook to all
configured endpoints. The Negotiator admin configures the middleware's inbound endpoint as one of these webhook targets.

Webhook payloads are intentionally kept lean. They carry enough data to identify what changed, but not the full content
needed to act on it. For example, the `negotiation.post.added` event carries a `postId` but not the post text itself.
Extending webhook payloads to carry all data would couple the webhook schema tightly to the help desk integration and
add unnecessary payload size for all other webhook consumers. Instead, after receiving a webhook the middleware performs
a **secondary REST call** to Negotiator to fetch any additional data it needs (e.g. the post text, the full negotiation
details, or the organization's external ID). Once it has all the required information, the middleware calls the
appropriate method on the platform adapter, which in turn makes the necessary API calls to the target help desk.

Existing webhook payloads do not need to be extended. Routing to the correct organization and help desk ticket is
resolved entirely through the `ticket_mapping` table stored in the middleware (see Section 4). However, new webhook
event types will need to be introduced in Negotiator where events are not yet emitted at all. The identified gap was the
absence of webhook events for attachment uploads and for transferring a negotiation to another researcher.

### 3.3 Help Desk → Middleware → Negotiator

The reverse direction allows representatives to change a resource's state and post private messages directly from within
the help desk.

#### 3.3.1 Help Desk Configuration

Before the integration can function, a help desk administrator must perform a one-time configuration. Help desk
platforms such as Zammad and ServiceNow support defining custom fields on tickets (
see [Zammad Objects](https://admin-docs.zammad.org/en/latest/system/objects.html), [ServiceNow Custom Fields](https://docs.getint.io/guides/integration-synchronization/jira-servicenow-integration/creating-a-custom-field-in-servicenow))
as well as conditional field display and editability rules (
see [Zammad Core Workflows](https://admin-docs.zammad.org/en/6.4/system/core-workflows.html), [ServiceNow UI Policies](https://www.servicenow.com/docs/r/yokohama/platform-administration/ai-platform-administration/t_CreateAUIPolicy.html)).

The following custom fields must be created in the help desk:

| Field                                  | Type             | Editable by user | Purpose                                                |
|----------------------------------------|------------------|------------------|--------------------------------------------------------|
| `negotiation_state`                    | select           | No               | Displays the current negotiation state                 |
| `negotiator_resource_state`            | select           | No               | Displays the current resource state(s) for this ticket |
| `negotiator_resource_transition_event` | select, nullable | Yes              | Representative requests a resource(s) state transition |

The field names are fixed and must match exactly what the middleware reads and writes. The **label** shown to
representatives in the help desk UI is set by the help desk administrator and should reflect the organization's
`ticketStrategy`:

- `negotiator_resource_state` label:
  - `PER_RESOURCE` — e.g. **"Resource State"**
  - `PER_NEGOTIATION` — e.g. **"Resource States"**

- `negotiator_resource_transition_event` label:
  - `PER_RESOURCE` — e.g. **"Request State Transition"**
  - `PER_NEGOTIATION` — e.g. **"Request State Transition (all resources)"**

In addition, the administrator must configure two webhook triggers in the help desk that call the middleware's inbound
endpoint:

- **New comment trigger** — fires when a new external message is added to a ticket. The trigger condition must exclude
  messages created by the middleware's own bot account to prevent feedback loops.
- **Resource transition trigger** — fires when `negotiator_resource_transition_event` is set to a non-null value. Again,
  the middleware's bot account must be excluded from the trigger condition to prevent the middleware's own
  field-clearing action from re-triggering the callback.

In Zammad, this is achieved using a combination of Triggers and Webhooks (
see [Zammad Triggers](https://admin-docs.zammad.org/en/6.5/manage/trigger/how-do-they-work.html)).

### 3.4 Resource State Transition Flow

When a representative wants to advance a resource's state, they select the desired transition event in the
`negotiator_resource_transition_event` dropdown and save the ticket. This causes the help desk to send a webhook
callback to the middleware.

The middleware looks up all `ticket_mapping` rows for the callback's `ticketId` and `organizationExternalId` to
determine the `negotiationId` and the list of `resourceId` values covered by the ticket. The subsequent validation and
transition logic differs by `ticketStrategy`:

**PER_RESOURCE** (single resource per ticket)

The middleware validates the requested transition by calling
`GET /v3/negotiations/{negotiationId}/resources/{resourceId}/lifecycle` to retrieve the set of currently allowed events.

- **If the transition is valid:** the middleware calls
  `POST /v3/negotiations/{negotiationId}/resources/{resourceId}/lifecycle/{event}` to apply the transition. Negotiator
  persists
  the change and asynchronously dispatches a `negotiation.resource.state.updated` webhook back to the middleware. The
  middleware processes this webhook normally: it updates the `negotiator_resource_state` field on the ticket and clears
  `negotiator_resource_transition_event` back to `null`.
- **If the transition is invalid:** the middleware clears `negotiator_resource_transition_event` back to `null` on the
  ticket and posts a system comment explaining why the transition was rejected and listing the currently allowed
  transitions. A transition is invalid either because the negotiation is not in `IN_PROGRESS` state or because the
  selected event is not permitted from the resource's current state.

**PER_NEGOTIATION** (all resources in the negotiation share one ticket)

The middleware validates the requested transition against every resource covered by the ticket by calling
`GET /v3/negotiations/{negotiationId}/resources/{resourceId}/lifecycle` for each. A transition is considered valid only
if it is permitted for **all** resources.

- **If the transition is valid for all resources:** the middleware records the `(negotiationId, ticketId)` combination
  in the `originated_transitions` table (see Section 3.4.1) before calling the `PATCH /v3/negotiations/{id}/resources`
  bulk endpoint. Negotiator dispatches a `negotiation.resource.state.updated` webhook for each affected resource. The
  middleware uses the `originated_transitions` record to update `negotiator_resource_state` on the ticket exactly once
  and clear `negotiator_resource_transition_event` back to `null`, discarding the remaining redundant webhooks for the
  same ticket (see Section 3.4.1).
- **If the transition is invalid for one or more resources:** the middleware clears
  `negotiator_resource_transition_event` back to `null` on the ticket and posts a system comment listing which resources
  cannot accept the transition and what transitions are currently allowed for each. The representative will be blocked
  in the help desk from applying a resource state transition and will be informed in the system comment that they need
  to continue in the negotiation in the Negotiator UI to resolve the discrepancy.

Note: the endpoint `POST /v3/negotiations/{negotiationId}/resources/{resourceId}/lifecycle/{event}` expects as a
resourceId a sourceId of the resource which can be obtained from ticket_mapping table as resource_source_id.

The middleware includes the representative's identity from the help desk as the `X-Helpdesk-Actor` request header on all
Negotiator calls, so transitions are recorded in the negotiation timeline under the representative's name rather than
the middleware service account (see Decision 7).

#### 3.4.1 Deduplication of Resource State Update Webhooks for PER_NEGOTIATION Tickets

When the middleware applies a bulk transition for a `PER_NEGOTIATION` organization, Negotiator dispatches one
`negotiation.resource.state.updated` webhook per affected resource. All of these webhooks would update the same help
desk ticket to the same new state, producing N−1 redundant API calls. To handle this, the middleware maintains an
`originated_transitions` table:

| Column           | Type      | Constraints         |
|------------------|-----------|---------------------|
| `row_id`         | NUMBER    | PK, SEQUENCE        |
| `negotiation_id` | VARCHAR   | NOT NULL            |
| `ticket_id`      | VARCHAR   | NOT NULL            |
| `expected_count` | INTEGER   | NOT NULL            |
| `received_count` | INTEGER   | NOT NULL, DEFAULT 0 |
| `new_state`      | VARCHAR   | NOT NULL            |
| `created_at`     | TIMESTAMP | NOT NULL            |

Before calling the bulk transition endpoint, the middleware inserts a row into `originated_transitions` with the
`negotiationId`, `ticketId`, the number of resources being transitioned as `expected_count`, and the target state as
`new_state`.

When a `negotiation.resource.state.updated` webhook arrives, `ResourceStateUpdatedHandler` checks whether the
`(negotiationId, ticketId)` combination has an entry in `originated_transitions`. If a matching row is found:

- The handler increments `received_count` atomically.
- If this is the **first** webhook for this record (`received_count` becomes 1 after increment): the handler updates
  `negotiator_resource_state` on the ticket and clears `negotiator_resource_transition_event` to `null`.
- If `received_count` equals `expected_count`: the row is deleted from `originated_transitions`.
- All subsequent webhooks for the same record (where `received_count` > 1) are acknowledged and discarded without
  calling the help desk.

If no matching row is found, the webhook is for a state change triggered outside the help desk (e.g. directly from
Negotiator UI) and is processed normally.

Rows in `originated_transitions` are removed either when fully consumed or by a scheduled cleanup job that deletes rows
older than 24 hours.

### 3.5 Post Message Flow

When a representative sends an external message from the help desk, the middleware receives it via webhook callback. If
the help desk platform distinguishes between internal and external articles (e.g. Zammad), only external articles are
processed; internal articles are silently ignored as they are intended for intra-organization communication only.

Before forwarding the message to Negotiator, the middleware checks whether the negotiation is in a state that allows
posts (IN_PROGRESS, PAUSED, CONCLUDED). If the negotiation state does not permit posting, the middleware does not
forward the message and instead adds a system comment to the ticket explaining the reason.

If the state permits posting, the middleware calls `POST /v3/negotiations/{negotiationId}/posts` on Negotiator with
visibility type `PRIVATE`. The representative's identity from the help desk is passed as the `X-Helpdesk-Actor` request
header so the post is attributed to the representative rather than the middleware service account (see Decision 6).

**OPEN QUESTION:** Does we want to allow post messages also for CONCLUDED negotiation state? Currently negotiator
disable posts when state changed IN_PROGRESS → ABANDONED, however not for PAUSED → ABANDONED which is probably the
implementation bug

Overview of allowed post visibility types by negotiation state:

| Negotiation state            | Public posts | Private posts |
|------------------------------|:------------:|:-------------:|
| DRAFT                        |      No      |      No       |
| SUBMITTED                    |     Yes      |      No       |
| IN_PROGRESS                  |     Yes      |      Yes      |
| PAUSED                       |     Yes      |      Yes      |
| CONCLUDED                    |     Yes      |      Yes      |
| DECLINED                     |      No      |      No       |
| ABANDONED (from IN_PROGRESS) |      No      |      No       |
| ABANDONED (from PAUSED)      |     Yes      |      Yes      |

#### 3.5.1 Deduplication of Outbound Posts

When the middleware posts a message to Negotiator, Negotiator persists it and asynchronously fires a
`negotiation.post.added` webhook back to the middleware. The middleware must not relay this webhook back to the
originating help desk ticket as a duplicate comment. To handle this, the middleware maintains an `originated_posts`
table:

| Column            | Type      | Constraints  |
|-------------------|-----------|--------------|
| `row_id`          | NUMBER    | PK, SEQUENCE |
| `text_hash`       | VARCHAR   | NOT NULL     |
| `organization_id` | VARCHAR   | NOT NULL     |
| `ticket_id`       | VARCHAR   | NOT NULL     |
| `created_at`      | TIMESTAMP | NOT NULL     |

Before forwarding a post to Negotiator, the middleware computes a hash of the message text and inserts a row into
`originated_posts` with the hash, organization ID (retireved from table `ticket_mapping`, see Section 4) and the
originating `ticket_id`. When a `negotiation.post.added` webhook subsequently arrives, the middleware fetches the post
text and post type, computes hash of the post text, and checks `originated_posts` for a matching record by hash and
organization ID. If a match is found, that ticket is excluded from the fan-out and the matching row is deleted.

Two edge cases are handled explicitly:

- **Two identical messages sent from different tickets at the same time** — when multiple matching hashes in combination
  with organization ID exist in `originated_posts`, only one record is consumed per webhook delivery with the earliest
  `created_at`, so only one ticket is excluded from the fan-out. It is assumed that another webhook will arrive to
  process another record with same `text_hash` and `organization_id` from the table.
- **Two identical messages sent from the same ticket in quick succession** — the `row_id` sequence ensures that each
  message produces a distinct row even when the `text_hash` and `ticket_id` are identical. A composite primary key on
  `(text_hash, ticket_id)` alone would be insufficient.

Rows in `originated_posts` are removed either when consumed by the deduplication logic or by a scheduled cleanup job
that deletes rows older than 24 hours.

## 4. Routing

This section describes how the middleware routes events in both directions — Negotiator → Middleware → Help Desk and
Help Desk → Middleware → Negotiator. It focuses on the routing logic itself and does not cover which secondary API calls
the middleware must make to Negotiator in order to retrieve data not present in the webhook payload. Those details are
covered per-handler in Section 6 (Middleware Architecture) and Section 7 (Implementation Plan).

### 4.1 Middleware State

#### ticket_mapping Table

The middleware persists a `ticket_mapping` table that is the single source of truth for routing in both directions:

| Column                | Type    | Constraints  |
|-----------------------|---------|--------------|
| `negotiation_id`      | VARCHAR | composite PK |
| `helpdesk_ticket_id`  | VARCHAR | composite PK |
| `resource_id`         | VARCHAR | composite PK |
| `resource_source_id`  | VARCHAR | NOT NULL     |
| `organization_id`     | VARCHAR | NOT NULL     |
| `organization_ext_id` | VARCHAR | NOT NULL     |

For `PER_RESOURCE` organizations each resource has its own row with a distinct `helpdesk_ticket_id`. For
`PER_NEGOTIATION` organizations all resources in the negotiation share the same `helpdesk_ticket_id`, resulting in
multiple rows with the same ticket ID but different `resource_id` values.

#### Organization Configuration

The middleware also holds a YAML configuration file that defines which organizations have help desk integration enabled.
Each entry carries the connection details for that organization's help desk instance. Credentials are never hardcoded —
they are injected from environment variables at startup. An example configuration (probably will be extended during
implementation, when some of the requeired fields will be identified):

```yaml
organizations:
  - organizationExternalId: "bbmri:DE:MHH"
    type: "zammad"
    ticketStrategy: "PER_RESOURCE"
    baseUrl: "https://zammad.mhh.de"
    credentials:
      apiToken: "${ZAMMAD_MHH_API_TOKEN}"
    callbackSecret: "${ZAMMAD_MHH_CALLBACK_SECRET}"
    groupId: 3 # this in Zammad identify the ticket type to which the custom fields are linked

  - organizationExternalId: "bbmri:NL:AMC"
    type: "zammad"
    ticketStrategy: "PER_NEGOTIATION"
    baseUrl: "https://zammad.amc.nl"
    credentials:
      apiToken: "${ZAMMAD_AMC_API_TOKEN}"
    callbackSecret: "${ZAMMAD_AMC_CALLBACK_SECRET}"
    groupId: 5

  - organizationExternalId: "bbmri:UK:OXFORD"
    type: "serviceNow"
    ticketStrategy: "PER_RESOURCE"
    baseUrl: "https://oxford.service-now.com"
    credentials:
      username: "${SERVICENOW_USERNAME}"
      password: "${SERVICENOW_PASSWORD}"
    callbackSecret: "${SERVICENOW_CALLBACK_SECRET}"
    assignmentGroupId: "287ebd7da9fe198100f92cc8d1d2154e"
```

### 4.2 Negotiator → Middleware → Help Desk

Representatives can only act on resources once the negotiation has been approved by an admin and is in the `IN_PROGRESS`
state. Ticket creation is therefore triggered by a `negotiation.state.updated` webhook event where `event` is `APPROVE`.

When the middleware receives this event, it fetches the full negotiation details from Negotiator including all resources
and their associated `organizationExternalId`. The resources are first grouped by `organizationExternalId`. For each
organization group the following routing logic is applied:

```
for each (organizationExternalId, resources[]) group:
    look up organizations[] config by organizationExternalId
         │
         ├── not found → org not opted in → log and skip silently
         │
         └── found → use help desk type, baseUrl, credentials, ticketStrategy, etc.
                  │
                  ├── ticketStrategy = PER_NEGOTIATION
                  │        │
                  │        ▼
                  │    create one ticket covering all resources of this org
                  │        │
                  │        ▼
                  │    for each resource in resources[]:
                  │        INSERT INTO ticket_mapping:
                  │            negotiation_id      = negotiation.negotiationId
                  │            helpdesk_ticket_id  = ticketId returned by help desk
                  │            resource_id         = resource.Id
                  │            resource_source_id  = resource.sourceId
                  │            organization_id     = resource.organizationId
                  │            organization_ext_id = organizationExternalId
                  │
                  └── ticketStrategy = PER_RESOURCE
                           │
                           ▼
                       for each resource in resources[]:
                           create one ticket for this resource
                               │
                               ▼
                           INSERT INTO ticket_mapping:
                               negotiation_id      = negotiation.negotiationId
                               helpdesk_ticket_id  = ticketId returned by help desk
                               resource_id         = resource.Id
                               resource_source_id  = resource.sourceId
                               organization_id     = resource.organizationId
                               organization_ext_id = organizationExternalId
                           │
                           ▼
                       link all created tickets as related
                       (call adapter.linkRelatedTickets() for each pair)
```

For all subsequent events (e.g. negotiation state change, new post, resource state change), the middleware looks up all
rows in `ticket_mapping` by `negotiationId`. For each returned row it retrieves the organization's connection details
from the YAML config and calls the appropriate adapter method, which performs the necessary API call to the target help
desk.

### 4.3 Help Desk → Middleware → Negotiator

The middleware exposes an inbound endpoint `POST /webhooks/helpdesk/{organizationExternalId}` that receives all
callbacks from help desks. The callback payload always contains the `ticketId` of the ticket that triggered the
callback. The middleware looks up all rows in `ticket_mapping` by `ticketId` and `organizationExternalId` (the
`ticketId` is not sufficient on its own since it does not need to be unique across help desks).

For a `PER_RESOURCE` organization this lookup returns a single row, and the middleware resolves a single `negotiationId`
and `resourceId` to act on.

For a `PER_NEGOTIATION` organization this lookup returns multiple rows — one per resource sharing that ticket. The
middleware resolves the `negotiationId` from any row (it is the same across all rows for the same ticket) and passes the
full list of `resourceId` values to the handler so it can apply the requested change. State transition to all resources
it applies at once via the `PATCH /v3/negotiations/{id}/resources` bulk endpoint. For the post message it will do only
one post to the negotiation in a same way as it is done in PER_RESOURCE ticket strategy.

This Negotiator call causes new webhook events to be dispatched back to the middleware (e.g. a resource state change
triggers `negotiation.resource.state.updated` for each affected resource). The middleware processes these webhooks
normally, updating the relevant fields in the help desk ticket following the same Negotiator → Middleware → Help Desk
flow described in Section 4.2. Middleware also needs to ensure deduplication as described in subsections 3.4.1 and
3.5.1.

## 5. Security

The middleware must secure communication in all four directions: inbound webhooks from Negotiator, outbound REST calls
to Negotiator, outbound API calls to help desks, and inbound callbacks from help desks. Each direction has distinct
authentication and verification requirements. This section describes the security measures applied at each boundary.

### 5.1 Security Between the Middleware and Negotiator

#### 5.1.1 Negotiator → Middleware (Webhook Delivery)

Negotiator signs every outbound webhook using HMAC-SHA256 per the Standard Webhooks specification. The signing secret is
configured in Negotiator's webhook entity and shared with the middleware via the environment variable
`NEGOTIATOR_WEBHOOK_SECRET`.

On every inbound webhook the middleware performs three checks in order before any processing happens:

```
POST /webhooks/negotiator arrives
         │
         ▼
1. Verify Webhook-Signature header
   HMAC-SHA256(secret, webhookId + "." + timestamp + "." + body)
   → mismatch: reject HTTP 401, stop

         │
         ▼
2. Verify Webhook-Timestamp header is within ±5 minutes of current server time
   → outside window: reject HTTP 401, stop

         │
         ▼
3. Check Webhook-Id against seen-cache (TTL 15 minutes)
   → already seen: return HTTP 200, discard silently (idempotent)

         │
         ▼
process it and return HTTP 200
```

#### 5.1.2 Middleware → Negotiator (REST API Calls)

The middleware authenticates against Negotiator using the OAuth 2.0 Client Credentials flow. It uses a `clientId` and
`clientSecret` to obtain a Bearer token from the configured OIDC provider. The access token is cached and automatically
refreshed before expiration.

Only the `negotiator_helpdesk` scope is requested. Within Negotiator, `CustomJWTAuthConverter` maps this scope to the
Spring Security authority `ROLE_HELPDESK_INTEGRATION`. This role allows the middleware service account to perform a
limited set of actions that are otherwise restricted to interactive users.

The `clientId`, `clientSecret`, and `NEGOTIATOR_WEBHOOK_SECRET` are injected exclusively via environment variables —
never hardcoded in configuration files.

##### Posting Messages

`PostServiceImpl.checkAuthorization()` is extended with an additional authorization branch:

```java
||AuthenticatedUserContext.hasRole("ROLE_HELPDESK_INTEGRATION")
```

A client holding this role may:

- Post **PUBLIC** messages if the negotiation state enables it.
- Post **PRIVATE** messages to negotiations if the negotiation state enables it.

##### Resource Lifecycle Transitions

Support for the help desk integration is added through two changes in Negotiator.

**1. NegotiationController**

`NegotiationController.sendEventForNegotiationResource()` gains an additional authorization condition alongside the
existing creator check:

```java
||AuthenticatedUserContext.hasRole("ROLE_HELPDESK_INTEGRATION")
```

**2. ResourceLifecycleServiceImpl**

`ResourceLifecycleServiceImpl.isSecurityRuleMet()` gains a new security rule branch:

```java
}else if(securityRule.getAttributes().

contains("isHelpdeskIntegration")){
        return AuthenticatedUserContext.

hasRole("ROLE_HELPDESK_INTEGRATION");
}
```

Additionally, the relevant transitions in `ResourceStateMachineConfig` are updated so that transitions previously
requiring `isRepresentative` can also be executed by `isHelpdeskIntegration`.

##### Principle of Least Privilege

The `negotiator_helpdesk` scope follows the principle of least privilege and grants only the permissions required by the
middleware and nothing more.

| Operation                              |
|----------------------------------------|
| Read negotiations, resources, posts    |
| Post PUBLIC/PRIVATE messages           |
| Trigger resource lifecycle transitions |

### 5.2 Security Between the Middleware and Help Desks

#### 5.2.1 Middleware → Help Desk (Outbound API Calls)

The middleware authenticates to each help desk instance using the authentication mechanism native to that platform. The
credentials are stored per organization in the YAML config and injected from environment variables:

```yaml
credentials:
  apiToken: "${ZAMMAD_MHH_API_TOKEN}"
```

The exact credential type depends on the help desk platform. It may be an API token, a username/password pair, or an
OAuth 2.0 client. The adapter implementation for each platform is responsible for translating the configured credentials
into the correct HTTP authentication header or handshake. From the middleware core's perspective, credentials are an
opaque map of key-value pairs passed to the adapter.

Each organization's credentials are fully isolated: a compromise of one organization's credentials gives no access to
any other organization's help desk instance or to any Negotiator data. All outbound calls use TLS with certificate
validation enforced. SSL verification is never disabled outside of isolated local development environments.

#### 5.2.2 Help Desk → Middleware (Inbound Callbacks)

The middleware exposes the endpoint `POST /webhooks/helpdesk/{organizationExternalId}`. This endpoint is protected by
shared secret verification. Each organization has its own `callbackSecret` in the YAML config, injected from an
environment variable.

The exact mechanism by which the help desk platform sends this secret is adapter-specific. It may be a custom HTTP
header, an HMAC signature over the payload, or a query parameter. The adapter implementation is responsible for
verifying it. The middleware core calls `adapter.verifyCallback(headers, body, callbackSecret)` and rejects with HTTP
401 if verification fails.

## 6. Middleware Architecture

### Overview

The middleware is structured into three layers: the **inbound layer** (receiving webhooks from both Negotiator and help
desks), the **core** (all logic that is not bound to any specific help desk platform), and the **adapter layer** (all
logic that is platform-specific). The dependency direction is strictly one way. The core depends on the adapter
interface, never on concrete adapter implementations.

![Middleware Architecture](img/middlewareArchitecture.png)

---

### Inbound Layer

#### NegotiatorWebhookController

Receives all webhook deliveries from Negotiator at `POST /webhooks/negotiator`. Its only responsibilities are:

1. Delegate signature and timestamp verification to `WebhookVerificationService`
2. Check `Webhook-Id` against the seen-cache in `WebhookVerificationService`
3. Return HTTP 200 within the Negotiator timeout window
4. Pass the verified raw event to `EventRouter` for processing

It contains no business logic. It never calls Negotiator or any help desk.

#### HelpdeskCallbackController

Receives all callbacks from help desks at `POST /webhooks/helpdesk/{organizationExternalId}`. Its only responsibilities
are:

1. Look up the organization config from `OrganizationConfigRegistry` by `organizationExternalId` — HTTP 404 if not found
2. Resolve the correct `HelpDeskAdapter` implementation for that organization's `type`
3. Delegate callback authentication to `adapter.verifyCallback(headers, body, callbackSecret)` — HTTP 401 if it returns
   false
4. Pass the verified callback to `EventRouter` for processing

---

### Core

#### WebhookVerificationService

Handles all security verification for inbound Negotiator webhooks. Encapsulates:

- HMAC-SHA256 signature verification against `NEGOTIATOR_WEBHOOK_SECRET`
- Timestamp tolerance check (±5 minutes)
- `Webhook-Id` seen-cache (in-memory TTL map, 15-minute window)

This is a pure utility service with no dependency on any other core component.

#### OrganizationConfigRegistry

Loads the YAML configuration at startup and exposes two lookups:

- `findByExternalId(String organizationExternalId) → OrganizationConfig`
- `findAll() → List<OrganizationConfig>`

`OrganizationConfig` is an immutable value object carrying: `organizationExternalId`, `type`, `ticketStrategy` (
`PER_RESOURCE` or `PER_NEGOTIATION`), `baseUrl`, `credentials`, `callbackSecret`, and any platform-specific fields (e.g.
`groupId`). Credentials are resolved from environment variables at load time and never written to logs.

#### NegotiatorClient

Encapsulates all outbound REST calls to Negotiator. Handles:

- OAuth 2.0 Client Credentials token acquisition and caching
- Token refresh before expiry
- All GET and PUT calls needed by event handlers

Exposes typed methods for example:

```java
NegotiationDTO getNegotiation(String negotiationId)

List<ResourceWithStatusDTO> getResources(String negotiationId)

Set<String> getPossibleResourceEvents(String negotiationId, String resourceId)

void sendResourceEvent(String negotiationId, String resourceId, String event)

void sendBulkResourceEvent(String negotiationId, List<String> resourceIds, String event)

void createPost(String negotiationId, PostCreateDTO post)
```

`sendBulkResourceEvent` maps to the `PATCH /v3/negotiations/{id}/resources` bulk endpoint and is used exclusively by
`HelpdeskResourceTransitionHandler` for `PER_NEGOTIATION` organizations.

No caller outside `NegotiatorClient` constructs HTTP requests to Negotiator directly. During the implementation it will
be considered if the Negotiator client will be generated from OpenAPI specification and the `NegotiatorClient` would be
just a wrapper handling token acquisition and caching, and then the generated Negotiator client would be called.

#### TicketMappingRepository

Persists and queries the `ticket_mapping` table. Exposes for example:

```java
void save(TicketMapping mapping)

List<TicketMapping> findByNegotiationId(String negotiationId)

List<TicketMapping> findByTicketIdAndOrganization(String ticketId, String organizationExternalId)

Optional<TicketMapping> findByNegotiationIdAndResourceId(String negotiationId, String resourceId)
```

Note that `findByTicketIdAndOrganization` returns a `List` rather than an `Optional` because a single
`helpdesk_ticket_id` maps to multiple resources for `PER_NEGOTIATION` organizations.

#### EventRouter

The central dispatch component. Receives a verified event (from either direction) and routes it to the correct event
handler. For Negotiator-side events it resolves the handler by event type. For help-desk-side callbacks it resolve
handler based on the webhook payload.

Uses the **Strategy pattern**: each event type has a dedicated handler registered in the router. Adding support for a
new event type means adding a new handler class - the router itself does not change.

#### Event Handlers

One class per event type. Each handler receives the parsed event, the resolved `OrganizationConfig`, and the
`HelpDeskAdapter` instance and performs exactly the operations for that event:

- `NegotiationApprovedHandler` — triggered by `negotiation.state.updated` with `event: APPROVE`; fetches all resources
  from Negotiator; groups resources by `organizationExternalId`; for each group whose `organizationExternalId` matches
  an entry in the YAML config, branches on `ticketStrategy`: for `PER_NEGOTIATION` creates one ticket covering all
  resources and inserts one `ticket_mapping` row per resource all pointing to the same `helpdesk_ticket_id`; for
  `PER_RESOURCE` creates one ticket per resource, inserts one `ticket_mapping` row per resource, then calls
  `adapter.linkRelatedTickets()` for all tickets created for that org in that negotiation
- `NegotiationStateUpdatedHandler` — updates `negotiation_state` field on all mapped tickets; for `PER_NEGOTIATION` orgs
  the distinct set of `helpdesk_ticket_id` values for the negotiation is used (multiple rows share the same ticket ID,
  so deduplication is needed before calling the adapter)
- `NegotiationPostAddedHandler` — fetches post text from Negotiator; for PUBLIC posts fans out to all distinct tickets
  for the negotiation; for PRIVATE posts fans out only to distinct tickets matching the post's `organizationId` and
  `negotiationId`; for both cases it excludes the originating ticket identified via `originated_posts` if present
- `ResourceStateUpdatedHandler` — looks up the `ticket_mapping` row by `negotiationId` and `resourceId`; if the org uses
  `PER_NEGOTIATION`, checks `originated_transitions` by `(negotiationId, ticketId)`: if a matching record is found,
  updates `negotiator_resource_state` and clears `negotiator_resource_transition_event` only on the first webhook (
  increments `received_count`; deletes the record when `received_count` reaches `expected_count`); subsequent webhooks
  for the same record are discarded; if no `originated_transitions` record is found (state change was triggered from
  Negotiator directly), calls `adapter.updateResourceState()` normally
- `HelpdeskResourceTransitionHandler` — resolves `negotiationId` and `resourceId` list from `ticket_mapping` by
  `ticketId`; for `PER_RESOURCE` (single resource): validates via `NegotiatorClient.getPossibleResourceEvents()`, on
  legal transition calls `sendResourceEvent()`, on illegal transition calls `adapter.updateResourceState()` to clear the
  field and `adapter.addSystemComment()` with explanation and allowed transitions; for `PER_NEGOTIATION` (multiple
  resources): validates the event against all resources, on fully valid transition inserts an `originated_transitions`
  record then calls the bulk `PATCH /v3/negotiations/{id}/resources` endpoint, on partial or full invalidity calls
  `adapter.updateResourceState()` to clear the field and `adapter.addSystemComment()` listing which resources cannot
  accept the transition
- `HelpdeskPostHandler` — writes hash of post text and `ticketId` to `originated_posts` before calling Negotiator; calls
  `NegotiatorClient.createPost()` with visibility type always set to `PRIVATE`

Each handler has a single, testable responsibility. No handler contains routing logic. Routing is always resolved before
the handler is called. Some other handlers like NegotiationInfoUpdatedHandler, etc. will added during phase 2 and 3 (see
section 7).

---

### Adapter Layer

#### HelpDeskAdapter (interface)

The contract every adapter must implement. All parameters are typed internal models (no adapter ever receives a raw
Negotiator DTO or a raw webhook payload). The translation from Negotiator domain objects to these internal models
happens in the event handlers in the core, before the adapter is called.

```java
public interface HelpDeskAdapter {

    /**
     * Verify the authenticity of an inbound callback from the help desk platform.
     * Return false if verificaiton fails.
     */
    boolean verifyCallback(HttpHeaders headers, byte[] body, String callbackSecret);

    /**
     * Create a new ticket for a resource in a negotiation.
     * Called once per resource when negotiation reaches IN_PROGRESS. 
     * Returns ticketId.
     */
    String createTicket(CreateTicketRequest request, OrganizationConfig config);

    /**
     * Update the negotiation_state display field on an existing ticket.
     */
    void updateNegotiationState(String ticketId, String negotiationState, OrganizationConfig config);

    /**
     * Update the negotiator_resource_state display field on an existing ticket.
     * Also clears negotiator_resource_transition_event back to null.
     */
    void updateResourceState(String ticketId, String resourceState, OrganizationConfig config);

    /**
     * Add a comment to a ticket relaying a Negotiator post.
     */
    void addComment(String ticketId, String commentText, OrganizationConfig config);

    /**
     * Post a system notification comment (e.g. illegal transition rejection, post not send). 
     */
    void addSystemComment(String ticketId, String message, OrganizationConfig config);

    /**
     * Parse a raw help desk callback payload into the middleware's
     * internal HelpdeskCallbackEvent model.
     */
    HelpdeskCallbackEvent parseCallback(Map<String, Object> payload);

  /**
   * Link a set of tickets as related so representatives can navigate between them.
   * Called once after all PER_RESOURCE tickets for a negotiation+org are created.
   */
  void linkRelatedTickets(List<String> ticketIds, OrganizationConfig config);
}
```

`createTicket()` returns the platform-specific ticket ID as a `String`. This is what gets stored in
`ticket_mapping.helpdesk_ticket_id`.

#### ZammadAdapter

The first and only concrete adapter in this MVP product. Implements `HelpDeskAdapter` for
Zammad ([Ticket endpoints](https://docs.zammad.org/en/latest/api/ticket/index.html), [Article endpoints](https://docs.zammad.org/en/latest/api/ticket/articles.html)):

- `verifyCallback()` — verifies the `X-Helpdesk-Secret` header against `callbackSecret`
- `createTicket()` — calls `POST /api/v1/tickets` with `group_id`, sets initial custom field values
- `updateResourceState()` — calls `PUT /api/v1/tickets/{id}` setting `negotiator_resource_state` and
  `negotiator_resource_transition_event: null`.
- `addComment()` — calls `POST /api/v1/ticket_articles` with `internal: false`. Comments added by the middleware's bot
  account do not trigger callbacks because the Zammad trigger is configured to exclude the middleware's API user (see
  help desk setup in Section 3.3.1).
- `linkRelatedTickets()` — calls `POST /api/v1/links` for each pair of ticket IDs, using link type `normal` to mark them
  as related in Zammad.
- `parseCallback()` — deserializes the Zammad webhook payload into `HelpdeskCallbackEvent`, extracting `ticket.id`,
  `ticket.negotiator_resource_transition_event`, and `article.body`

---

### Internal Model Classes

These are the data structures passed between the core and the adapter layer. They are defined in the core. The adapter
depends on them, not the other way around.

```java
record CreateTicketRequest(
        String negotiationId,        // internal UUID
        String negotiationDisplayId, // used in ticket title and potentially as a subject in first comment
        String resourceName,
        // human-readable resource name — used in ticket title in ticket strategy PER_RESOURCE
        String negotiationState,     // "IN_PROGRESS" — initial value for negotiation_state field
        String resourceState,        // "SUBMITTED" — initial value for negotiator_resource_state field
        String humanReadable         // ticket body
)


record HelpdeskCallbackEvent(
        String ticketId,
        HelpdeskCallbackType type,  // RESOURCE_TRANSITION or POST_MESSAGE
        String resourceTransitionEvent,   // null if type = POST_MESSAGE
        String postText
)

enum HelpdeskCallbackType {
    RESOURCE_TRANSITION,
    POST_MESSAGE
}

```

---

### Package Structure

```
eu.bbmri_eric.negotiator.negotiator-link
├── inbound
│   ├── NegotiatorWebhookController
│   └── HelpdeskCallbackController
├── core
│   ├── config
│   │   ├── OrganizationConfig
│   │   └── OrganizationConfigRegistry
│   ├── verification
│   │   └── WebhookVerificationService
│   ├── client
│   │   └── NegotiatorClient
│   ├── routing
│   │   ├── EventRouter
│   │   └── TicketMappingRepository
│   ├── handler
│   │   ├── NegotiationApprovedHandler
│   │   ├── NegotiationStateUpdatedHandler
│   │   ├── NegotiationPostAddedHandler
│   │   ├── ResourceStateUpdatedHandler
│   │   ├── ...
│   │   ├── HelpdeskResourceTransitionHandler
│   │   └── HelpdeskPostHandler
│   └── model
│       ├── CreateTicketRequest
│       ├── HelpdeskCallbackEvent
│       └── HelpdeskCallbackType
└── adapter
    ├── HelpDeskAdapter
    ├── ZammadAdapter
    └── ...
```

## 7. Implementation Plan

The implementation is divided into five phases. Each phase delivers a working, testable increment of the integration.
Phase 1 establishes the full bidirectional foundation (ticket creation, resource state transitions, and message
exchange) which proves the integration concept end to end. Later phases extend coverage to additional Negotiator events,
attachments, reliability, and async processing. Phases are intentionally ordered so that each one builds on a stable,
tested previous phase without requiring rework.

Phase 1 is described in the most detail. Later phases are described at a higher level of abstraction. This is
intentional, because implementation of Phase 1 could surface details that are not captured now.

### Phase 1

**Scope:** Phase 1 delivers the complete bidirectional integration for the core representative workflows: ticket
creation when a negotiation is approved, resource state transitions from the help desk, and bidirectional message
exchange (PUBLIC and PRIVATE posts). Security as described in Section 5 is implemented in full from the start since the
middleware would not work without introduction of the new authority `ROLE_HELPDESK_INTEGRATION` and new scope. The
Zammad adapter is the reference implementation. At the end of Phase 1 a representative can manage the full resource
lifecycle and communicate with researchers entirely from Zammad without opening Negotiator.

---

#### Required Negotiator Changes

**Change 1: Add `ROLE_HELPDESK_INTEGRATION` Spring Security authority and `negotiator_helpdesk` scope**

The middleware's service account must be able to read negotiation data, post messages, and trigger resource lifecycle
transitions. Without this change Phase 1 is architecturally blocked. Five targeted changes are required:

- `CustomJWTAuthConverter` — map the `negotiator_helpdesk` OAuth 2.0 scope to the Spring Security authority
  `ROLE_HELPDESK_INTEGRATION`
- `NegotiationAccessManagerImpl.verifyReadAccessForNegotiation()` — add
  `!AuthenticatedUserContext.isHelpdeskIntegration()` as an additional condition in the existing `if` statement
  This unblocks `GET /v3/negotiations/{id}` (used by `NegotiationApprovedHandler` to fetch negotiation metadata and
  payload) and `GET /v3/negotiations/{id}/resources` (used by `NegotiationApprovedHandler` to list resources with their
  current states and `organizationExternalId` for routing).
- `NegotiationServiceImpl.isAuthorizedForNegotiation()` — add `AuthenticatedUserContext.isHelpdeskIntegration()` as an
  additional condition in the existing `return` statement. This method is a separate deprecated access check used by
  `DBAttachmentService.isAuthorizedForAttachment()` and must be updated independently to unblock
  `GET /v3/attachments/{id}` (Phase 2).
- `PostServiceImpl.checkAuthorization()` — add a branch allowing `ROLE_HELPDESK_INTEGRATION` to post messages to any
  negotiation when the negotiation state allows it
- `NegotiationController.sendEventForNegotiationResource()` — add `ROLE_HELPDESK_INTEGRATION` as an additional
  authorized caller alongside the existing `isCreator` check
- `ResourceLifecycleServiceImpl.isSecurityRuleMet()` — add an `isHelpdeskIntegration` branch; update the relevant
  transitions in `ResourceStateMachineConfig` (those currently secured with `isRepresentative`) to also accept
  `isHelpdeskIntegration`

Note: `GET /v3/negotiations/{negotiationId}/resources/{resourceId}/lifecycle` (used by
`HelpdeskResourceTransitionHandler` to validate a requested transition before applying it) is already accessible to
`ROLE_HELPDESK_INTEGRATION` without any additional change — `ResourceLifecycleServiceImpl.getPossibleEvents()` routes
through `isSecurityRuleMet()` which already has an `isRepresentativeOrHelpdeskIntegration` branch.

**Change 2: Populate `helpdeskActor` on posts created via the help desk**

When the middleware posts a message on behalf of a representative, the post must be attributed to that representative in
the Negotiator UI and API rather than to the middleware service account. Five targeted changes are required:

- `V36.0__add_helpdesk_actor.sql` (new Flyway migration) —
  `ALTER TABLE post ADD COLUMN helpdesk_actor VARCHAR(255) NULL`
- `Post.java` — add `@Column(name = "helpdesk_actor") private String helpdeskActor`
- `PostCreateDTO.java` — add `private String helpdeskActor` so the middleware can pass the value in the request body
- `PostDTO.java` — add `private String helpdeskActor` so the field is returned in API responses
- `PostServiceImpl.setUpPostEntity()` — when the caller holds `ROLE_HELPDESK_INTEGRATION` and
  `postRequest.getHelpdeskActor()` is non-null, copy the value onto the entity:
  `postEntity.setHelpdeskActor(postRequest.getHelpdeskActor())`
- `TimelineItem.vue` — update `getAuthorName()` to prefer `helpdeskActor` over `createdBy.name` when present:
  `return item.helpdeskActor || item.createdBy?.name || 'Unknown'`

**Change 3: Populate `helpdeskActor` on resource state transitions triggered via the help desk**

When the middleware triggers a resource state transition on behalf of a representative, the transition must appear in
the negotiation timeline under that representative's name rather than the middleware service account. Six targeted
changes are required:

- `V36.0__add_helpdesk_actor.sql` (same migration as Change 2) —
  `ALTER TABLE negotiation_resource_lifecycle_record ADD COLUMN helpdesk_actor VARCHAR(255) NULL`
- `NegotiationResourceLifecycleRecord.java` — add `@Column(name = "helpdesk_actor") private String helpdeskActor`;
  update `getTriggeredBy()` to return `helpdeskActor != null ? helpdeskActor : getCreatedBy().getName()`
- `NegotiationController.sendEventForNegotiationResource()` — accept an optional
  `@RequestHeader(value = "X-Helpdesk-Actor", required = false) String helpdeskActor` parameter and pass it down to the
  service layer
- `ResourceLifecycleService` / `ResourceLifecycleServiceImpl` — add `helpdeskActor` parameter to the send-event method
  and place it into the state machine message headers: `message.getHeaders().put("helpdeskActor", helpdeskActor)`
- `ResourcePersistStateChangeListener.onPersist()` — read the header value:
  `String helpdeskActor = (String) message.getMessageHeaders().get("helpdeskActor")` and pass it to
  `negotiation.setStateForResource()`
- `Negotiation.buildResourceStateChangeRecord()` — accept the nullable `helpdeskActor` string and set it on the
  `NegotiationResourceLifecycleRecord` before it is added to the cascade

**Change 4: Implement `GET /v3/posts/{postId}` for middleware post text retrieval**

The middleware needs to fetch the full text of a post when a `negotiation.post.added` webhook arrives — the webhook
payload carries only a `postId`, not the post body. This endpoint currently cannot be used by anyone: the path falls
through to the catch-all `denyAll()` rule in `HTTPRegistryConfigurer`, and `PostServiceImpl.findById()` is an
unimplemented stub returning `null`. Two targeted changes are required:

- `HTTPRegistryConfigurer` — add an explicit `authenticated()` rule for `/v3/posts/{id}` so the path is not caught by
  the catch-all `denyAll()` rule.
- `PostServiceImpl.findById()` — implement the method body to load the `Post` entity by ID and map it to `PostDTO`,
  applying the same visibility rules as the existing post-listing endpoint. A caller holding `ROLE_HELPDESK_INTEGRATION`
  must be permitted to read any post by ID without being subject to the negotiation participant check, since the
  middleware needs to relay posts to the help desk regardless of which organization the post is addressed to.

---

#### Middleware Implementation

1. **Project setup** — Spring Boot application, Maven build, Dockerfile, Docker Compose configuration placing the
   middleware as a sidecar alongside Negotiator. All secrets injected via environment variables. The application fails
   to start if any required secret environment variable is absent. The Docker Compose configuration also includes a
   Zammad instance so that end-to-end tests can be run locally against a real help desk without any external dependency.

2. **Configuration loading** — `OrganizationConfigRegistry` loading the YAML config at startup and validating all
   referenced environment variables are present at startup (fail fast).

3. **Inbound layer** — `NegotiatorWebhookController` and `HelpdeskCallbackController` as described in Section 6.
   `WebhookVerificationService` implementing HMAC-SHA256 signature verification, ±5-minute timestamp tolerance check,
   and `Webhook-Id` seen-cache (in-memory TTL, 15-minute window).

4. **Negotiator client** — `NegotiatorClient` with OAuth 2.0 Client Credentials token acquisition and caching via Spring
   Security's `OAuth2AuthorizedClientManager`. Methods required in Phase 1: `getNegotiation`, `getResources`,
   `getPossibleResourceEvents`, `sendResourceEvent`, `createPost`, `getPostText`.

5. **Persistence** — `ticket_mapping` table (`negotiation_id`, `helpdesk_ticket_id`, `resource_id`, `organization_id`,
   `organization_ext_id`), `originated_posts` table (`row_id`, `text_hash`, `organization_id`, `ticket_id`,
   `created_at`), and `originated_transitions` table (`row_id`, `negotiation_id`, `ticket_id`, `expected_count`,
   `received_count`, `new_state`, `created_at`), all managed via Spring Data JPA. Scheduled cleanup job deleting rows
   older than 24 hours from both `originated_posts` and `originated_transitions`.

6. **Event routing and handlers** — `EventRouter` with Strategy pattern dispatch. Handlers implemented in Phase 1:

- `NegotiationApprovedHandler` — triggered by `negotiation.state.updated` with `event: APPROVE`; fetches all resources
  from Negotiator; groups resources by `organizationExternalId`; for each group matching an entry in the YAML config,
  branches on `ticketStrategy`: for `PER_NEGOTIATION` creates one ticket and inserts one `ticket_mapping` row per
  resource all pointing to the same `helpdesk_ticket_id`; for `PER_RESOURCE` creates one ticket per resource, inserts
  one `ticket_mapping` row per resource, then calls `adapter.linkRelatedTickets()` with all ticket IDs created for that
  org in that negotiation
- `NegotiationPostAddedHandler` — fetches post text from Negotiator; for PUBLIC posts fans out to all distinct
  `helpdesk_ticket_id` values for the negotiation; for PRIVATE posts fans out only to distinct ticket IDs matching the
  post's `organizationId` and `negotiationId`; for both cases it excludes the originating ticket identified via
  `originated_posts` if present
- `ResourceStateUpdatedHandler` — looks up the `ticket_mapping` row by `negotiationId` and `resourceId`; checks
  `originated_transitions` by `(negotiationId, ticketId)`: if a matching record exists, processes the first webhook (
  updates `negotiator_resource_state`, clears `negotiator_resource_transition_event`) and discards the rest; if no
  record exists, calls `adapter.updateResourceState()` normally
- `HelpdeskResourceTransitionHandler` — resolves `negotiationId` and `resourceId` list from `ticket_mapping` by
  `ticketId`; for `PER_RESOURCE`: validates via `NegotiatorClient.getPossibleResourceEvents()`, on legal transition
  calls `sendResourceEvent()`, on illegal transition clears the field and posts rejection comment; for
  `PER_NEGOTIATION`: validates against all resources, on fully valid transition inserts an `originated_transitions`
  record then calls bulk `PATCH /v3/negotiations/{id}/resources`, on partial or full invalidity clears the field and
  posts a system comment listing which resources cannot accept the transition
- `HelpdeskPostHandler` — writes hash of post text and `ticketId` to `originated_posts` before calling Negotiator; calls
  `NegotiatorClient.createPost()` with visibility type always set to `PRIVATE`

7. **Zammad adapter** — `ZammadAdapter` implementing the full `HelpDeskAdapter` interface: `verifyCallback` (checks
   `X-Helpdesk-Secret` header), `createTicket` (title format: `[{negotiationDisplayId}] {resourceName} — Access Request`
   for `PER_RESOURCE`; `[{negotiationDisplayId}] Resources Access Request` for `PER_NEGOTIATION`),
   `updateResourceState`, `addComment`, `addSystemComment`, `linkRelatedTickets` (calls `POST /api/v1/links` for each
   pair of ticket IDs with link type `normal`), `parseCallback`.

8. **Help desk setup documentation** — step-by-step instructions for the Zammad admin covering: dedicated bot user
   account for the middleware, group creation for tickets, three custom fields (`negotiation_state`,
   `negotiator_resource_state`, `negotiator_resource_transition_event`), Core Workflow for conditional field display and
   read-only enforcement on `negotiation_state` and `negotiator_resource_state`, two triggers (new external article
   excluding middleware bot account, `negotiator_resource_transition_event` changed to non-null value). Consider
   creating script which would do all of these configuration by calling Zammad API. It would make the testing easier.

---

#### Acceptance Criteria

- A negotiation approved by an admin in Negotiator results in the correct ticket structure for each opted-in
  organization based on its `ticketStrategy`:
  - `PER_RESOURCE`: one Zammad ticket per resource with correct initial field values (
    `negotiation_state: IN_PROGRESS`, `negotiator_resource_state: SUBMITTED`, title `[NEGO-1234] {resourceName}`). All
    tickets for the same negotiation and organization are linked as related in Zammad.
  - `PER_NEGOTIATION`: one Zammad ticket covering all resources with correct initial field values (
    `negotiation_state: IN_PROGRESS`, `negotiator_resource_state: SUBMITTED`, title `[NEGO-1234] All resources`).
- A representative at a `PER_RESOURCE` organization selecting a valid event in `negotiator_resource_transition_event`
  triggers the correct single-resource state change in Negotiator. The `negotiator_resource_transition_event` field is
  cleared to null and `negotiator_resource_state` is updated to the new state.
- A representative at a `PER_NEGOTIATION` organization selecting a valid event in `negotiator_resource_transition_event`
  triggers a bulk state change for all resources in the ticket. The `negotiator_resource_transition_event` field is
  cleared to null and `negotiator_resource_state` is updated exactly once regardless of how many
  `negotiation.resource.state.updated` webhooks Negotiator dispatches.
- A representative selecting an invalid transition event receives a system comment explaining why the transition was
  rejected and listing the currently allowed transitions. For `PER_NEGOTIATION` the comment lists which resources cannot
  accept the transition. The `negotiator_resource_transition_event` field is cleared to null. No change is made in
  Negotiator.
- A PUBLIC post created in Negotiator appears as a comment in all distinct Zammad tickets for that negotiation. A
  PRIVATE post appears only in tickets belonging to the addressed organization.
- A message sent from a Zammad ticket appears as a PRIVATE post in the Negotiator negotiation. The message does not
  appear as a duplicate comment in the originating ticket.
- Webhook deliveries from Negotiator with an invalid HMAC signature are rejected with HTTP 401 and not processed.
- Webhook deliveries from Negotiator with a timestamp outside the ±5-minute window are rejected with HTTP 401 and not
  processed.
- Duplicate webhook deliveries (same `Webhook-Id`) are acknowledged with HTTP 200 and not reprocessed.
- Help desk callbacks with an invalid or missing shared secret are rejected with HTTP 401 and not processed.
- The application refuses to start if any required secret environment variable is absent.

### Phase 2

**Scope:** Phase 2 extends the integration to cover three additional Negotiator events that were intentionally deferred
from Phase 1: negotiation state changes, access form payload updates, and attachments. By the end of Phase 2 the help
desk ticket stays fully in sync with the negotiation throughout its lifecycle, not only during the initial resource
workflow, but also when the negotiation is paused, concluded, or abandoned, when the researcher updates the access form,
and when files are exchanged in either direction.

**Negotiation state changes (`negotiation.state.updated` for states other than `IN_PROGRESS`):** When a negotiation
moves to `PAUSED`, `CONCLUDED`, or `ABANDONED`, all tickets for that negotiation must be updated. This requires a new
`NegotiationStateUpdatedHandler` and a new `updateNegotiationState()` method on the `HelpDeskAdapter` interface. The
Zammad adapter maps Negotiator states to the closest Zammad ticket state (e.g. `CONCLUDED` → closed as resolved,
`ABANDONED` → closed as cancelled).

**Access form payload updates:** When the researcher updates the negotiation access form, the help desk ticket
description should be refreshed to reflect the new content, and any attachments uploaded during the edit must also be
forwarded to the help desk. This requires a new webhook event to be introduced in Negotiator — currently no such event
exists. The existing `negotiation.info.updated` event (`WebhookEventType.NEGOTIATION_INFO_UPDATED`) is **not** suitable
for this purpose: it is fired by `InformationSubmissionServiceImpl` when a representative submits an
`InformationSubmission` (the Information Requirements feature), not when the researcher edits the negotiation payload.
`NegotiationServiceImpl.updateNegotiation()` updates `negotiation.payload` at line 195 but publishes no application
event and dispatches no webhook. A new `NegotiationPayloadUpdatedEvent` Spring application event must therefore be
introduced in Negotiator, published from `NegotiationServiceImpl` after the payload is saved, and wired into the webhook
dispatch system following the same pattern as existing events — a new `WebhookEventType` constant (e.g.
`NEGOTIATION_PAYLOAD_UPDATED`), a new webhook payload DTO carrying `negotiationId`, and a new `WebhookMappingStrategy`
registration. On the middleware side a new `NegotiationPayloadUpdatedHandler` fetches the updated negotiation from
Negotiator via a secondary REST call, posts the refreshed content as a new comment to all mapped tickets, and forwards
any standalone attachments that have not yet been forwarded (see attachment deduplication below).

**Attachments:** Attachments are currently not linked to posts anywhere in the data model — both `Post` and `Attachment`
independently reference `negotiation_id` and `organization_id`, but there is no `post_id` FK on `Attachment` and no
`attachments` collection on `Post`. To support transferring attachments to the help desk as part of the post flow,
attachments must be optionally linked to posts. The link is optional because attachments can also be uploaded
independently — during negotiation creation or via the negotiation payload edit — and those must continue to work
without a post reference.

The following Negotiator changes are required:

- **Migration** — `ALTER TABLE attachment ADD COLUMN post_id VARCHAR(255) NULL` with a FK to `post(id)`.
- **`Attachment` entity** — add `@ManyToOne @JoinColumn(name = "post_id") private Post post`.
- **`Post` entity** — add `@OneToMany(mappedBy = "post") private List<Attachment> attachments`.
- **`PostCreateDTO`** — add `private List<String> attachmentIds` so the caller can reference previously uploaded
  attachments.
- **`PostServiceImpl.create()`** — after saving the post entity, fetch each attachment by ID, verify it belongs to the
  same negotiation and is not already linked to another post, set `attachment.setPost(postEntity)`, and save.
- **`PostDTO`** — add `private List<AttachmentMetadataDTO> attachments` so attachment metadata is returned alongside the
  post.
- **`NegotiationPostAddedWebhookEvent` and `NewPostEvent`** — add `List<String> attachmentIds` so the webhook payload
  carries the IDs of all attachments linked to the post.

The frontend `handleSendMessage()` in `NegotiationPosts.vue` currently makes two independent calls — one to create the
post and one to upload the attachment — with no link between them. It must be updated to upload the attachment first via
`POST /v3/negotiations/{negotiationId}/attachments`, collect the returned attachment ID, and then include it in the
`PostCreateDTO` body when calling `POST /v3/negotiations/{negotiationId}/posts`. The user experience is unchanged —
one "Send message" button — only the internal call sequence changes.

On the middleware side, `NegotiationPostAddedHandler` is extended to read `attachmentIds` from the webhook payload and,
for each ID, fetch the binary content from `GET /attachments/{id}` before calling the adapter. A new `addComment()`
overload (or an extended `addComment()` signature) on `HelpDeskAdapter` accepts an optional list of attachment binaries
alongside the comment text, so the adapter can attach the files to the help desk ticket article in the same API call.
Post-linked attachments are deduplicated automatically through the existing `originated_posts` mechanism — if the
originating post is excluded from fan-out, its attachments are excluded with it.

For the reverse direction (help desk → Negotiator), `HelpdeskPostHandler` is extended to detect attachments in the
callback payload. If attachments are present, each file is uploaded to Negotiator first via
`POST /v3/negotiations/{negotiationId}/attachments`, and the returned attachment IDs are included in
`PostCreateDTO.attachmentIds` when creating the post. This keeps the post and its attachments linked from the moment
they are created in Negotiator.

**Attachment deduplication for standalone attachments:** Post-linked attachments are deduplicated automatically through
the `originated_posts` mechanism. Standalone attachments — those uploaded during negotiation creation or access form
payload edits — have no post reference and are handled separately. These attachments reach the middleware via
`NegotiationApprovedHandler` (negotiation creation attachments are included in the initial ticket body) and
`NegotiationPayloadUpdatedHandler` (attachments uploaded during payload edit). To prevent re-forwarding the same file on
every subsequent payload update, the middleware maintains a `forwarded_attachments` table:

| Column           | Type      | Constraints  |
|------------------|-----------|--------------|
| `negotiation_id` | VARCHAR   | composite PK |
| `attachment_id`  | VARCHAR   | composite PK |
| `forwarded_at`   | TIMESTAMP | NOT NULL     |

When a standalone attachment is forwarded to a help desk ticket, a row is inserted. When
`NegotiationPayloadUpdatedHandler` fires, it fetches all attachment IDs for the negotiation from Negotiator via
`GET /v3/negotiations/{negotiationId}/attachments`, subtracts the IDs already present in `forwarded_attachments` for the
relevant ticket, and forwards only the delta. Attachments linked to posts (`post_id IS NOT NULL`) are excluded from this
query — they are managed entirely through the post flow and never enter `forwarded_attachments`.

All changes in Phase 2 follow the established patterns from Phase 1. No architectural changes are required.

### Phase 3

**Scope:** Phase 3 handles the case where a negotiation is transferred to a different researcher (author change). This
is the only remaining Negotiator-level event that affects the help desk ticket but is not yet covered.

Negotiation transfer is performed via `PATCH /v3/negotiations/{id}` with `authorSubjectId` in `NegotiationUpdateDTO` (
`NegotiationServiceImpl.java:201-208`). The transfer simply updates `negotiation.createdBy` and saves. No
`ApplicationEvent` is published and no webhook event type for transfer currently exists.

Phase 3 therefore requires a Negotiator change before middleware work can begin: a new `NegotiationTransferredEvent`
Spring application event published from `NegotiationServiceImpl` at the point where
`negotiationEntity.setCreatedBy(person)` is called, a new `NEGOTIATION_TRANSFERRED` webhook event type constant, a new
payload DTO carrying `negotiationId` and the new author's identifier, and a new `WebhookMappingStrategy` registration
following the exact same pattern as all existing webhook events.

On the middleware side a new `NegotiationTransferredHandler` receives the event and updates all tickets for that
negotiation to reflect the new requester identity. The exact representation in the help desk ticket (e.g. updating the
ticket description or adding a system comment noting the transfer) is an adapter-level decision implemented in adapter
it self. In the `ZammadAdapter`, it will be done with system coment.

### Phase 4

**Scope:** Phase 4 addresses reliability and observability of the middleware.

**Reliability — handling Negotiator downtime:** In the current synchronous design, if Negotiator is unavailable when a
help desk callback arrives, the middleware's outbound REST call fails and the event is lost with no recovery path. Phase
4 introduces retry logic with exponential backoff for all outbound calls to both Negotiator and help desk platforms.
Transient failures (HTTP 5xx, connection timeout) are retried up to a configurable number of attempts. Permanent
failures (HTTP 4xx, unknown mapping) are not retried. Spring Retry (`spring-retry`) is the natural choice given the
existing Spring Boot stack. Events that exhaust all retry attempts are written to a dead-letter store with structural
metadata only (`negotiationId`, event type, timestamp, error message, retry count) so an operator can inspect and replay
them manually.

**Reliability — catch-up reconciliation on middleware restart:** When the middleware restarts after downtime, Negotiator
webhook deliveries that arrived during the outage are permanently lost (Negotiator has no automatic retry, findings
document Q10). To recover missed negotiation-level changes, the middleware can run a catch-up pass on startup: it
queries `GET /v3/negotiations` filtered by `modifiedAfter` (the last-processed timestamp stored in the database) and
`organizationExternalId` (the configured orgs), then for each negotiation already in `ticket_mapping` pushes the current
state to the corresponding help desk tickets unconditionally. Missed posts cannot be recovered via this mechanism in the
current implementation. For posts, recovery requires adding two query parameters to `GET /v3/negotiations/{id}/posts` in
Negotiator: a `createdAfter` filter (so only posts created after the last-processed timestamp are returned) and a
multi-value `negotiationId` filter (so the catch-up pass can batch-query posts for all negotiations already present in
`ticket_mapping` without calling the endpoint once per negotiation).

**Observability:** Negotiator uses Spring Boot Actuator with Micrometer and `micrometer-registry-prometheus` (
`pom.xml:257-259`). The middleware will use the same stack for consistency. The collected metrics will be scraped by
Prometheus and visualised in Grafana dashboards, giving operators visibility into webhook delivery rates, processing
latency, and error counts across both directions of the integration.

### Phase 5

**Scope:** Phase 5 replaces synchronous webhook processing with an asynchronous queue-based model, addressing the
Negotiator delivery timeout constraint and enabling horizontal scaling.

In the current synchronous design all processing (secondary REST calls to Negotiator, help desk API calls) happens
within the HTTP request thread that received the webhook. Negotiator's read timeout for webhook delivery is 8 seconds (
`WebhookTimeoutProperties.java`). Under load or when Negotiator or help desk APIs are slow, the middleware risks missing
this window, causing Negotiator to record the delivery as failed.

Phase 5 introduces an internal queue for both inbound directions. When `NegotiatorWebhookController` or
`HelpdeskCallbackController` receives a verified request, it enqueues the event and returns HTTP 200 immediately. A
separate worker pool dequeues events and processes them asynchronously, with no time pressure from the inbound HTTP
timeout.

Before Phase 5 implementation begins, the choice of queue infrastructure technology (e.g. database-backed queue,
RabbitMQ, Redis Streams) will be discussed and agreed with the developers of the Negotiator application so it meet their
expectations.

## 8. Out of Scope

### 8.1 Information Requirements and Submissions

Negotiator supports a feature called **Information Requirements**: a Negotiator administrator can configure that a
representative must fill out an additional access form before a specific resource state transition is permitted. This is
modelled by the `InformationRequirement` entity, which links a `NegotiationResourceEvent` (the transition that triggers
the requirement) to an `AccessForm` that must be completed. The representative's response is stored as an
`InformationSubmission`, which carries a JSON payload and is scoped to a specific `Resource`, `Negotiation`, and
`InformationRequirement`.

When a representative attempts a state transition for which an unsatisfied `InformationRequirement` exists, Negotiator
blocks the transition until the corresponding `InformationSubmission` has been provided. The form that must be filled is
dynamic and deployment-specific, determined by the `AccessForm` configuration.

As discussed, this feature is out of scope for the help desk integration currently.

### 8.2 Admin Adding Resources to an In-Progress Negotiation

A Negotiator administrator can add new resources to a negotiation regardless of its current state, including when the
negotiation is already `IN_PROGRESS`. When a new resource is added to an in-progress negotiation, the help desk
integration will not automatically create a ticket for that resource in the relevant organization's help desk.

Supporting this case would require a new webhook event in Negotiator fired when a resource is added to an existing
negotiation, as well as additional routing logic in the middleware to distinguish a late-added resource from the initial
batch created at approval time. This complexity is deferred. Organizations that need to handle late-added resources must
manage them through the Negotiator UI directly.

Note: there is a bug in updateResourcesInANegotiation method. The NewResourcesAddedEvent is published even when no new
resources are added.