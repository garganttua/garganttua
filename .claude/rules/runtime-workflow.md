# Runtime Workflow Engine
---
paths:
  - "**/garganttua-runtime/**/*.java"
  - "**/runtime/**/*.java"
---

## Annotations

### Workflow Definition
- `@RuntimeDefinition` - declares input/output types on the workflow class

### Structure
- `@Step` - defines workflow steps (order matters)

### Parameter Injection
- `@Input` - input parameters
- `@Output` - output parameters
- `@Context` - runtime context injection
- `@Variable` - workflow variables

### Exception Handling
- `@Catch` - catch specific exceptions
- `@FallBack` - fallback behavior on failure

## Programmatic Definition

Use the builder API for programmatic workflow definition:

```java
RuntimeBuilder.create()
    .step("step1")
        .method(...)
        .catch_(Exception.class)
            .fallback(...)
            .up()
        .up()
    .build();
```

## Module Dependencies

`runtime` depends on:
- `injection` (DI container)
- `execution` (chain-of-responsibility)
- `condition` (boolean conditions)
