# Deploying Negotiator-Link

## 1. Prerequisites

Only Docker is required for building and running the image.

```bash
docker --version           # Docker Engine
docker compose version     # Compose v2.x
```

## 2. Files involved

| File                     | Purpose |
|--------------------------|---|
| `compose.prod.yml`       | Production Compose: negotiator-link + PostgreSQL only |
| `.env.prod.example`      | Template for the environment/secrets file |
| `organizations.prod.yml` | Template for the organization config |
| `Dockerfile`             | Multi-stage build (Maven → JRE); reused as-is |

## 3. First deployment

```bash
# 1. Get the code onto the VM (clone once; later updates use `git pull`)
git clone <repo-url> ~/negotiator-link
cd ~/negotiator-link

# 2. Create the production config from the templates
cp .env.prod.example .env
cp organizations.prod.yml organizations.yml

# 3. Fill in the real values
vi .env                 # DB_PASSWORD, NEGOTIATOR_ISSUER_URI, client creds, webhook + Helpdesk secrets
vi organizations.yml    # set the real Helpdesk baseUrl for each organization

# 4. Build the image and start the stack in the background
sudo docker compose -f compose.prod.yml up -d --build
```

Notes:
- Docker Compose auto-loads `.env` and `organizations.yml` from the current directory.
- The app fails fast if a required secret is absent, check `.env` first.

---

## 4. Verify the deployment

Run these on the VM unless noted otherwise.

**Containers are up and healthy:**
```bash
sudo docker compose -f compose.prod.yml ps
# negotiator-link      Up (healthy)
# negotiator-link-db   Up (healthy)
```

**Application logs** (look for the Spring "Started" line and Flyway migrations, no errors):
```bash
sudo docker compose -f compose.prod.yml logs -f negotiator-link
```

**The container is publishing port 80:**
```bash
sudo docker compose -f compose.prod.yml port negotiator-link 8080   # -> 0.0.0.0:80
ss -tlnp | grep ':80'                                          # host is listening on 80
```

**Health check locally on the VM:**
```bash
curl -f http://localhost/actuator/health      # -> {"status":"UP"}
```

**Health check externally** (from your laptop — use your real domain):
```bash
curl -I https://<your-domain>/actuator/health
# e.g. curl https://negotiator-link.demo.bbmri-eric.eu/actuator/health
```

## 5. Updating deployed negotiator-link

```bash
cd ~/negotiator-link
git pull                                               # fetch updated source / compose / templates
sudo docker compose -f compose.prod.yml up -d --build  # rebuild image and restart changed services
```

## 6. Onboarding a new organization

When a new organization wants to start using the integration, no code change or image rebuild is
needed — only configuration.

1. **Add the organization to `organizations.yml`.** Append a new organization block following the
   structure in `organizations.prod.yml` — set its `organizationExternalId`, `ticketStrategy`, the
   help-desk `baseUrl`, and reference its credentials via `${...}` placeholders (never hardcode
   secrets).
2. **Add the organization's secrets to `.env`.** Add the new Negotiator OAuth2 client credentials, Negotiator webhook
   secret, and the help-desk API token / callback secret referenced by the placeholders above.
3. **Apply the change:**
   ```bash
   cd ~/negotiator-link
   sudo docker compose -f compose.prod.yml up -d --force-recreate negotiator-link
   ```
   > No `--build` is required. The database container and its data are untouched.
4. **Verify** the container came up cleanly with the new org and check for config errors:
   ```bash
   sudo docker compose -f compose.prod.yml ps
   sudo docker compose -f compose.prod.yml logs -f negotiator-link
   ```

> Configuring negotiator-link is only one side. The organization's **Negotiator webhook** and
> **help-desk webhook/triggers** must also be set up to point at negotiator-link (see the webhook
> and trigger setup in the project `README.md`). Until both sides are configured, events will not
> flow.