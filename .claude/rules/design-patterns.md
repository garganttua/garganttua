# Design Patterns
---
paths:
  - "**/*.java"
---

## Hierarchical Builder Pattern

All complex objects use fluent builders:
- `IBuilder<T>` - basic builder interface
- `ILinkedBuilder<Link, Built>` - navigable parent-child relationships via `up()` method

```java
// Example usage
RuntimeBuilder.create()
    .step("step1")
        .method(...)
        .up()  // returns to parent builder
    .build();
```

## Supplier Pattern

`ISupplier<T>` provides lazy evaluation throughout the codebase:
- Expressions evaluate to suppliers
- Enables deferred computation
- All binders use `ISupplier<?>` for parameter values

## Binder Pattern (Reflection Module)

Type-safe wrappers for reflection operations:
- `IConstructorBinder<T>` - object instantiation
- `IMethodBinder<R>` - method invocation (static/instance)
- `IFieldBinder<O,F>` - field access

## Dependency Tracking

`Dependent` interface declares type dependencies:
- Used for resolution ordering
- Detects circular dependencies
- All modules depend on `garganttua-commons`
