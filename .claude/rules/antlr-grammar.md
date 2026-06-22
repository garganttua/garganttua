# ANTLR4 Grammar Rules
---
paths:
  - "**/*.g4"
  - "**/garganttua-expression/**/*.java"
---

## Critical Rules

- **NEVER edit generated files** in `target/generated-sources/`
- Grammar location: `garganttua-expression/src/main/antlr4/com/garganttua/core/expression/parser/Expression.g4`
- Parser/Lexer classes are auto-generated via `antlr4-maven-plugin`

## Rebuild Commands

```bash
# Regenerate parser after grammar changes
mvn antlr4:antlr4 -pl garganttua-expression

# Full rebuild of expression module
mvn clean compile -pl garganttua-expression
```

## Expression Language Syntax

- Function call: `concatenate("a", "b")`
- Method call: `:methodName(arg1, arg2)`
- Constructor: `:(String.class, "value")`
- Types: primitives, `java.lang.String`, generics `List<String>`, arrays `String[]`

## Expression Node Interface

All expression nodes implement:
```java
IExpressionNode<R, S extends ISupplier<R>>
```
Evaluation produces suppliers for lazy computation.
