#!/usr/bin/env python3
"""Generate the `## Parameters` README block: the `-D` switches the platform understands.

One registry, two renderings:

* the **reactor root** README gets the full table, grouped by scope;
* a **module** README gets only the parameters that module actually declares or reads —
  detected by scanning its own `pom.xml` and `src/main`, so ownership can never drift from
  the code. Modules that read none get no section at all rather than a misleading "none".

The block is delimited by AUTO-GENERATED-PARAMETERS markers and is idempotent: re-running
rewrites the same bytes. When a README predates the section, it is inserted just before
`## License` (or appended when there is no License section).
"""
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

START = "<!-- AUTO-GENERATED-PARAMETERS-START -->"
END = "<!-- AUTO-GENERATED-PARAMETERS-END -->"

RUNTIME = "Runtime (JVM)"
BUILD = "Build (Maven / APT)"
PLUGIN = "Build (Maven plugin)"

SCOPE_ORDER = [RUNTIME, BUILD, PLUGIN]

SCOPE_HEADINGS = {
    RUNTIME: "### Runtime — JVM system properties",
    BUILD: "### Build — Maven properties and annotation-processor options",
    PLUGIN: "### Build — Maven plugin parameters",
}

# `detect` is the literal searched for in a module's own pom.xml / src/main to decide whether the
# parameter belongs in that module's README. Keep it specific enough not to match prose.
PARAMETERS = [
    {
        "usage": "-Dgarganttua.log.level=<level>",
        "scope": RUNTIME,
        "values": "`TRACE`, `DEBUG`, `INFO`, `WARN` (`WARNING`), `ERROR`, `OFF` (`NONE`)",
        "default": "`INFO`",
        "effect": "Log threshold of the observable `Logger`. Events below it are never constructed. "
                  "Resolved once, at `Logger` class initialisation — setting it later has no effect. "
                  "An unrecognised value silently falls back to `INFO`.",
        "detect": '"garganttua.log.level"',
    },
    {
        "usage": "-Dgarganttua.perf.probe=true",
        "scope": RUNTIME,
        "values": "`true` (exactly; anything else, including an empty value, is false)",
        "default": "`false`",
        "effect": "Enables `HotPathProbe`, the nanosecond attribution aid for hot paths "
                  "(`HotPathProbe.report()` / `snapshot()`). Resolved once into a `static final`, so "
                  "when off the JIT folds every probe call to dead code. A measurement aid, not an "
                  "observability source — never leave it on in production.",
        "detect": '"garganttua.perf.probe"',
    },
    {
        "usage": "-Dgarganttua.packages=<pkg>[,<pkg>...]",
        "scope": RUNTIME,
        "values": "comma-separated package names",
        "default": "the package of the class passed to `GarganttuaApplication.run(...)`",
        "effect": "Packages scanned for bootstrap auto-detection. Also set as a project property by "
                  "`garganttua-script-maven-plugin` so a packaged script JAR carries its scan scope.",
        "detect": '"garganttua.packages"',
    },
    {
        "usage": "-Dgarganttua.direct.binders=true",
        "scope": BUILD,
        "values": "`true` \\| `false`",
        "default": "`false` (set in `core/pom.xml`; most function modules override it to `true`)",
        "effect": "Maven property forwarded to javac as `-Agarganttua.direct.binders` and consumed by "
                  "`garganttua-aot-annotation-processor`. When on, the module ships compile-time "
                  "`AOTClass_*` descriptors for its `@Reflected` classes — required for a native build "
                  "to see them. Modules inside the `aot-commons`/`aot-reflection` dependency cycle "
                  "must stay `false`.",
        "detect": "garganttua.direct.binders",
    },
    {
        "usage": "-Dgarganttua.core.version=<version>",
        "scope": BUILD,
        "values": "a `garganttua-core` version string",
        "default": "pinned to the reactor version",
        "effect": "Version of the `garganttua-core` artifacts the api and events modules resolve. In "
                  "the monorepo it must track the reactor version — a stale value silently skews the "
                  "build onto published artifacts instead of the reactor ones.",
        "detect": "garganttua.core.version",
    },
    {
        "usage": "-DjarName=<name>.jar",
        "scope": PLUGIN,
        "values": "a file name",
        "default": "`${project.artifactId}-${project.version}-script.jar`",
        "effect": "`garganttua-script-maven-plugin`: name of the executable script JAR produced.",
        "detect": 'property = "jarName"',
    },
    {
        "usage": "-Dpackages=<pkg>[,<pkg>...]",
        "scope": PLUGIN,
        "values": "package names",
        "default": "none (auto-detection only)",
        "effect": "Packages always written to the manifest / native config, on top of whatever "
                  "auto-detection finds.",
        "detect": 'property = "packages"',
    },
    {
        "usage": "-DautoDetect=<bool>",
        "scope": PLUGIN,
        "values": "`true` \\| `false`",
        "default": "`true`",
        "effect": "`garganttua-script-maven-plugin`: scan for packages carrying Garganttua "
                  "annotations instead of relying solely on `packages`.",
        "detect": 'property = "autoDetect"',
    },
    {
        "usage": "-DscanPackages=<pkg>[,<pkg>...]",
        "scope": PLUGIN,
        "values": "package names",
        "default": "every package of the output directory",
        "effect": "`garganttua-script-maven-plugin`: roots of the auto-detection scan.",
        "detect": 'property = "scanPackages"',
    },
    {
        "usage": "-DincludeResources=<bool>",
        "scope": PLUGIN,
        "values": "`true` \\| `false`",
        "default": "`true`",
        "effect": "`garganttua-script-maven-plugin`: bundle the module's resources into the script JAR.",
        "detect": 'property = "includeResources"',
    },
    {
        "usage": "-Dresources=<pattern>[,...]",
        "scope": PLUGIN,
        "values": "resource patterns",
        "default": "none",
        "effect": "`garganttua-native-image-maven-plugin`: extra resource patterns written to the "
                  "generated `resource-config.json`.",
        "detect": 'property = "resources"',
    },
    {
        "usage": "-Dreflections=<entries>",
        "scope": PLUGIN,
        "values": "`ReflectConfigEntry` items",
        "default": "none",
        "effect": "`garganttua-native-image-maven-plugin`: extra entries written to the generated "
                  "`reflect-config.json`.",
        "detect": 'property = "reflections"',
    },
    {
        "usage": "-Ddependencies=<coords>",
        "scope": PLUGIN,
        "values": "artifact coordinates",
        "default": "none",
        "effect": "`garganttua-native-image-maven-plugin`: dependencies whose native configuration is "
                  "merged into this module's.",
        "detect": 'property = "dependencies"',
    },
    {
        "usage": "-DconfigOutputNamespace=<path>",
        "scope": PLUGIN,
        "values": "a sub-path, or empty for the flat legacy layout",
        "default": "`<groupId>/<artifactId>`",
        "effect": "`garganttua-native-image-maven-plugin`: sub-path under `META-INF/native-image/` the "
                  "configs are written to. The default is unique per artifact, which is what keeps "
                  "uber-jars from colliding.",
        "detect": 'property = "configOutputNamespace"',
    },
]

SCANNED_SUFFIXES = (".java", ".xml", ".properties", ".yml", ".yaml")


def load_pom(path):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    return ET.parse(path).getroot(), ns


def find_modules(pom_path):
    root, ns = load_pom(pom_path)
    return [m.text.strip() for m in root.findall("m:modules/m:module", ns)]


def module_text(module_path):
    """Concatenates the module's OWN pom and main sources — submodules excluded, they answer for
    themselves."""
    chunks = []
    pom = os.path.join(module_path, "pom.xml")
    if os.path.exists(pom):
        with open(pom, "r", encoding="utf-8", errors="ignore") as f:
            chunks.append(f.read())

    main = os.path.join(module_path, "src", "main")
    for dirpath, _dirnames, filenames in os.walk(main):
        for name in filenames:
            if name.endswith(SCANNED_SUFFIXES):
                with open(os.path.join(dirpath, name), "r", encoding="utf-8", errors="ignore") as f:
                    chunks.append(f.read())

    return "\n".join(chunks)


def build_table(params, with_scope=False):
    """Renders the table. `with_scope` adds the scope column, needed wherever runtime and build
    parameters share one table — at the root they are separated by headings instead."""
    if with_scope:
        rows = ["| Parameter | Scope | Values | Default | Effect |", "|---|---|---|---|---|"]
        for p in params:
            rows.append(f"| `{p['usage']}` | {p['scope']} | {p['values']} | {p['default']} "
                        f"| {p['effect']} |")
    else:
        rows = ["| Parameter | Values | Default | Effect |", "|---|---|---|---|"]
        for p in params:
            rows.append(f"| `{p['usage']}` | {p['values']} | {p['default']} | {p['effect']} |")
    return "\n".join(rows)


def build_module_block(params):
    lines = [
        "Parameters this module declares or reads. Pass them with `-D` — on the JVM running the "
        "application for runtime scope, on the Maven command line for build scope.",
        "",
        build_table(params, with_scope=True),
    ]
    return "\n".join(lines)


def build_root_block():
    lines = [
        "Every `-D` switch the platform understands. Runtime parameters go on the JVM running the "
        "application (`java -D... -jar app.jar`); build parameters go on the Maven command line, "
        "where they override the value declared in the poms.",
    ]
    for scope in SCOPE_ORDER:
        params = [p for p in PARAMETERS if p["scope"] == scope]
        if not params:
            continue
        lines.append("")
        lines.append(SCOPE_HEADINGS[scope])
        lines.append("")
        lines.append(build_table(params))
    return "\n".join(lines)


def write_block(readme_path, block):
    with open(readme_path, "r", encoding="utf-8") as f:
        content = f.read()

    section = f"{START}\n{block}\n{END}"

    if START in content and END in content:
        new_content = re.sub(rf"{re.escape(START)}.*?{re.escape(END)}", lambda _m: section,
                             content, flags=re.DOTALL)
    else:
        heading = f"## Parameters\n\n{section}\n"
        match = re.search(r"^## License\s*$", content, flags=re.MULTILINE)
        if match:
            new_content = content[:match.start()] + heading + "\n" + content[match.start():]
        else:
            new_content = content.rstrip("\n") + "\n\n" + heading

    if new_content == content:
        return False

    with open(readme_path, "w", encoding="utf-8") as f:
        f.write(new_content)
    print(f"✔ Updated {readme_path}")
    return True


def process_module(module_path):
    readme_path = os.path.join(module_path, "README.md")
    pom_path = os.path.join(module_path, "pom.xml")

    if not os.path.exists(pom_path):
        return

    if os.path.exists(readme_path):
        text = module_text(module_path)
        matched = [p for p in PARAMETERS if p["detect"] in text]
        if matched:
            write_block(readme_path, build_module_block(matched))

    for submodule in find_modules(pom_path):
        process_module(os.path.join(module_path, submodule))


def main():
    script_dir = Path(__file__).resolve().parent
    parent_dir = script_dir.parent if script_dir.name == "scripts" else script_dir

    pom_parent = parent_dir / "pom.xml"
    if not pom_parent.exists():
        raise FileNotFoundError("Parent pom.xml not found")

    readme_parent = parent_dir / "README.md"
    if readme_parent.exists():
        write_block(str(readme_parent), build_root_block())

    for module in find_modules(str(pom_parent)):
        process_module(str(parent_dir / module))


if __name__ == "__main__":
    main()
