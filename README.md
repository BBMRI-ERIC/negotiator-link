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

## Related Projects

- [Negotiator Repository](https://github.com/BBMRI-ERIC/negotiator)
- [Negotiator Documentation](https://bbmri-eric.github.io/negotiator)