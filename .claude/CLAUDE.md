# negotiator-link

Middleware service that bridges Negotiator and external help desk systems (Zammad, Alvao).
Full goal, architectural decisions, and phased implementation
plan: [helpdesk-integration-plan.md](../docs/helpdesk-integration-plan.md)
Java and Spring Boot coding standards: [java-guidelines.md](java-guidelines.md)

## Stack

- Java 21, Spring Boot 4.1.0, Maven
- PostgreSQL + Flyway for persistence
- Spring Security OAuth2 Client Credentials for outbound Negotiator auth
- Lombok for boilerplate reduction
- Configuration via `application.yml` (prefer over `.properties`)

## Project structure

```
src/main/java/eu/bbmri_eric/negotiator/negotiatorlink/
├── inbound/        # Controllers only — no business logic
├── core/
│   ├── config/     # OrganizationConfigRegistry, OrganizationConfig
│   ├── verification/
│   ├── client/     # NegotiatorClient
│   ├── routing/    # EventRouter, TicketMappingRepository
│   ├── handler/    # One class per event type
│   └── model/      # Internal DTOs shared between core and adapter
└── adapter/        # HelpDeskAdapter interface + platform implementations
```

## Architectural rules:

- Handlers have a single responsibility. No routing logic inside handlers — routing is always resolved before a handler
  is called.
- The adapter layer must never depend on Negotiator DTOs directly. Translation happens in the core (handlers) before
  calling the adapter.

## Migrations

- All schema changes via Flyway migrations in `src/main/resources/db/migration/`.
- Naming: `V{n}__{description_in_snake_case}.sql`.
- Never modify an existing migration. Always add a new one.

## Security rules (non-negotiable)

- Secrets are never hardcoded. All credentials and signing secrets come from environment variables.
- The application must fail to start if a required secret environment variable is absent.
- Webhook signature and timestamp must be verified before any processing. Reject with HTTP 401 on failure.
- SSL verification is never disabled.

## Build verification

After every change, run `./mvnw clean install` and ensure all tests pass before considering the task complete.