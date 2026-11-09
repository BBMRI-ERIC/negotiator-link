# Zammad Manual Configuration Reference

These steps document the manual Zammad setup that needs to be done when integrating a Zammad instance with the
Negotiator via negotiator-link. The configured naming and secrets needs to be provided to the negotiator-link configuration
during the deployment as described in [deployment.md](deployment.md).

## Legend

Throughout this guide a few placeholders are used:

- **`<negotiator-link-url>`** — the base URL of your deployed negotiator-link.
  - Production: your real domain, e.g. `https://negotiator-link.demo.bbmri-eric.eu`
  - Local development: `http://negotiator-link:8080` (the Docker-internal address reachable from Zammad)
- **`<organizationExternalId>`** — the URL-encoded `organizationExternalId` of the organization, as configured in
  negotiator-link's organization config. Example: `bbmri-eric:ID:SE_890` is encoded as
  `bbmri-eric%3AID%3ASE_890`.
- **`<API token>`** — the Zammad bot API token negotiator-link uses for its outbound API calls. In the
  organization config, this is the value of `apiToken` field (see template [organizations.prod.yml](../organizations.prod.yml)).
- **`<callback secret>`** — the shared secret used to verify inbound Zammad callbacks (configured on the webhook
  as the HMAC SHA1 signature token). In the organization config, this is the value of `callbackSecret` field
  (see template [organizations.prod.yml](../organizations.prod.yml)).

## Step 0 — Complete the Zammad setup wizard (local development only)

> **Skip this step** if you are configuring an existing Zammad instance that is already in use.

For the local development environment, go to `http://localhost:8888` and follow the first-time setup wizard. Create an admin account when prompted.

## Step 1 — Create a dedicated group for negotiator-link

In Zammad: **Admin → Manage → Groups → New Group**

- **Name**: `negotiator-link-group`
- **Assignment Timeout**, **Follow-up** and other fields: leave at defaults

This group will own all tickets created by the middleware.

## Step 2 — Assign the negotiator-link group to the Representative users

In Zammad: **Admin → Manage → Users → `<select a user>` → Groups**

- **Add group**: `negotiator-link-group` (the group created above with full access)

This will be the users who would see the tickets created with group from Step 1.

## Step 3 — Create a dedicated role for negotiator-link

In Zammad: **Admin → Manage → Roles → New Role**

- **Name**: `negotiator-link-role`
- **Permissions**: enable `ticket.agent` and `user_preferences.access_token` (needed to generate API tokens)
- **Group permissions**: select the `negotiator-link-group` group created above and set access to **full**

## Step 4 — Create a Zammad bot user for negotiator-link

negotiator-link needs a dedicated Zammad agent account for its API calls. This account is also used in trigger
conditions to prevent feedback loops (see Step 5).

In Zammad: **Admin → Users → New User**

- **First name**: `Negotiator Bot`
- **Email**: the Zammad bot/service-account email configured for this organization in negotiator-link
  (local development: `negotiator-link@local.dev`)
- **Role**: `negotiator-link-role` (the role created in Step 3 — uncheck the default `Agent` role)
- **Password**: any value

## Step 5 — Create a Zammad API token

Log into Zammad as the `Negotiator Bot` bot user created in Step 4.

Go to **Avatar (top right) → Profile → Token Access → Create**.
Give it a name (e.g. `negotiator-link`) and enable all permissions. This token is negotiator-link's `<API token>`
for this organization. Provide it in the negotiator-link configuration.

> In the local development environment the bot token is provisioned automatically from `ZAMMAD_MHH_API_TOKEN` on
> first boot, so you can skip generating one by hand.

## Step 6 — Configure the Zammad webhooks

**Create the webhook with whole payload:**
In Zammad: **Admin → Integrations → Webhooks → New Webhook**

- **Name**: `negotiator-link: whole payload`
- **Endpoint**: `<negotiator-link-url>/webhooks/helpdesk/<organizationExternalId>`
- **HTTP method**: POST
- **SSL Verification**: per environment (production **Yes**, local **No**)
- **HMAC SHA1 signature token**: the organization's `<callback secret>`

**Create the ticket-state webhook:**
In Zammad: **Admin → Integrations → Webhooks → New Webhook**

- **Name**: `negotiator-link: ticket state updated`
- **Endpoint**: `<negotiator-link-url>/webhooks/helpdesk/<organizationExternalId>`
- **HTTP method**: POST
- **SSL Verification**: per environment (production **Yes**, local **No**)
- **HMAC SHA1 signature token**: the organization's `<callback secret>`
- **Custom payload** (enable custom payload):
  ```json
  {
    "ticket": {
      "state_updated": true,
      "id": "#{ticket.id}",
      "state": "#{ticket.state.name}"
    }
  }
  ```

## Step 7 — Create the three custom ticket fields

In Zammad: **Admin → System → Objects → Ticket → New Attribute**

> After adding all three fields, click **Update Database** at the top of the Objects page and wait for Zammad to
> restart before configuring the triggers below.

*Field 1 — negotiation state (display only):*

- **Name**: `negotiation_state`
- **Display**: `Negotiation Status`
- **Type**: Single Selection Field
- **Required**: No
- **Default**: *(leave empty)*
- **Options** (click **➕ Add** after each value):
  `SUBMITTED`, `APPROVED`, `DECLINED`, `IN_PROGRESS`, `PAUSED`, `CONCLUDED`, `ABANDONED`
- **Permissions → ticket.agent → edit**: Shown ✓, Required ✗
- All other permission rows: Shown ✗, Required ✗
- **Position**: 1000

*Field 2 — resource status (display only):*

- **Name**: `negotiator_resource_state`
- **Display**: `Resource Status`
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
- **Position**: 1100

*Field 3 — resource transition event (editable by representative):*

- **Name**: `negotiator_resource_transition_event`
- **Display**: `Request Resource Status Transition`
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

## Step 8 — Configure triggers

**Create trigger for new articles (post messages):**
In Zammad: **Admin → Manage → Triggers → New Trigger**

- **Name**: `negotiator-link: new article`
- **Action execution**: Selective
- Enable expert mode and ensure the logic is set to match all (AND).
- **Conditions**: `Article → Created by is not → <negotiator-link bot user>` AND `Article Action → is → created` AND `Article Visibility → is → public`
- **Execute changes on objects**: `Webhook → negotiator-link: whole payload`

**Create trigger for resource status transitions:**
In Zammad: **Admin → Manage → Triggers → New Trigger**

- **Name**: `negotiator-link: resource transition`
- **Action execution**: Selective
- **Conditions**: (`Ticket → Request Resource Status Transition → is → MARK_AS_AVAILABLE` OR <same_for_all_other_transition_event>) AND
  `Updated by → is not → <negotiator-link bot user>` AND (Article Action → is not → created OR Article Visibility → is → internal)
- Make sure in expert mode under Condition that the logic AND is selected.
- **Execute changes on objects**: `Webhook → negotiator-link: whole payload`

**Create trigger for ticket state changes:**
In Zammad: **Admin → Manage → Triggers → New Trigger**

- **Name**: `negotiator-link: ticket state updated`
- **Action execution**: Selective
- **Conditions**: `Ticket → State → has changed` AND `Updated by → is not → <negotiator-link bot user>`
- Make sure in expert mode under Condition that the logic AND is selected.
- **Execute changes on objects**: `Webhook → negotiator-link: ticket state updated`

## Step 9 — Configure Core Workflows

**Hide the custom fields on non-negotiator-link tickets:**
In Zammad: **Admin → System → Core Workflows → New Workflow**

- **Name**: `negotiator-link: hide custom fields when different group than negotiator-link-group`
- **Object**: Ticket
- **Saved Conditions**: `group` → **is not** → *negotiator-link-group*
- **Actions**:
  - `negotiation_state` → **Set to** *(hide)*
  - `negotiator_resource_state` → **Set to** *(hide)*
  - `negotiator_resource_transition_event` → **Set to** *(hide)*

**Lock the two display-only fields as read-only:**
In Zammad: **Admin → System → Core Workflows → New Workflow**

- **Name**: `negotiator-link: lock display fields`
- **Object**: Ticket
- **Conditions**: *(none — applies to all tickets)*
- **Actions**:
  - `negotiation_state` → **Set fixed to** *(read-only)*
  - `negotiator_resource_state` → **Set fixed to** *(read-only)*

**Lock the resource transition field when negotiation is not IN_PROGRESS:**
In Zammad: **Admin → System → Core Workflows → New Workflow**

- **Name**: `negotiator-link: lock resource transition when negotiation state is not IN_PROGRESS`
- **Object**: Ticket
- **Conditions**: Negotiation Status → is not → IN_PROGRESS
- **Actions**:
  - `negotiator_resource_transition_event` → **Set fixed to** *(read-only)*
- **Priority**: 505

**Lock the resource transition field for terminal resource statuses:**
In Zammad: **Admin → System → Core Workflows → New Workflow**

Create one workflow for each of the following resource statuses:
`SUBMITTED`, `REPRESENTATIVE_UNREACHABLE`, `ACCESS_CONDITIONS_INDICATED`,
`RESOURCE_MADE_AVAILABLE`, `RESOURCE_NOT_MADE_AVAILABLE`, `RESOURCE_UNAVAILABLE`

For each state:

- **Name**: `negotiator-link: lock resource transition when status is <STATE>`
- **Object**: Ticket
- **Conditions**: Resource Status → is → `<STATE>`
- **Actions**:
  - `negotiator_resource_transition_event` → **Set fixed to** *(read-only)*
- **Priority**: 510, 520, 530, … (increment by 10 for each state)