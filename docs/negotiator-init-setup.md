# Negotiator Manual Configuration Reference

These steps document the manual Negotiator setup that enables the integration with negotiator-link in
**production**. They assume negotiator-link is already deployed and reachable under your real domain (see
[deployment.md](deployment.md)).

Log into Negotiator as an administrator before you start.

## Configure the Negotiator webhook

This is done **once per Negotiator instance**. The webhook points Negotiator at your deployed negotiator-link.

In Negotiator: **Admin → Webhooks → Create**

- **URL**: `https://<your-domain>/webhooks/negotiator`
  (your deployed negotiator-link domain, e.g. `https://negotiator-link.demo.bbmri-eric.eu/webhooks/negotiator`)
- **Secret**: the value of `NEGOTIATOR_WEBHOOK_SECRET` from your negotiator-link `.env`
- **SSL Verification**: **Yes** (enabled)

> Unlike the local development setup, SSL verification must be **enabled** in production.

## Onboarding a new organization

When an organization is onboarded in negotiator-link (see [deployment.md](deployment.md), "Onboarding a new
organization"), negotiator-link automatically creates a dedicated service account in Negotiator after the
application starts. Perform the two steps below **once per organization**.

### Step 1 — Rename the service account

The service account is created with its name set to the organization's `client_id` from the Client Credentials
flow, which is not human-readable. Rename it to something transparent that signals it acts on behalf of the
organization (e.g. the organization name or shortcut with a `" Bot"` suffix). The Negotiator UI does not support
this, so run the following queries directly in the Negotiator database.

Find the person ID by name:

```sql
SELECT id
FROM person
WHERE name = '<client_id>';
```

Update the person's name using the retrieved ID (replace `<id_from_step_1>` with the value returned above):

```sql
UPDATE person
SET name = '<organization_service_account_name>'
WHERE id = <id_from_step_1>;
```

### Step 2 — Assign the service account to the organization's resources

In Negotiator: **Admin → Governance → Organizations → `<organization>` → `<select a resource>`**

- **Add user**: the service account renamed in Step 1

Repeat for every resource of the organization that should participate in the integration.
