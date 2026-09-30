# Deploying Negotiator-Link

This guide describes how to deploy **negotiator-link** to a remote VM and reach it through a public
HTTPS URL that points at that VM.

Throughout this guide, `https://negotiator-link.demo.bbmri-eric.eu/` is used only as an **example**
public URL. Replace it everywhere with the real domain that resolves to your VM. Likewise, the SSH
host names and IP addresses shown below are examples — substitute your own infrastructure's values.

Only negotiator-link and its own PostgreSQL run on the VM. Negotiator and help desks (Zammad, Alvao,
…) are **external** services that communicate with negotiator-link over HTTPS and webhooks.

---

## 1. Topology

```
                          HTTPS                                      HTTP
  Internet ─────────────────────────────────────▶  upstream proxy ───────────▶  VM :80  ──▶  container :8080
  https://<your-domain>/                         (TLS terminated here)                       negotiator-link
  (e.g. https://negotiator-link.demo.bbmri-eric.eu/)

  External dependencies:
    • Negotiator   e.g. https://negotiator.acc.bbmri-eric.eu/
    • Help desk    Zammad, Alvao, …   (per organization)
```

- TLS is terminated by the upstream reverse proxy. The VM only needs to serve **plain HTTP on port 80**.
- The application listens on **8080 inside the container** and runs as a non-root user. Compose maps
  **host `80` → container `8080`**.
- Your public domain (e.g. `negotiator-link.demo.bbmri-eric.eu`) must be configured to point at this
  infrastructure so that anything on the VM's port 80 is exposed there. This DNS/proxy setup is done
  by whoever provisions the VM.

---

## 2. Prerequisites

### SSH access

Add the provisioner-supplied config to `~/.ssh/config` on your machine (the VM is reached through a
jump host):

Example `~/.ssh/config`:
```sshconfig
Host proxy
    Hostname 147.251.245.113
    User oholis

Host negotiator-link
    HostName 172.16.5.159
    User debian
    ProxyJump proxy
```

Then `ssh negotiator-link` connects straight to the VM through the proxy.

### On the VM

Only Docker is required — the image is built by Docker, so **no Java, Maven, or Node.js** need to be
installed on the VM.

```bash
docker --version           # Docker Engine
docker compose version     # Compose v2.x
```

If Docker is missing, install Docker Engine + the Compose plugin (Debian):
<https://docs.docker.com/engine/install/debian/>. Add your user to the `docker` group (or prefix
commands with `sudo`).

---

## 3. Files involved

Committed in the repository (templates — safe to commit):

| File | Purpose |
|---|---|
| `compose.prod.yml` | Production Compose: negotiator-link + PostgreSQL only |
| `.env.production.example` | Template for the environment/secrets file |
| `organizations.prod.yml` | Template for the organization config |
| `Dockerfile` | Multi-stage build (Maven → JRE); reused as-is |

Created on the VM from the templates (**never committed** — contain secrets / environment-specific
values):

| File | Created from | Notes |
|---|---|---|
| `.env` | `.env.production.example` | Real DB password, issuer URI, tokens |
| `organizations.yml` | `organizations.prod.yml` | Real Zammad base URL(s) |

Because we build on the VM, the **application source** must also be present there. The simplest way
to get the source + Compose file + templates together is to `git clone` the repository on the VM.

---

## 4. First deployment

```bash
# 1. Connect (ProxyJump is handled by ~/.ssh/config)
ssh negotiator-link

# 2. Get the code onto the VM (clone once; later updates use `git pull`)
git clone <repo-url> ~/negotiator-link
cd ~/negotiator-link

# 3. Create the production config from the templates
cp .env.prod.example .env
cp organizations.prod.yml organizations.yml

# 4. Fill in the real values
nano .env                 # DB_PASSWORD, NEGOTIATOR_ISSUER_URI, client creds, webhook + Helpdesk secrets
nano organizations.yml    # set the real Helpdesk baseUrl for each organization

# 5. Build the image and start the stack in the background
sudo docker compose -f compose.prod.yml up -d --build
```

Notes:
- Docker Compose auto-loads `.env` from the current directory.
- `organizations.yml` is bind-mounted **relative to the Compose file's directory**, so it must sit
  next to `compose.prod.yml` (i.e. in `~/negotiator-link`).
- The first build downloads Maven dependencies and can take a few minutes.
- The app fails fast if a required secret is absent — if it exits on startup, check `.env` first.

---

## 5. Verify the deployment

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

### Distinguishing an application problem from a proxy/network problem

| Symptom | Likely cause |
|---|---|
| VM-local `curl http://localhost/actuator/health` **works**, external `curl https://...` **fails** | Upstream proxy / DNS / TLS issue — the app is fine. Contact the infrastructure provisioner. |
| VM-local `curl` **fails / connection refused** | App or container problem — inspect `docker compose -f compose.prod.yml logs negotiator-link` and `ps`. |
| External returns **502 / 504** | Proxy reached the VM but the container is down or unhealthy — check container status and logs. |
| App container keeps restarting / exits at startup | Missing or wrong env var in `.env`, or DB not reachable — check logs for the failing property. |

---

## 6. Updating deployed negotiator-link

```bash
ssh negotiator-link
cd ~/negotiator-link
git pull                                          # fetch updated source / compose / templates
sudo docker compose -f compose.prod.yml up -d --build  # rebuild image and restart changed services
sudo docker compose -f compose.prod.yml logs -f negotiator-link
```

- Database data persists in the `negotiator-link-db-data` volume across rebuilds and restarts.
- Flyway applies any new migrations automatically on startup.
- `.env` and `organizations.yml` are left untouched by `git pull` (they are git-ignored on purpose).
  If a release adds a new required variable, update `.env` before restarting. Or if release adds
  a new organization, update `organizations.yml` before restarting.

Stop / start containers without deleting:
```bash
sudo docker compose -f compose.prod.yml stop
sudo docker compose -f compose.prod.yml start
```

---

## 7. Onboarding a new organization

When a new organization wants to start using the integration, no code change or image rebuild is
needed — only configuration. On the VM:

1. **Add the organization to `organizations.yml`.** Append a new organization block following the
   structure in `organizations.prod.yml` — set its `organizationExternalId`, `ticketStrategy`, the
   help-desk `baseUrl`, and reference its credentials via `${...}` placeholders (never hardcode
   secrets).
2. **Add the organization's secrets to `.env`.** Add the new Negotiator OAuth2 client credentials, Negotiator webhook 
   secret, and the help-desk API token / callback secret referenced by the placeholders above.
3. **Apply the change:**
   ```bash
   cd ~/negotiator-link
   sudo docker compose -f compose.prod.yml up -d
   ```
   `up -d` recreates the negotiator-link container so it picks up the new `.env` values and re-reads
   `organizations.yml` (the config is read once at startup). No `--build` is required, and the
   database and its data are untouched.
4. **Verify** the container came up cleanly with the new org and check for config errors:
   ```bash
   sudo docker compose -f compose.prod.yml ps
   sudo docker compose -f compose.prod.yml logs -f negotiator-link
   ```

> Configuring negotiator-link is only one side. The organization's **Negotiator webhook** and
> **help-desk webhook/triggers** must also be set up to point at negotiator-link (see the webhook
> and trigger setup in the project `README.md`). Until both sides are configured, events will not
> flow.

---

## 8. Security notes

- **Secrets never live in the repository.** `.env` and the filled-in `organizations.yml` are
  git-ignored and must only exist on the VM. Only the `*.example` / `*.prod.yml` **templates**
  (placeholders) are committed.
- All credentials and signing secrets come from environment variables; the application refuses to
  start if a required one is missing.
