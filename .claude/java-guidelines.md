# Java & Spring Boot Guidelines

## Java Best Practices

- **Comments**: Only add a comment when the WHY is non-obvious (hidden constraint, workaround, subtle invariant). No
  Javadoc on self-explanatory methods.
- **Error handling**: Do not add error handling for scenarios that cannot happen. Only validate at system boundaries (
  inbound HTTP controllers, external API clients).
- **Records**: Use records for DTOs and all immutable data structures instead of traditional classes.
- **Pattern matching**: Use pattern matching for `instanceof` and `switch` expressions to simplify type casting and
  branching.
- **Type inference**: Use `var` for local variables only when the type is unambiguous from the right-hand side.
- **Immutability**: Make classes and fields `final` where possible. Use `List.of()`/`Map.of()` for fixed data,
  `Stream.toList()` for immutable list results.
- **Streams and lambdas**: Use the Streams API and method references (`stream.map(Foo::toBar)`) for collection
  processing.
- **Null handling**: Do not return or accept `null`. Use `Optional<T>` for possibly-absent values. Use
  `Objects.requireNonNull()` at construction boundaries.

## Naming Conventions

Follow Google Java Style:

- `UpperCamelCase` — classes and interfaces
- `lowerCamelCase` — methods and variables
- `UPPER_SNAKE_CASE` — constants
- `lowercase` — package names

Use nouns for classes (`NegotiatorClient`), verbs for methods (`sendResourceEvent`). No abbreviations, no Hungarian
notation.

## Testing

- Every handler must have a unit test covering the happy path and the main failure branches.
- Adapter implementations have integration tests using WireMock to stub the help desk HTTP API.
- Prefer slice tests (`@WebMvcTest`, `@DataJpaTest`) for focused testing.
- Test class names: `{SubjectUnderTest}Test`. One test file per production class.
- Test method naming: `subject_when_outcome` — e.g. `constructor_whenCallbackSecretIsBlank_throws`, `findByExternalId_whenOrgUnknown_returnsEmpty`.
- Unit tests live under `src/test/java/.../unit/` mirroring the production package beneath it.
- Integration tests (those that load a Spring context) live under `src/test/java/.../integration/` mirroring the production package beneath it.
- Arrange/Act/Assert structure, no blank lines between related assertions.

## Bug Patterns to Avoid

| Rule  | Description                                          | Notes                                              |
|-------|------------------------------------------------------|----------------------------------------------------|
| S2095 | Resources must be closed                             | Use try-with-resources for streams, sockets, files |
| S1698 | Use `.equals()`, not `==` for object comparison      | Critical for Strings and boxed primitives          |
| S1905 | Remove redundant casts                               |                                                    |
| S3518 | Conditions must not always evaluate to true or false | Check for loop invariants and dead branches        |
| S108  | Remove unreachable code                              | Code after `return`/`throw` must be cleaned up     |

## Code Smells to Avoid

| Rule  | Description                | Notes                                        |
|-------|----------------------------|----------------------------------------------|
| S107  | Too many method parameters | Refactor into a parameter object or builder  |
| S121  | Duplicated code blocks     | Extract to shared methods                    |
| S138  | Methods too long           | Break into smaller, focused, testable units  |
| S3776 | High cognitive complexity  | Flatten nested logic, extract methods        |
| S1192 | Duplicated string literals | Replace with constants or enums              |
| S1854 | Unused assignments         | Remove dead variables                        |
| S109  | Magic numbers              | Replace with named constants                 |
| S1188 | Empty catch blocks         | Always log or handle exceptions meaningfully |

## Spring Boot: Dependency Injection

- Constructor injection for all dependencies. Fields declared `private final`.
- No `@Autowired` on fields.

## Spring Boot: Configuration

- Use `application.yml` (not `.properties`) for all configuration going forward.
- Use `@ConfigurationProperties` for type-safe binding of grouped properties.
- Use Spring profiles (`dev`, `test`, `prod`) for environment-specific configuration.
- Secrets always come from environment variables — never hardcoded in config files.

## Spring Boot: Service Layer

- Business logic lives in `@Service`-annotated classes. Controllers only validate, delegate, and return.
- Services must be stateless and independently testable.
- Service method signatures use domain IDs or DTOs — do not expose JPA entities across layer boundaries unless
  necessary.

## Spring Boot: Logging

- Use SLF4J for all logging (`@Slf4j` from Lombok).
- Never use `System.out.println()` or concrete implementations (Logback, Log4j2) directly.
- Always use parameterized logging: `log.info("Resource {} transitioned to {}", resourceId, state);`

## Spring Boot: Input Validation & Security

- Validate all inbound request bodies and parameters with JSR-380 annotations (`@NotNull`, `@Size`, `@Valid`).
- Always use Spring Data JPA or `NamedParameterJdbcTemplate` — never concatenate user input into queries.

## Utility Classes

Make utility classes `final` with a private constructor to prevent instantiation.

## Build Verification

After adding or modifying code, verify the build:

```
./mvnw clean install
```

All tests must pass before considering a task complete.