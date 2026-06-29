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

The integration enables representatives to perform exactly these three actions within their help desk system.

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

#### Decision 4: One Ticket per Organization vs. One Ticket per Resource

Two options were considered for how to map a Negotiator negotiation to help desk tickets when an organization owns
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

A separate ticket is created for each resource, even when multiple resources belong to the same organization.

Advantages:

- A representative can independently accept or refuse individual resources from the help desk. Each ticket maps to a
  single resource state machine.

Limitations:

- Private posts are org-scoped in Negotiator. When a private post occurs, the middleware must fan it out to all resource
  tickets of that organization in that negotiation.
- Negotiation-level state changes (e.g. negotiation info changed, file attached) must be propagated to all resource
  tickets belonging to that negotiation. This is a straightforward fan-out in the middleware, but it means more ticket
  update calls per negotiation-level event.

**Decision:** One ticket per resource (Option B).

**Justification:**

- The core value of the integration is allowing representatives to independently manage access decisions for individual
  resources from within their existing help desk workflow. Forcing representatives into bulk accept-or-refuse decisions
  across all resources in a negotiation (Option A) would significantly limit this value since an organization may own
  resources from different collections with different availability, and representatives need to act on them
  independently.
- It is more reasonable to accept the limitation that bulk state changes from the help desk are not supported than to
  impose a model that prevents granular decisions entirely. Representatives who need to update multiple resources at
  once can still use the Negotiator UI's org-level bulk dropdown.
- Applying negotiation-level changes to all related tickets is not a significant burden for the middleware. It is a
  straightforward fan-out over a known set of ticket IDs stored at creation time.

#### Decision 5: Distinguishing Private vs. Public Posts from the Help Desk

**Decision:** Add a custom field to the help desk ticket (
e.g. [Zammad Objects](https://admin-docs.zammad.org/en/latest/system/objects.html), [ServiceNow Custom Fields doc](https://docs.getint.io/guides/integration-synchronization/jira-servicenow-integration/creating-a-custom-field-in-servicenow))
with a default value of `PRIVATE` (visible only to the sending organization and the requester and admin) and an
alternative value of `PUBLIC` (visible to all parties). The type of the post forwarded to Negotiator will be determined
by the selected value.

**Justification:**

- One alternative would be to use the help desk's native internal/external article distinction (supported by platforms
  such as [Zammad](https://docs.zammad.org/en/latest/api/ticket/articles.html) and ServiceNow). However, this is
  potentially confusing because internal messages in tickets are conventionally used only for intra-organization
  communication, which could lead to unintentional information leakage.
- Another alternative would be to support only public posts from the help desk. This would eliminate the ability for a
  representative to communicate with the researcher about matters that should not be visible to other participating
  organizations.
- A custom dropdown field with values `PRIVATE` and `PUBLIC` is therefore the most explicit and least ambiguous
  approach. When the representative sends a message from the help desk, the middleware reads this field and posts to
  Negotiator with the corresponding visibility type.

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

The reverse direction allows representatives to change a resource's state and post public or private messages directly
from within the help desk.

#### 3.3.1 Help Desk Configuration

Before the integration can function, a help desk administrator must perform a one-time configuration. Help desk
platforms such as Zammad and ServiceNow support defining custom fields on tickets (
see [Zammad Objects](https://admin-docs.zammad.org/en/latest/system/objects.html), [ServiceNow Custom Fields](https://docs.getint.io/guides/integration-synchronization/jira-servicenow-integration/creating-a-custom-field-in-servicenow))
as well as conditional field display and editability rules (
see [Zammad Core Workflows](https://admin-docs.zammad.org/en/6.4/system/core-workflows.html), [ServiceNow UI Policies](https://www.servicenow.com/docs/r/yokohama/platform-administration/ai-platform-administration/t_CreateAUIPolicy.html)).

The following custom fields must be created in the help desk:

| Field                                  | Type                      | Editable by user | Purpose                                                  |
|----------------------------------------|---------------------------|------------------|----------------------------------------------------------|
| `negotiation_state`                    | select                    | No               | Displays the current negotiation state                   |
| `negotiator_resource_state`            | select                    | No               | Displays the current resource state                      |
| `negotiator_resource_transition_event` | select, nullable          | Yes              | Representative requests a resource state transition      |
| `negotiator_post_type`                 | select, default `PRIVATE` | Yes              | Representative sets the visibility of their next comment |

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

The middleware first validates the requested transition by calling
`GET /v3/negotiations/{negotiationId}/resources/{resourceId}/lifecycle` on Negotiator to retrieve the set of currently
allowed events.

**If the transition is valid:**

- The middleware calls Negotiator to apply the transition.
- Negotiator persists the change and asynchronously dispatches a `negotiation.resource.state.updated` webhook back to
  the middleware.
- The middleware processes this webhook normally: it updates the `negotiator_resource_state` field on the ticket and
  clears `negotiator_resource_transition_event` back to `null`.

**If the transition is invalid:**

- The middleware clears `negotiator_resource_transition_event` back to `null` on the ticket.
- The middleware posts a system comment to the ticket explaining why the transition was rejected and listing the
  transitions that are currently allowed. A transition is invalid either because the negotiation is not in `IN_PROGRESS`
  state or because the selected event is not permitted from the resource's current state.

The middleware includes the representative's identity from the help desk as the `X-Helpdesk-Actor` request header on the
Negotiator call, so the transition is recorded in the negotiation timeline under the representative's name rather than
the middleware service account (see Decision 7).

### 3.5 Post Message Flow

When a representative sends an external message from the help desk, the middleware receives it via webhook callback.
Before forwarding the message to Negotiator, the middleware checks whether the negotiation is in a state that allows
posts (IN_PROGRESS, PAUSED). If the negotiation state does not permit posting, the middleware does not forward the
message and instead adds a system comment to the ticket explaining the reason.

If the state permits posting, the middleware reads the `negotiator_post_type` field from the callback payload and calls
`POST /v3/negotiations/{negotiationId}/posts` on Negotiator with the appropriate visibility type (`PUBLIC` or
`PRIVATE`). The representative's identity from the help desk is passed as the `X-Helpdesk-Actor` request header so the
post is attributed to the representative rather than the middleware service account (see Decision 6).

**OPEN QUESTION:** Does we want to allow post messages also for CONCLUDED negotiation state? Currently negotiator
disable posts when state changed IN_PROGRESS → ABANDONED, however not for PAUSED → ABANDONED which is probably the
implementation bug

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
| `resource_id`         | VARCHAR | composite PK |
| `organization_id`     | VARCHAR | NOT NULL     |
| `organization_ext_id` | VARCHAR | NOT NULL     |
| `helpdesk_ticket_id`  | VARCHAR | NOT NULL     |

#### Organization Configuration

The middleware also holds a YAML configuration file that defines which organizations have help desk integration enabled.
Each entry carries the connection details for that organization's help desk instance. Credentials are never hardcoded —
they are injected from environment variables at startup. An example configuration (probably will be extended during
implementation, when some of the requeired fields will be identified):

```yaml
organizations:
  - organizationExternalId: "bbmri:DE:MHH"
    type: "zammad"
    baseUrl: "https://zammad.mhh.de"
    credentials:
      apiToken: "${ZAMMAD_MHH_API_TOKEN}"
    callbackSecret: "${ZAMMAD_MHH_CALLBACK_SECRET}"
    groupId: 3 # this in Zammad identify the ticket type to which the custom fields are linked

  - organizationExternalId: "bbmri:NL:AMC"
    type: "zammad"
    baseUrl: "https://zammad.amc.nl"
    credentials:
      apiToken: "${ZAMMAD_AMC_API_TOKEN}"
    callbackSecret: "${ZAMMAD_AMC_CALLBACK_SECRET}"
    groupId: 5

  - organizationExternalId: "bbmri:UK:OXFORD"
    type: "serviceNow"
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
and their associated `organizationExternalId`. For each resource the following routing logic is applied:

```
for each resource in resources:
    look up organizations[] config by organizationExternalId
         │
         ├── not found → org not opted in → log and skip silently
         │
         └── found → use help desk type, baseUrl, credentials, etc.
                  │
                  ▼
             create ticket in that org's help desk
                  │
                  ▼
             INSERT INTO ticket_mapping:
                 negotiation_id      = negotiation.negotiationId
                 organization_id     = resource.organizationId
                 organization_ext_id = organizationExternalId
                 helpdesk_ticket_id  = ticketId returned by help desk
                 resource_id         = resource.resourceId
```

For all subsequent events (e.g. negotiation state change, new post, resource state change), the middleware looks up all
rows in `ticket_mapping` by `negotiationId`. For each returned row it retrieves the organization's connection details
from the YAML config and calls the appropriate adapter method, which performs the necessary API call to the target help
desk.

### 4.3 Help Desk → Middleware → Negotiator

The middleware exposes an inbound endpoint `POST /webhooks/helpdesk/{organizationExternalId}` that receives all
callbacks from help desks. The callback payload always contains the `ticketId` of the ticket that triggered the
callback. The middleware looks up this `ticketId` in combination with `organizationExternalId` (the `ticketId` is not
sufficient for that since it does not need to be unique across help desks) in `ticket_mapping` to resolve the
`negotiationId` and `resourceId` it corresponds to. It then calls the appropriate Negotiator endpoint to apply the
change.

This Negotiator call causes a new webhook event to be dispatched back to the middleware (e.g. a resource state change
triggers a `negotiation.resource.state.updated` event). The middleware processes this webhook normally, updates the
relevant fields in the help desk ticket following the same Negotiator → Middleware → Help Desk flow described in Section
4.2.

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

- Post **PUBLIC** messages to any negotiation.
- Post **PRIVATE** messages to negotiations in the `IN_PROGRESS` state.

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

`OrganizationConfig` is an immutable value object carrying: `organizationExternalId`, `type`, `baseUrl`, `credentials`,
`callbackSecret`, and any platform-specific fields (e.g. `groupId`). Credentials are resolved from environment variables
at load time and never written to logs.

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

void createPost(String negotiationId, PostCreateDTO post)
```

No caller outside `NegotiatorClient` constructs HTTP requests to Negotiator directly. During the implementation it will
be considered if the Negotiator client will be generated from OpenAPI specification and the `NegotiatorClient` would be
just a wrapper handling token acquisition and caching, and then the generated Negotiator client would be called.

#### TicketMappingRepository

Persists and queries the `ticket_mapping` table. Exposes for example:

```java
void save(TicketMapping mapping)

List<TicketMapping> findByNegotiationId(String negotiationId)

Optional<TicketMapping> findByTicketIdAndOrganization(String ticketId, String organizationExternalId)
```

#### EventRouter

The central dispatch component. Receives a verified event (from either direction) and routes it to the correct event
handler. For Negotiator-side events it resolves the handler by event type. For help-desk-side callbacks it resolve
handler based on the webhook payload.

Uses the **Strategy pattern**: each event type has a dedicated handler registered in the router. Adding support for a
new event type means adding a new handler class - the router itself does not change.

#### Event Handlers

One class per event type. Each handler receives the parsed event, the resolved `OrganizationConfig`, and the
`HelpDeskAdapter` instance and performs exactly the operations for that event:

- `NegotiationApprovedHandler` — creates one ticket per resource in the negotiation
- `NegotiationStateUpdatedHandler` — updates `negotiation_state` field on all mapped tickets
- `NegotiationPostAddedHandler` — fetches post text from Negotiator, fans out comment to mapped tickets (all tickets for
  PUBLIC, org-scoped tickets for PRIVATE). Potentially exclude some ticket from processing if the ticketId was returned
  from table `originated_posts`
- `ResourceStateUpdatedHandler` — updates `negotiator_resource_state` on the single matching ticket
- `HelpdeskResourceTransitionHandler` — validates the event via `NegotiatorClient.getPossibleResourceEvents()`, fires
  transition or posts rejection comment
- `HelpdeskPostHandler` — reads `negotiator_post_type` from callback, posts to Negotiator with correct type

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
- `parseCallback()` — deserializes the Zammad webhook payload into `HelpdeskCallbackEvent`, extracting `ticket.id`,
  `ticket.negotiator_resource_transition_event`, `ticket.negotiator_post_type`, and `article.body`

---

### Internal Model Classes

These are the data structures passed between the core and the adapter layer. They are defined in the core. The adapter
depends on them, not the other way around.

```java
record CreateTicketRequest(
        String negotiationId,        // internal UUID
        String negotiationDisplayId, // used in ticket title and potentially as a subject in first comment
        String resourceName,         // human-readable resource name — used in ticket title
        String negotiationState,     // "IN_PROGRESS" — initial value for negotiation_state field
        String resourceState,        // "SUBMITTED" — initial value for negotiator_resource_state field
        String humanReadable         // ticket body
)


record HelpdeskCallbackEvent(
        String ticketId,
        HelpdeskCallbackType type,  // RESOURCE_TRANSITION or POST_MESSAGE
        String resourceTransitionEvent,   // null if type = POST_MESSAGE
        String postText,
        String postType             // PUBLIC or PRIVATE, null if type = RESOURCE_TRANSITION
)

enum HelpdeskCallbackType {
    RESOURCE_TRANSITION,
    POST_MESSAGE
}

```

---

### Package Structure

```
eu.bbmri_eric.negotiator.helpdesk.middleware
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

The middleware's service account must be able to post messages and trigger resource lifecycle transitions. Without this
change Phase 1 is architecturally blocked. Four targeted changes are required:

- `CustomJWTAuthConverter` — map the `negotiator_helpdesk` OAuth 2.0 scope to the Spring Security authority
  `ROLE_HELPDESK_INTEGRATION`
- `PostServiceImpl.checkAuthorization()` — add a branch allowing `ROLE_HELPDESK_INTEGRATION` to post PUBLIC messages to
  any negotiation and PRIVATE messages to negotiations in `IN_PROGRESS` state
- `NegotiationController.sendEventForNegotiationResource()` — add `ROLE_HELPDESK_INTEGRATION` as an additional
  authorized caller alongside the existing `isCreator` check
- `ResourceLifecycleServiceImpl.isSecurityRuleMet()` — add an `isHelpdeskIntegration` branch; update the relevant
  transitions in `ResourceStateMachineConfig` (those currently secured with `isRepresentative`) to also accept
  `isHelpdeskIntegration`

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

5. **Persistence** — `ticket_mapping` table (`negotiation_id`, `organization_id`, `organization_ext_id`,
   `helpdesk_ticket_id`, `resource_id`) and `originated_posts` table (`row_id`, `text_hash`, `organization_id`,
   `ticket_id`, `created_at`), both managed via Spring Data JPA. Scheduled cleanup job deleting `originated_posts` rows
   older than 24 hours.

6. **Event routing and handlers** — `EventRouter` with Strategy pattern dispatch. Handlers implemented in Phase 1:
    - `NegotiationApprovedHandler` — triggered by `negotiation.state.updated` with `event: APPROVE`; fetches all
      resources from Negotiator; fetches `organizationExternalId` for resource; for each resource whose
      `organizationExternalId` matches an entry in the YAML config, creates one help desk ticket and inserts a row into
      `ticket_mapping`
    - `NegotiationPostAddedHandler` — fetches post text from Negotiator; for PUBLIC posts fans out to all tickets for
      the negotiation; for PRIVATE posts fans out only to tickets matching the post's `organizationId` and
      `negotiationId`; for both cases it excludes the originating ticket identified via `originated_posts` if present
    - `ResourceStateUpdatedHandler` — looks up the matching `ticket_mapping` row by `negotiationId` and `resourceId`;
      calls `adapter.updateResourceState()` on the matching ticket
    - `HelpdeskResourceTransitionHandler` — calls `NegotiatorClient.getPossibleResourceEvents()` to validate; on legal
      transition calls `sendResourceEvent()`; on illegal transition calls `adapter.updateResourceState()` to clear the
      field and `adapter.addSystemComment()` with explanation and allowed transitions
    - `HelpdeskPostHandler` — writes hash of post text and `ticketId` to `originated_posts` before calling Negotiator;
      calls `NegotiatorClient.createPost()` with type from `negotiator_post_type` field

7. **Zammad adapter** — `ZammadAdapter` implementing the full `HelpDeskAdapter` interface: `verifyCallback` (checks
   `X-Helpdesk-Secret` header), `createTicket` (title format: `[{negotiationDisplayId}] {resourceName}`),
   `updateResourceState`, `addComment`, `addSystemComment`, `parseCallback`.

8. **Help desk setup documentation** — step-by-step instructions for the Zammad admin covering: dedicated bot user
   account for the middleware, group creation for tickets, four custom fields (`negotiation_state`,
   `negotiator_resource_state`, `negotiator_resource_transition_event`, `negotiator_post_type`), Core Workflow for
   conditional field display and read-only enforcement on `negotiation_state` and `negotiator_resource_state`, two
   triggers (new article excluding middleware bot account, `negotiator_resource_transition_event` changed to non-null
   value). Consider creating script which would do all of these configuration by calling Zammad API. It would make the
   testing easier.

---

#### Acceptance Criteria

- A negotiation approved by an admin in Negotiator results in one Zammad ticket created per resource for each opted-in
  organization, with correct initial field values (`negotiation_state: IN_PROGRESS`,
  `negotiator_resource_state: SUBMITTED`, ticket title `[NEGO-1234] {resourceName}`).
- A representative selecting a valid event in `negotiator_resource_transition_event` and saving the ticket triggers the
  correct state change in Negotiator. The `negotiator_resource_transition_event` field is cleared to null and
  `negotiator_resource_state` is updated to the new state.
- A representative selecting an invalid transition event receives a system comment explaining why the transition was
  rejected and listing the currently allowed transitions. The `negotiator_resource_transition_event` field is cleared to
  null. No change is made in Negotiator.
- A PUBLIC post created in Negotiator appears as a comment in all Zammad tickets for that negotiation. A PRIVATE post
  appears only in tickets belonging to the addressed organization.
- A message sent from a Zammad ticket appears as a post of the correct type (PUBLIC or PRIVATE based on
  `negotiator_post_type`) in the Negotiator negotiation. The message does not appear as a duplicate comment in the
  originating ticket.
- Webhook deliveries from Negotiator with an invalid HMAC signature are rejected with HTTP 401 and not processed.
- Webhook deliveries from Negotiator with a timestamp outside the ±5-minute window are rejected with HTTP 401 and not
  processed.
- Duplicate webhook deliveries (same `Webhook-Id`) are acknowledged with HTTP 200 and not reprocessed.
- Help desk callbacks with an invalid or missing shared secret are rejected with HTTP 401 and not processed.
- The application refuses to start if any required secret environment variable is absent.

### Phase 2

**Scope:** Phase 2 extends the integration to cover three additional Negotiator events that were intentionally deferred
from Phase 1: negotiation state changes, negotiation info updates, and attachments. By the end of Phase 2 the help desk
ticket stays fully in sync with the negotiation throughout its lifecycle, not only during the initial resource workflow,
but also when the negotiation is paused, concluded, or abandoned, when the researcher updates the access form, and when
files are exchanged in either direction.

**Negotiation state changes (`negotiation.state.updated` for states other than `IN_PROGRESS`):** When a negotiation
moves to `PAUSED`, `CONCLUDED`, or `ABANDONED`, all tickets for that negotiation must be updated. This requires a new
`NegotiationStateUpdatedHandler` and a new `updateNegotiationState()` method on the `HelpDeskAdapter` interface. The
Zammad adapter maps Negotiator states to the closest Zammad ticket state (e.g. `CONCLUDED` → closed as resolved,
`ABANDONED` → closed as cancelled).

**Negotiation info updates (`negotiation.info.updated`):** When the researcher updates the access form, the ticket
description should be refreshed. The middleware fetches the updated `NegotiationDTO.humanReadable` via a secondary REST
call and send it as a new comment to the ticket. This requires a new `NegotiationInfoUpdatedHandler`.

**Attachments (`negotiation.attachment.added`):** Attachments are currently not covered by any Negotiator webhook. A new
`AttachmentAddedEvent` Spring application event must be introduced in Negotiator and wired into the webhook dispatch
system following the same pattern as the existing `NewPostEvent` — a new `WebhookEventType` constant, a new webhook
payload DTO carrying `negotiationId`, `attachmentId`, `organizationId` (nullable for public), `name`, and `contentType`,
and a new `WebhookMappingStrategy` registration. On the middleware side a new `NegotiationAttachmentAddedHandler`
fetches the binary content from `GET /v3/attachments/{id}` and calls a new `addAttachment()` method on
`HelpDeskAdapter`. For attachments uploaded from the help desk, `HelpdeskPostHandler` is extended to detect attachments
in the callback payload and upload them to Negotiator via `POST /v3/negotiations/{negotiationId}/attachments` as a
separate call after the post is created. The `originated_posts` deduplication mechanism is extended with an
`originated_attachments` table using filename and file size as the deduplication key, following the same hash-based
pattern as `originated_posts`.

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

