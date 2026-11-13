# Negotiator Link

**Negotiator Link** is a standalone middleware service that
integrates [BBMRI-ERIC Negotiator](https://github.com/BBMRI-ERIC/negotiator) with external help desk systems. It allows
representatives of biobanks to manage access decisions and communicate with researchers from
within their existing help desk environment, without needing to open the Negotiator application.

The middleware bridges two directions:

- **Negotiator → Help Desk:** When a negotiation is approved, a ticket is automatically created in the organization's
  help desk. Subsequent negotiation events (e.g. state changes, new posts, resource state updates) are reflected in the
  ticket in real time.
- **Help Desk → Negotiator:** Representatives can trigger resource state transitions and send private messages along with attachments
  to researchers directly from the help desk ticket. The middleware forwards these actions to Negotiator on their
  behalf.

## Quickstart (Local Development)

The included `compose.yml` starts a complete local environment for end-to-end manual testing in Docker with no external dependencies. 
Both Negotiator and Zammad are **configured automatically** on startup (see [Automatic configuration](#automatic-configuration)), 
so no manual setup is required.

### Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) with at least **6 GB RAM** allocated (Zammad
  requires significant memory)
- [Docker Compose](https://docs.docker.com/compose/) v2.x (included in Docker Desktop)

### Start all services

```bash
docker compose --env-file .env.dev up -d
```

Startup takes 3–5 minutes. The committed `.env.dev` contains ready-to-use development defaults — no copying or editing
is needed.

### Services

| URL | Service |
|---|---|
| `http://localhost:8080` | Negotiator UI + API |
| `http://localhost:4011` | OIDC server mock (identity provider) |
| `http://localhost:8090` | negotiator-link API |
| `http://localhost:8888` | Zammad UI |
| `http://localhost:9091` | Prometheus |

Prometheus scrapes the negotiator-link metrics endpoint at `http://negotiator-link:8080/actuator/prometheus`.
Pre-built Grafana dashboards live in `grafana/dashboards/`.

### Verify

```bash
docker compose --env-file .env.dev ps
```

All **16** long-running containers should be **up and healthy**. (Four one-shot containers — `zammad-init`,
`zammad-bot-token`, `zammad-provision`, and `negotiator-provision` — run once and exit, so they do not count)

Confirm the automatic configuration completed successfully by checking the one-shot provisioning logs:

```bash
docker compose --env-file .env.dev logs zammad-bot-token

docker compose --env-file .env.dev logs zammad-provision
 
docker compose --env-file .env.dev logs negotiator-provision
```

### Test users

**Negotiator** (via the OIDC mock):

| Username     | Password     | Role          |
|--------------|--------------|---------------|
| `admin`      | `admin`      | Administrator |
| `Researcher` | `researcher` | Researcher    |
| `Repr`       | `repr`       | Representative |
| `Biobanker`  | `biobanker`  | Biobanker     |

**Zammad:**

| Username          | Password             | Role                         |
|-------------------|----------------------|------------------------------|
| `admin@local.dev` | `Zammad-dev-admin1!` | Administrator/Representative |

## Automatic configuration

On `docker compose up`, one-shot containers provision both systems so the environment is ready for end-to-end testing
without any manual steps:

- **`zammad-bot-token`** — runs the Zammad setup wizard (creating the `admin@local.dev` admin) and creates the
  negotiator-link bot user with a fixed API token.
- **`zammad-provision`** — provisions Zammad groups, roles, bot membership, custom ticket fields, webhooks, triggers,
  and core workflows.
- **`negotiator-provision`** — registers the negotiator-link webhook in Negotiator and assigns the service accounts to
  their resources.

All three are idempotent and safe to re-run on every `docker compose up`.

The equivalent **manual** steps for configuring a real Zammad or Negotiator instance in production are documented
in [docs/zammad-init-setup.md](docs/zammad-init-setup.md) and [docs/negotiator-init-setup.md](docs/negotiator-init-setup.md).

## Common commands

```bash
# Stop all containers without losing data
docker compose --env-file .env.dev stop

# Resume
docker compose --env-file .env.dev start

# Rebuild and restart negotiator-link after a code change
docker compose --env-file .env.dev up --build -d negotiator-link

# Follow negotiator-link logs
docker compose --env-file .env.dev logs -f negotiator-link
```

## Documentation

Further documentation lives in [`docs/`](docs/):

- **[deployment.md](docs/deployment.md)** — how to deploy negotiator-link to a real environment.
- **Initial platform configuration** — the manual setup each platform needs to enable the integration:
  [zammad-init-setup.md](docs/zammad-init-setup.md) (Zammad) and
  [negotiator-init-setup.md](docs/negotiator-init-setup.md) (Negotiator).

## Related Projects

- [Negotiator Repository](https://github.com/BBMRI-ERIC/negotiator)
- [Negotiator Documentation](https://bbmri-eric.github.io/negotiator)

## License

This project is licensed under the GNU Affero General Public License v3.0.
See the [LICENSE](LICENSE) file for details.