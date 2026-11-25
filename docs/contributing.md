# Contribution guidelines

We are really glad you are seeing this and are considering contributing to the development of the
Negotiator Link.
We welcome any pull requests that help us grow as open-source software!

## Development Environment

Our core developers use the [IntelliJ IDEA](https://www.jetbrains.com/idea/) and UNIX based Operating systems.
and can therefore be more helpful in debugging any potential problems in your local development
environment.

### Running locally

Negotiator Link ships with a self-contained Docker Compose stack (Negotiator + Zammad + this
service, auto-configured) for end-to-end manual testing. For prerequisites and the step-by-step
startup, service URLs, and test users, refer to the **Quickstart** in the [README](../README.md).

Use the Maven wrapper (`./mvnw`) for all build and test commands. The fast inner-loop unit tests
(`./mvnw test`) need no Docker; the integration tests (`*IT.java`, run in `./mvnw verify`) use
[Testcontainers](https://testcontainers.com) and therefore require a running Docker daemon.

## Code style

For maintaining a uniform code style through the code base, please adhere to the
[Google Java Style](https://google.github.io/styleguide/javaguide.html).
To enforce this, we have set up a GitHub action that checks the code style of any modified files
using the [fmt-maven-plugin](https://github.com/spotify/fmt-maven-plugin).
You can also set up an IntelliJ IDEA plugin
[google-java-format](https://github.com/google/google-java-format).

## Commit messages

Please follow
the [Conventional Commits specification](https://www.conventionalcommits.org/en/v1.0.0/#summary).

## Versioning

For versioning of releases and tags please follow [Semantic Versioning](https://semver.org/).

## Submitting changes

We use a simple Git flow based on short-lived feature branches and a stable `master` branch.  
All work should be done in a `feature/<your-branch>` branch, created from `master`, and submitted as a pull request (
PR).  
Please ensure your PR includes a clear title using [Conventional Commits](https://www.conventionalcommits.org/) (e.g.
`feat: add PDF export`).  
PRs should be rebased or squashed before merging to keep the commit history clean.  
All contributions are reviewed before merging into `master`, which is always deployable.

Please send us a GitHub Pull Request with a clear description.
We have also provided
a [template](https://github.com/BBMRI-ERIC/negotiator-link/blob/master/.github/PULL_REQUEST_TEMPLATE.md)
with a checklist to help you with providing a high-quality contribution.

## Releasing

Releases are driven by **git tags**. Pushing a [SemVer](https://semver.org/) tag like `v0.1.0`
triggers CI to build and publish the Docker image to `ghcr.io/bbmri-eric/negotiator-link`
(see [README → Versioning](../README.md#versioning) for details). The project has no regular
release schedule; the changelog is kept as GitHub release descriptions.

To cut a release: tag and push (`git tag v0.1.0 && git push origin v0.1.0`), then go to
Releases → Draft a new release → choose the tag you pushed → Generate release notes → edit to
follow the structure below → Publish.

### Release Categories

- **Added:** for new features/additions
- **Fixed:** for bug fixes
- **Changed:** for changes in existing functionality
- **Deprecated:** for soon-to-be removed features
- **Removed:** for now removed features
- **Internal:** for chores and other changes that do not impact the functionality

### Breaking Changes

For breaking changes that require a major version bump (following [Semantic Versioning](https://semver.org/)):

- Use the **BREAKING:** prefix in the category description
- Clearly explain what changed and how to migrate
- Include migration steps or links to migration guides when applicable

Example:

- **BREAKING - Removed:** Legacy API endpoints */api/v1/* - migrate to */api/v2/*
- **BREAKING - Changed:** Configuration format updated - see migration guide in docs

---
Below is an example of a release description, please follow this structure!

### What's New in v2.0.0

- **Added:** export to CSV
- **BREAKING - Changed:** Authentication now requires API keys instead of username/password -
  see [authentication guide](/auth)
- **Fixed:** issue with date parsing
- **BREAKING - Removed:** Legacy endpoints */api/v1/users* and */api/v1/projects* - use */api/v2/users* and
  */api/v2/projects* instead
- **Internal:** refactor of logging module

Thanks to Alice and Bob for contributions!

⚠️ **This is a major release with breaking changes. Please review the migration steps above before upgrading.**

---

                        Good luck and thank you! 🙇🏻‍♂️
