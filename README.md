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
- **Help Desk → Negotiator:** Representatives can trigger resource state transitions and send public or private messages
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

### Step 4 — Create a Zammad bot user for negotiator-link

negotiator-link needs a dedicated Zammad agent account for its API calls. This account is also used in trigger
conditions to prevent feedback loops (see Step 6).

In Zammad: **Admin → Users → New User**

- **First name**: `negotiator-link`
- **Email**: `negotiator-link@local.dev`
- **Role**: `Agent`
- **Password**: any value (the account is only used via API token, never interactively)

### Step 5 — Create a Zammad API token

Log into Zammad as the `negotiator-link` bot user created in Step 4.

Go to **Avatar (top right) → Profile → Token Access → Create**.
Give it a name (e.g. `negotiator-link`) and enable all permissions. Copy the token into `.env` as
`ZAMMAD_MHH_API_TOKEN`, then restart negotiator-link:

```bash
docker compose restart negotiator-link
```

### Step 6 — Configure the Negotiator webhook

Log into Negotiator at `http://localhost:8080` as `admin` / `admin`.

Navigate to **Admin → Webhooks → Create** and fill in:

- **URL**: `http://negotiator-link:8080/webhooks/negotiator`
- **Secret**: the value of `NEGOTIATOR_WEBHOOK_SECRET` from your `.env`
- **Events**: select at minimum `negotiation.state.updated`

### Step 6 — Configure Zammad webhook and triggers

**Create the webhook endpoint:**
In Zammad: **Admin → Integrations → Webhooks → New Webhook**

- **Name**: `negotiator-link`
- **Endpoint**: `http://negotiator-link:8080/webhooks/helpdesk/bbmri:DE:MHH`
- **HTTP method**: POST
- **Custom header**: `X-Helpdesk-Secret: <value of ZAMMAD_MHH_CALLBACK_SECRET from .env>`

**Create trigger for new articles (post messages):**
In Zammad: **Admin → Manage → Triggers → New Trigger**

- **Name**: `negotiator-link: new article`
- **Conditions**: `Article → Sender is → Customer` AND `Updated by → is not → <negotiator-link bot user>`
- **Actions**: `Notify Webhook → negotiator-link`

**Create trigger for resource state transitions:**
In Zammad: **Admin → Manage → Triggers → New Trigger**

- **Name**: `negotiator-link: resource transition`
- **Conditions**: `Ticket → negotiator_resource_transition_event → has changed` AND `is not → (empty)` AND
  `Updated by → is not → <negotiator-link bot user>`
- **Actions**: `Notify Webhook → negotiator-link`

> The custom ticket fields (`negotiator_resource_state`, `negotiator_resource_transition_event`, etc.) must be created
> first via **Admin → Objects** before the second trigger can be configured. See the
> [integration plan](docs/helpdesk-integration-plan.md) for the full list of required fields.

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

## Related Projects

- [Negotiator Repository](https://github.com/BBMRI-ERIC/negotiator)
- [Negotiator Documentation](https://bbmri-eric.github.io/negotiator)