# Dependency Injection System
---
paths:
  - "**/garganttua-injection/**/*.java"
  - "**/injection/**/*.java"
---

## Bean Reference Format

Bean identification uses the format:
```
[provider::][class][!strategy][#name][@qualifier]
```

Examples:
- `com.example.MyService` - by class
- `MyService#primary` - by class and name
- `MyService!singleton` - by class and strategy
- `provider::MyService@qualifier` - full reference

## Key Interfaces

- `IInjectionContext` - central DI hub with lifecycle support
- `IBeanProvider` - bean repository with query methods
- `IBeanFactory<T>` - creates bean instances with matching logic
- `BeanDefinition<T>` - immutable bean metadata (Java record)

## Strategies

- `singleton` - single instance per context
- `prototype` - new instance per injection

## Features

- Child contexts supported
- Property injection
- Lifecycle management
- Circular dependency detection
