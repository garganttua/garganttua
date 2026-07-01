# Code Quality Gates
---
paths:
  - "**/*.java"
  - "**/pom.xml"
  - "config/checkstyle/**"
---

Quality tooling introduced during the **2.0.0-ALPHA02 maintainability rework** (god-class
split + long-method extraction) and kept afterwards to prevent silent regression.

## Guiding principle: advisory, never blocking

All quality gates are **WARNING ONLY** — they NEVER fail the build. They produce a worklist,
not a wall. The default offline build does not even pull the analysis artifacts; everything
lives behind the opt-in `quality` Maven profile.

> **Judgment, not a number chase.** The thresholds below feed human review. Do **not**
> over-fragment subtle logic, nor inflate a value object count, just to turn a warning green.

**One documented exception — the release (tag) build blocks.** On a `v*` tag only, the
`quality-gate` job in `.github/workflows/maven-publish.yml` turns the same three tools into a hard
gate that `deploy` depends on, so an insufficient-quality tag is not published. It is **not** a
"0 violations" gate (that would fail on the documented-accepted debt below); it is a **ratchet**:
`scripts/quality-gate.py` fails the build only when a tool's violation count **regresses above the
committed baseline** in `config/quality-baseline.json`, or when reports are missing (analysis did not
run). Day-to-day and PR builds stay advisory. To accept new debt intentionally, regenerate the
baseline (`python3 scripts/quality-gate.py --update-baseline`) and commit it for review.

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

## Checkstyle size thresholds (`config/checkstyle/checkstyle.xml`)

| Check | Limit | Notes |
|-------|-------|-------|
| `FileLength` | **500** | god-class gate (production files) |
| `MethodLength` | **20** | `METHOD_DEF` only, `countEmpty=false`. **Constructors NOT checked** — large ctors are field-assignment / record canonical bodies (heuristic false positives), not refactor targets |
| `ParameterNumber` | **7** | advisory only; signals a missing value object. Known noisy hit: `AOTClass`' generated 23-arg ctor |

## God-class rule (FileLength > 500)

**Split to < 500 — STRICT**, with ONE documented exception class: **inherent large-interface
mirrors** that implement a wide contract of mandatory single-line accessors and have no
extractable complexity cluster. For these, extract the real cluster, then **accept residual
length** and add a `<b>Size note:</b>` javadoc explaining why.

Approved size exceptions (do not "fix" these by force):
- `AOTClass`, `RuntimeClass`, `RuntimeContext` — `IClass` / `IRuntimeContext` accessor mirrors
- `IReflection` — pure interface facade (52 methods, 0 defaults → unsplittable contract)
- `BuilderDependency` — `IBuilderDependency`: accessors + deep-internal validation, no clean extraction
- `InjectionContext` — cohesive `IInjectionContext` facade, no long methods
- `Bootstrap` — cohesive `IBootstrap` orchestrator (already decomposed into 4 collaborators)

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
  `SystemPrintln` (legitimate in the CLI), `CloseResource` (caller-owned resources)

## Process & gotchas

- **Sequencing:** per-module **strict**, bottom-up — finish one module before the next.
- **Gate:** commit per file/module; run a full `mvn -o clean install` between batches. This is
  the only thing that catches **AOT regressions** (`PureAotIntegrationTest`) — they are invisible
  to plain unit tests.
- **`@Expression` function classes are registered BY FQN** in
  `FrameworkBuiltinRegistrar.FRAMEWORK_FUNCTION_CLASSES`. Splitting one means adding the new
  class FQNs there, or the functions silently vanish.
- A naive brace counter **over-measures** method length: `{`/`}` inside string literals
  (codegen / ANSI / `${...}`) inflate the count. Use a literal/comment-aware scanner.

## Test coverage

The ALPHA02 rework took line coverage from **53.9% → 71.1%** (instructions 71.9%, classes 82.8%)
via ~1480 added tests. Treat the current figure as a **floor — do not regress it**.

- **Real tests only.** Assert actual behaviour; **no padding / vanity tests** to inflate the
  percentage. A test that exercises nothing meaningful is worse than no test.
- Measure with JaCoCo: `mvn clean test jacoco:report` (per-module report under `target/site/jacoco`).
- **Self-verify per module** before declaring done: `mvn -o test -pl <module>` (with `JAVA_HOME` on
  JDK 25). One module at a time keeps parallel test runs from racing on shared `target/` dirs.
- New `.java` test files only under `<module>/src/test` — no markdown, no file moves, no scope creep.

## Logging

**No Lombok, no SLF4J.** The reactor is fully de-Lomboked; the observable `Logger`
(`com.garganttua.core` logging, pure event source) replaces `@Slf4j` and the old `Diagnostics`
package. Get one with `Logger.getLogger(SomeClass.class)` (name-only — safe for `static` fields).
Level is set via the `garganttua.log.level` system property.

Log hygiene (enforced during the doc/log pass, watch for regressions):
- **Parameterize with `{}`** — never string-concatenate log arguments (`log.debug("x={}", x)`).
- Use the **correct level** — no `info` for per-node / hot-path chatter.
- **No stray `System.out`/`System.err`** — the one legitimate exception is deliberate CLI stdout
  (`garganttua-script` / `garganttua-console` REPL output), not diagnostic logging.

## Javadoc

- **Every public top-level type carries class javadoc** (the rework took this from 271 missing to
  ~0). New public types must keep that invariant.
- Documented size exceptions (see god-class rule) must carry a `<b>Size note:</b>` javadoc
  explaining why they exceed 500 lines.
- `@since` / `@Deprecated(since = ...)` tags are **historical** — never bulk-bump them on a version
  release; they record when something was introduced/deprecated.

## README & version strings

- **READMEs must follow `templates/README.md.template`.** The architecture/dependency sections are
  generated — regenerate with `python3 scripts/run_all.py` (the generator is idempotent; do not
  hand-edit generated blocks).
- **Never hardcode the version in code.** The CLI version (`--version` / banner) reads
  `com.garganttua.core.bootstrap.GarganttuaVersion.getVersion()` (Maven-filtered resource), so a
  version bump does not require touching Java sources.
