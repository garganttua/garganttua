# Java Conventions
---
paths:
  - "**/*.java"
---

## General Rules

- Java 21 required
- Use Lombok annotations for boilerplate reduction: `@Getter`, `@Setter`, `@Builder`, `@RequiredArgsConstructor`
- Use Java records for immutable value objects (e.g., `BeanDefinition`, `BeanReference`)
- Use `Optional<T>` for nullable/conditional values - never return null
- Use SLF4J for logging (`@Slf4j` Lombok annotation)
- Thread-safe collections via `Collections.synchronizedMap/List`

## Naming Conventions

- Interfaces prefixed with `I` (e.g., `IBuilder`, `ISupplier`, `IBeanProvider`)
- Abstract classes prefixed with `Abstract` when appropriate
- Test classes suffixed with `Test`

## Code Style

- Prefer fluent APIs with method chaining
- Use `var` for local variables when type is obvious
- Avoid raw types - always use generics
- No wildcard imports - use explicit imports
