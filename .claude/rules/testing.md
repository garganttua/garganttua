# Testing Standards
---
paths:
  - "**/*Test.java"
  - "**/*Tests.java"
  - "**/test/**/*.java"
---

## Test Commands

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=ExecutorChainTest

# Run tests in a specific module
mvn test -pl garganttua-expression

# Run tests with coverage
mvn clean test jacoco:report

# Run performance tests
mvn test -Dtest=*Perf*
```

## Conventions

- Use JUnit 5 (`@Test`, `@BeforeEach`, `@AfterEach`)
- Test class names: `<ClassName>Test.java`
- Mock external dependencies when needed
- Test exception cases explicitly
- Use descriptive test method names

## Module-Specific Tests

- `garganttua-runtime`: workflow execution tests, exception handling
- `garganttua-expression`: parser tests, expression evaluation
- `garganttua-injection`: DI container, bean lifecycle tests
- `garganttua-reflection`: binder tests, method resolution
