# Integrating a new platform

This guide explains how to add support for a new help desk (or similar) platform. Zammad is the
reference implementation. It lives in `adapter/zammad/` — use it as the working example.

## What you need to do

Supporting a platform is a few mechanical steps. The adapters are wired in automatically —
Spring discovers your beans and `AdapterConfig` registers them by type, so there are no lookup
maps to edit. The only registry touch is declaring required `platformConfig` keys (Step 4).

1. Add a value to the `HelpdeskType` enum.
2. Implement `HelpdeskClient` — the **outbound** side (middleware → platform).
3. Implement `HelpdeskCallbackAdapter` — the **inbound** side (platform webhook → middleware).
4. Declare any required `platformConfig` keys for startup validation.
5. Configure an organization to use the new type.

Both implementations go under a new package `adapter/<platform>/`, are annotated `@Component`,
and return your new `HelpdeskType` from `getType()`.

```
adapter/
├── HelpdeskClient.java            (interface — implement)
├── HelpdeskCallbackAdapter.java   (interface — implement)
└── <platform>/
    ├── <Platform>Client.java          implements HelpdeskClient
    ├── <Platform>CallbackAdapter.java  implements HelpdeskCallbackAdapter
    └── <Platform>Fields.java           (optional) custom-field name constants
```

## How it is wired (so you don't have to)

`AdapterConfig` registers two maps, keyed by HelpdeskType:
```
  Map<HelpdeskType, HelpdeskCallbackAdapter>   — inbound  (callbacks)
  Map<HelpdeskType, HelpdeskClient>            — outbound (API writes)
```

The entries come from every `@Component` implementing the interfaces. At runtime, callers
resolve the right one with `config.type()` — the type on the organization's `OrganizationConfig`.
So each `HelpdeskType` must be claimed by **exactly one** client and **exactly one** callback
adapter.

## Step 1 — Register the type

`core/config/HelpdeskType.java`:

```java
public enum HelpdeskType {
  ZAMMAD,
  MY_PLATFORM
}
```

## Step 2 — Implement `HelpdeskClient` (outbound)

Write actions against the platform's API. Everything it needs comes from `OrganizationConfig`:

| From `OrganizationConfig` | Use for |
|---|---|
| `baseUrl()` | the platform's API root |
| `credentials()` (`Map<String,String>`) | auth — e.g. `credentials().get("apiToken")` |
| `platformConfig()` (`Map<String,Object>`) | platform-specific knobs — e.g. Zammad's `group`, `botEmail` |

Implement each method per its Javadoc in `HelpdeskClient.java`. Translate the `common`-model
inputs (`CreateTicketRequest`, `Attachment`, `CommentType`) into your platform's API shape.
`createTicket` must return the platform's ticket id as a `String`, which is reused on every later
call for that ticket.

Two things to respect:

- **At-least-once delivery.** Any method may be called more than once for the same logical
  action (a success whose response was lost gets retried). The duplicate side effects in this
  case are accepted in the current design.
- **State vs. ticket state.** `updateNegotiationState` / `updateResourceState` set *read-only
  display fields*, not the ticket's native state. Only `updateTicketState` changes the native
  state. If your platform uses a pending-transition marker, clear it in `updateResourceState`
  (see how `ZammadClient` nulls `TRANSITION_EVENT`).

Reference: [`ZammadClient.java`](../src/main/java/eu/bbmri_eric/negotiator/negotiatorlink/adapter/zammad/ZammadClient.java).

## Step 3 — Implement `HelpdeskCallbackAdapter` (inbound)

Three pure methods (**no external calls**) that turn a raw webhook into the internal model:

- **`verifyCallback(headers, body, callbackSecret)`** — check authenticity against the *raw
  body* (e.g. verify an HMAC signature). Return `false` on any failure; never throw. Runs
  before anything is persisted. `callbackSecret` is the org's configured secret.
- **`extractCallbackId(headers)`** — the platform's delivery id, used to deduplicate
  redeliveries. `Optional.empty()` if the platform provides none.
- **`parseCallback(payload)`** — translate the parsed body into one `HelpdeskCallbackEvent`,
  or `Optional.empty()` if the payload carries nothing actionable (will be ignored).

Map the platform's payload onto exactly one of the four event types:

| `HelpdeskCallbackType` | Event record | Meaning |
|---|---|---|
| `RESOURCE_TRANSITION` | `ResourceTransitionEvent` | representative requested a resource-state change |
| `POST_MESSAGE` | `PostMessageEvent` | a public comment (optionally with attachments) |
| `RESOURCE_TRANSITION_WITH_POST_MESSAGE` | `ResourceTransitionWithPostMessageEvent` | both at once |
| `TICKET_STATE_CHANGED` | `TicketStateChangedEvent` | native ticket state changed (e.g. closed) |

Notes:
- For attachments, emit a `HelpdeskAttachmentRef` whose `downloadPath` is the **relative** path
  your `HelpdeskClient.downloadAttachment` later accepts. The two must agree.

Reference: [`ZammadCallbackAdapter.java`](../src/main/java/eu/bbmri_eric/negotiator/negotiatorlink/adapter/zammad/ZammadCallbackAdapter.java).

## Step 4 — Declare required config keys for startup validation

The generic fields (`baseUrl`, `callbackSecret`, `negotiatorCredentials`, and that `credentials`
is non-empty) are validated at startup for every platform type automatically. But which keys live
inside `credentials` and `platformConfig` is platform-specific, so those required keys are **not**
checked unless you declare them in
[`OrganizationConfigRegistry`](../src/main/java/eu/bbmri_eric/negotiator/negotiatorlink/core/config/OrganizationConfigRegistry.java).

Add an entry for your type to each map, as Zammad does:

```java
// credentials your adapter reads — e.g. Zammad needs an apiToken
private static final Map<HelpdeskType, Set<String>> REQUIRED_CREDENTIAL_KEYS =
    Map.of(
        HelpdeskType.ZAMMAD, Set.of("apiToken"),
        HelpdeskType.MY_PLATFORM, Set.of("yourApiKey"));

// platformConfig your adapter reads — e.g. Zammad needs group + botEmail
private static final Map<HelpdeskType, Set<String>> REQUIRED_PLATFORM_KEYS =
    Map.of(
        HelpdeskType.ZAMMAD, Set.of("group", "botEmail"),
        HelpdeskType.MY_PLATFORM, Set.of("yourRequiredKey"));
```

If your platform has no mandatory `platformConfig` keys, you may leave it out of
`REQUIRED_PLATFORM_KEYS` (an empty `platformConfig` is allowed). `credentials`, however, is always
required to be non-empty, so declare its required key(s) in `REQUIRED_CREDENTIAL_KEYS`.

Without these, a missing key won't fail fast at startup.

## Step 5 — Configure an organization

Point an organization at the new platform by setting `type: "MY_PLATFORM"` on its entry in the
org config. The full structure, field-by-field, is documented in the annotated template
[`organizations.prod.yml`](../organizations.prod.yml), and onboarding steps are in
[deployment.md](deployment.md).

The one platform-specific part is that the `credentials`, `callbackSecret`, and `platformConfig`
keys are whatever **your** adapter reads — define them to match. Secrets come from environment
variables, so never hardcode them.

## Rules to keep in mind

- **Adapters speak only the `common` model + their own API.** Never import a Negotiator DTO
  into the adapter layer. The layering test (`LayeredArchitectureTest`) breaks the build 
  if you cross this line.
- **Keep callbacks pure.** `verifyCallback` / `extractCallbackId` / `parseCallback` do no I/O.
- **Secrets from env vars only**, and the app must fail to start if a required one is missing.

## Test it

Both adapters need **unit and integration** coverage — exercise each one properly (including
edge cases and failures). The split of what goes in a unit vs. integration test is up to you.
Zammad's tests are the reference: [`ZammadCallbackAdapterTest`](../src/test/java/eu/bbmri_eric/negotiator/negotiatorlink/unit/adapter/zammad/ZammadCallbackAdapterTest.java)
and [`ZammadClientIT`](../src/test/java/eu/bbmri_eric/negotiator/negotiatorlink/integration/adapter/zammad/ZammadClientIT.java).

Also add **component tests in both directions**, mirroring Zammad's in
[`component/`](../src/test/java/eu/bbmri_eric/negotiator/negotiatorlink/component). These drive a
signed webhook through the whole pipeline (controller → inbox → outbox → the mocked external API)
and assert the right calls come out the other side:

- **Negotiator → platform** (like `NegotiatorToZammadFlowIT`) — a Negotiator webhook ends up as
  the correct write to your platform's API.
- **platform → Negotiator** (like `ZammadToNegotiatorFlowIT`) — a platform callback ends up as
  the correct call to `NegotiatorClient`.

Unlike the adapter tests, component tests cover only the **basic happy-path functionality** of
the main flows (ticket creation, state updates, posts, attachments, resource transitions).

Then run the gate:

```
./mvnw com.spotify.fmt:fmt-maven-plugin:format -q && ./mvnw clean install
```
