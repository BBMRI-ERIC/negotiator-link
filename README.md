# Negotiator Link

> **Note:** This project is currently under development. APIs and configuration formats may change without notice.

**Negotiator Link** is a standalone middleware service that
integrates [BBMRI-ERIC Negotiator](https://github.com/BBMRI-ERIC/negotiator) with external help desk systems. It allows
representatives of research organizations to manage access decisions and communicate with researchers entirely from
within their existing help desk environment, without needing to open the Negotiator application.

The middleware bridges two directions:

- **Negotiator → Help Desk:** When a negotiation is approved, a ticket is automatically created in the organization's
  help desk. Subsequent negotiation events (e.g. state changes, new posts, resource state updates) are reflected in the
  ticket in real time.
- **Help Desk → Negotiator:** Representatives can trigger resource state transitions and send a private messages
  to researchers directly from the help desk ticket. The middleware forwards these actions to Negotiator on their
  behalf.

## Quickstart (Local Development)

The included `compose.yml` starts a complete local environment for end-to-end manual testing. It runs all three systems
— Negotiator, negotiator-link, and Zammad — in Docker with no external dependencies.

### Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) with at least **6 GB RAM** allocated (Zammad
  requires significant memory)
- [Docker Compose](https://docs.docker.com/compose/) v2.x (included in Docker Desktop)

### Services started

| URL | Service |
|---|---|
| `http://localhost:8080` | Negotiator UI + API |
| `http://localhost:4011` | OIDC server mock (identity provider) |
| `http://localhost:8090` | negotiator-link API |
| `http://localhost:8888` | Zammad UI |

### Step 1 — Configure environment variables

```bash
cp .env.example .env
```

Open `.env` and fill in the values:

- **`NEGOTIATOR_WEBHOOK_SECRET`** — shared secret between Negotiator and negotiator-link for webhook signature
  verification. You can keep there example for local testing: `whsec_bG9jYWwtZGV2LXdlYmhvb2stc2VjcmV0`
- **`ZAMMAD_MHH_API_TOKEN`** — Zammad API token (obtain in Step 5 after Zammad starts)
- **`ZAMMAD_MHH_CALLBACK_SECRET`** — shared secret for verifying inbound Zammad callbacks. Can be any string for local
  testing, e.g. `local-dev-callback-secret`

### Step 2 — Start everything

```bash
docker compose up
```

Startup takes 3–5 minutes. Check that all containers are healthy after startup with:

```bash
docker compose ps
```

### Step 3 — Complete the Zammad setup wizard

Go to `http://localhost:8888` and follow the first-time setup. Create an admin account when prompted.

### Step 4 — Create a dedicated group for negotiator-link

In Zammad: **Admin → Manage → Groups → New Group**

- **Name**: `negotiator-link`
- **Assignment Timeout**, **Follow-up** and other fields: leave at defaults

This group will own all tickets created by the middleware.

### Step 5 — Create a dedicated role for negotiator-link

In Zammad: **Admin → Manage → Roles → New Role**

- **Name**: `negotiator-link`
- **Permissions**: enable `ticket.agent` and `user_preferences.access_token` (needed to generate API tokens)
- **Group permissions**: select the `negotiator-link` group created above and set access to **full**

### Step 6 — Create a Zammad bot user for negotiator-link

negotiator-link needs a dedicated Zammad agent account for its API calls. This account is also used in trigger
conditions to prevent feedback loops (see Step 8).

In Zammad: **Admin → Users → New User**

- **First name**: `negotiator-link`
- **Email**: `negotiator-link@local.dev`
- **Role**: `negotiator-link` (the role created in Step 5 — uncheck the default `Agent` role)
- **Password**: any value (the account is only used via API token, never interactively)

### Step 7 — Create a Zammad API token

Log into Zammad as the `negotiator-link` bot user created in Step 6.

Go to **Avatar (top right) → Profile → Token Access → Create**.
Give it a name (e.g. `negotiator-link`) and enable all permissions. Copy the token into `.env` as
`ZAMMAD_MHH_API_TOKEN`, then restart negotiator-link:

```bash
docker compose restart negotiator-link
```

### Step 8 — Assign negotiator link service account to the resources of some organization

In Negotiator: **Admin → Governance → Organizations → "Biobank Väst" → <select a resource>**

- **Add user**: `negotiator-link` (the user was automatically created during bootstrap)

### Step 9 — Configure the Negotiator webhook

Log into Negotiator at `http://localhost:8080` as `admin` / `admin`.

Navigate to **Admin → Webhooks → Create** and fill in:

- **URL**: `http://negotiator-link:8080/webhooks/negotiator`
- **Secret**: the value of `NEGOTIATOR_WEBHOOK_SECRET` from your `.env`
- **Events**: select at minimum `negotiation.state.updated`

### Step 10 — Configure Zammad webhook and triggers

**Create the webhook endpoint:**
In Zammad: **Admin → Integrations → Webhooks → New Webhook**

- **Name**: `negotiator-link`
- **Endpoint**: `http://negotiator-link:8080/webhooks/helpdesk/bbmri-eric%3AID%3ASE_890`
- **HTTP method**: POST
- **SSL Verification**: No (in the production it would be "Yes")
- **HMAC SHA1 signature token**: `<value of ZAMMAD_MHH_CALLBACK_SECRET from .env>` (ensure authentication and integrity of webhook)

**Create trigger for new articles (post messages):**
In Zammad: **Admin → Manage → Triggers → New Trigger**

- **Name**: `negotiator-link: new article`
- **Action execution**: Selective
- Enable expert mode and ensure the logic is set to match all (AND).
- **Conditions**: `Article → Created by is not → <negotiator-link bot user>` AND `Article → Updated by is not → <negotiator-link bot user>` AND `Article Action → is → created` AND `Article Visibility → is → public`
- **Execute changes on objects**: `Webhook → negotiator-link`

**Create the three custom ticket fields:**
In Zammad: **Admin → System → Objects → Ticket → New Attribute**

> After adding all three fields, click **Update Database** at the top of the Objects page and wait for Zammad to
> restart before configuring the trigger below.

*Field 1 — negotiation state (display only):*

- **Name**: `negotiation_state`
- **Display**: `Negotiation State`
- **Type**: Single Selection Field
- **Required**: No
- **Default**: *(leave empty)*
- **Options** (click **➕ Add** after each value):
  `SUBMITTED`, `APPROVED`, `DECLINED`, `IN_PROGRESS`, `PAUSED`, `CONCLUDED`, `ABANDONED`
- **Permissions → ticket.agent → edit**: Shown ✓, Required ✗
- All other permission rows: Shown ✗, Required ✗
- **Position**: 1000

*Field 2 — resource state (display only):*

- **Name**: `negotiator_resource_state`
- **Display**: `Resource State`
- **Type**: Single Selection Field
- **Required**: No
- **Default**: *(leave empty)*
- **Options** (click **➕ Add** after each value):
  `SUBMITTED`, `REPRESENTATIVE_UNREACHABLE`, `REPRESENTATIVE_CONTACTED`,
  `CHECKING_AVAILABILITY`, `RESOURCE_UNAVAILABLE_WILLING_TO_COLLECT`, `RESOURCE_UNAVAILABLE`,
  `RESOURCE_AVAILABLE`, `ACCESS_CONDITIONS_INDICATED`, `ACCESS_CONDITIONS_MET`,
  `RESOURCE_NOT_MADE_AVAILABLE`, `RESOURCE_MADE_AVAILABLE`
- **Permissions → ticket.agent → edit**: Shown ✓, Required ✗
- All other permission rows: Shown ✗, Required ✗
- - **Position**: 1100

*Field 3 — resource transition event (editable by representative):*

- **Name**: `negotiator_resource_transition_event`
- **Display**: `Request Resource State Transition`
- **Type**: Single Selection Field
- **Required**: No
- **Default**: *(leave empty — must remain nullable)*
- **Options** (click **➕ Add** after each value):
  `MARK_AS_CHECKING_AVAILABILITY`,
  `MARK_AS_AVAILABLE`, `MARK_AS_UNAVAILABLE`, `MARK_AS_CURRENTLY_UNAVAILABLE_BUT_WILLING_TO_COLLECT`,
  `STEP_AWAY`, `INDICATE_ACCESS_CONDITIONS`,
  `GRANT_ACCESS_TO_RESOURCE`
- **Permissions → ticket.agent → edit**: Shown ✓, Required ✗
- All other permission rows: Shown ✗, Required ✗
- **Position**: 1200

**Lock the two display-only fields as read-only via Core Workflow:**
In Zammad: **Admin → System → Core Workflows → New Workflow**

- **Name**: `negotiator-link: lock display fields`
- **Object**: Ticket
- **Conditions**: *(none — applies to all tickets)*
- **Actions**:
  - `negotiation_state` → **Set fixed to** *(read-only)*
  - `negotiator_resource_state` → **Set fixed to** *(read-only)*

**Lock the resource transition state field as read-only via Core Workflow:**
In Zammad: **Admin → System → Core Workflows → New Workflow**
Set for each of the following resource states: SUBMITTED, REPRESENTATIVE_UNREACHABLE, ACCESS_CONDITIONS_INDICATED

- **Name**: `negotiator-link: lock resource transition when state is <state>`
- **Object**: Ticket
- **Conditions**: Resource State → is → <state>
- **Actions**:
  - `negotiator_resource_transition_event` → **Set fixed to** *(read-only)*
- Priority: 510 (then 520, 530 for the other two workflows)

**Create trigger for resource state transitions:**
In Zammad: **Admin → Manage → Triggers → New Trigger**

- **Name**: `negotiator-link: resource transition`
- **Action execution**: Selective
- **Conditions**: `Ticket → Request Resource State Transition → has changed` AND
  `Updated by → is not → <negotiator-link bot user>` AND (Article Visibility → is → internal OR Article Action → is not → created)
- make sure in expert mode under Condition that the logic AND is selected.
- **Execute changes on objects**: `Webhook → negotiator-link`

### Test users

The OIDC mock is pre-configured with the following test accounts for Negotiator:

| Username | Password | Role |
|---|---|---|
| `admin` | `admin` | Administrator |
| `Researcher` | `researcher` | Researcher |
| `Repr` | `repr` | Representative |
| `Biobanker` | `biobanker` | Biobanker |

### Stopping and resuming

To stop all containers without losing data:
```bash
docker compose stop
```

To resume:
```bash
docker compose start
```

To rebuild and restart negotiator-link:
```bash
docker compose up --build -d negotiator-link
```

To see the logs of negotiator-link:
```bash
docker compose logs -f negotiator-link
```

## Related Projects

- [Negotiator Repository](https://github.com/BBMRI-ERIC/negotiator)
- [Negotiator Documentation](https://bbmri-eric.github.io/negotiator)