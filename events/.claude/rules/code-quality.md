# Code Quality Gates
---
paths:
  - "**/*.java"
  - "**/pom.xml"
  - "config/checkstyle/**"
---

Quality tooling imported from **garganttua-core** (its 2.0.0-ALPHA02 maintainability rework:
god-class split + long-method extraction) and adapted to garganttua-events. Kept to prevent
silent regression as the v2.0 multi-module rewrite grows.

## Guiding principle: advisory, never blocking

All quality gates are **WARNING ONLY** — they NEVER fail the build. They produce a worklist,
not a wall. The default offline build does not even pull the analysis artifacts; everything
lives behind the opt-in `quality` Maven profile.

> **Judgment, not a number chase.** The thresholds below feed human review. Do **not**
> over-fragment subtle logic, nor inflate a value-object count, just to turn a warning green.

## The `quality` profile

```bash
mvn -Pquality checkstyle:checkstyle   # human-readable size worklist
mvn -Pquality verify                  # passive gate: runs Checkstyle + SpotBugs + PMD, writes XML
```

Three tools, all `failOnViolation=false` / offline, main sources only (`includeTests=false`):

| Tool | Version | Role |
|------|---------|------|
| **Checkstyle** | 10.21.0 (plugin 3.6.0) | code-size gate (`config/checkstyle/checkstyle.xml`) |
| **SpotBugs** | 4.9.8 (plugin 4.9.8.3) | bug finder — `effort=Max`, `threshold=Low` |
| **PMD** | plugin 3.28.0 | `category/java/errorprone.xml` + `category/java/bestpractices.xml` |

This trio is the **offline Sonar proxy** — no server, no network.

> SpotBugs and PMD analyse compiled classes, so they only run once the module compiles.
> Checkstyle is source-based and runs even on a non-compiling tree.

## Checkstyle size thresholds (`config/checkstyle/checkstyle.xml`)

| Check | Limit | Notes |
|-------|-------|-------|
| `FileLength` | **500** | god-class gate (production files) |
| `MethodLength` | **20** | `METHOD_DEF` only, `countEmpty=false`. **Constructors NOT checked** — large ctors are field-assignment / record canonical bodies (heuristic false positives), not refactor targets |
| `ParameterNumber` | **7** | advisory only; signals a missing value object |

## God-class rule (FileLength > 500)

**Split to < 500 — STRICT**, with ONE documented exception class: **inherent large-interface
mirrors** that implement a wide contract of mandatory single-line accessors and have no
extractable complexity cluster. For these, extract the real cluster, then **accept residual
length** and add a `<b>Size note:</b>` javadoc explaining why.

There are no approved size exceptions in garganttua-events yet. When one is justified, list it
here so it is not "fixed" by force later.

## Long-method rule (MethodLength > 20)

Extract the **REAL** offenders (> ~35 lines **OR** multi-responsibility). **Accept as advisory**:
- cohesive 21–35-line methods
- flat registration lists / big trivial-arm `switch` dispatch
- exception-message, banner, and ANSI-report builders

Do not fragment cohesive subtle logic just to reach ≤ 20.

## SpotBugs / PMD handling

Fix the **real** bugs; suppress the known noise categories rather than churning code:
- ✅ fix: infinite recursion, `Object[]` in messages (use `Arrays.toString`), platform-charset
  string bytes, platform-default case conversion → **`Locale.ROOT`** (Turkish-i safety)
- 🔇 noise (accepted): PMD `GuardLogStatement` (moot with `{}`-parameterized logging),
  `CloseResource` (caller-owned resources, e.g. connector resources)

## Process & gotchas

- **Sequencing:** per-module **strict**, bottom-up (`api` → `expressions`/`core` → `connector-*`) —
  finish one module before the next.
- **Gate:** commit per file/module; run a full `mvn -o clean install` between batches.
- **`@Expression` functions are auto-discovered** by garganttua-core's `ExpressionContextBuilder`
  (scan of `@Expression`-annotated static methods). If you split a function class, the methods are
  still discovered by annotation — but verify the new class stays on the scan path / module.
- A naive brace counter **over-measures** method length: `{`/`}` inside string literals
  (codegen / ANSI / `${...}`) inflate the count. Use a literal/comment-aware scanner.

## Test coverage

The new v2.0 modules currently ship **no tests** — coverage starts from a clean slate. As tests
are added, treat the achieved figure as a **floor — do not regress it**.

- **Real tests only.** Assert actual behaviour; **no padding / vanity tests** to inflate the
  percentage. A test that exercises nothing meaningful is worse than no test.
- Measure with JaCoCo: `mvn clean test jacoco:report` (per-module report under `target/site/jacoco`).
- **Self-verify per module** before declaring done: `mvn -o test -pl <module>`. One module at a
  time keeps parallel test runs from racing on shared `target/` dirs.
- New `.java` test files only under `<module>/src/test` — no markdown, no file moves, no scope creep.

## Logging

garganttua-events uses **Lombok `@Slf4j` over SLF4J** (`org.slf4j`). (Note: this differs from
garganttua-core, which is de-Lomboked and uses its own observable `Logger`.)

Log hygiene:
- **Parameterize with `{}`** — never string-concatenate log arguments (`log.debug("x={}", x)`).
- Use the **correct level** — no `info` for per-message / hot-path chatter.
- **No stray `System.out`/`System.err`** for diagnostics — use the logger.

## Javadoc

- **Every public top-level type carries class javadoc.** New public types must keep that invariant.
- Documented size exceptions (see god-class rule) must carry a `<b>Size note:</b>` javadoc
  explaining why they exceed 500 lines.
- `@since` / `@Deprecated(since = ...)` tags are **historical** — never bulk-bump them on a version
  release; they record when something was introduced/deprecated.

## Version strings

- **Never hardcode the version in Java sources.** The version is owned by the POMs
  (`${project.version}` / the `garganttua.core.version` property). A version bump (via
  `./new-major.sh` / `./new-minor.sh` / `./new-patch.sh`) must not require touching Java code.
