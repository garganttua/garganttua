#!/usr/bin/env python3
"""Aggregate JaCoCo coverage across every module into one consolidated report.

Reads every `**/target/site/jacoco/jacoco.csv` (produced by the per-module
`jacoco:report` execution), computes a grand-total instruction/branch/line
coverage plus a per-module breakdown, and then:

  * writes the full table to `target/coverage/coverage-summary.md`
  * refreshes the coverage badge + headline between the
    `<!-- AUTO-GENERATED-COVERAGE-* -->` markers in the root README
  * appends the table to `$GITHUB_STEP_SUMMARY` when run in GitHub Actions

Run it from the repo root (or anywhere — paths are resolved from this file):
    python3 scripts/coverage.py

No external dependencies; offline-friendly, in the spirit of the project's
"offline Sonar proxy" tooling.
"""
import csv
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

START = "<!-- AUTO-GENERATED-COVERAGE-START -->"
STOP = "<!-- AUTO-GENERATED-COVERAGE-STOP -->"


def find_csvs():
    out = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        # don't descend into legacy/excluded trees
        if any(os.sep + x + os.sep in dirpath for x in ("old", "legacy", "bin")):
            continue
        if dirpath.endswith(os.path.join("target", "site", "jacoco")) and "jacoco.csv" in filenames:
            out.append(os.path.join(dirpath, "jacoco.csv"))
    return sorted(out)


def module_name(csv_path):
    # .../<module>/target/site/jacoco/jacoco.csv  ->  <module> (relative to ROOT)
    module_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(csv_path))))
    return os.path.relpath(module_dir, ROOT).replace(os.sep, "/")


def read_csv(path):
    ins_m = ins_c = br_m = br_c = ln_m = ln_c = 0
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            ins_m += int(row["INSTRUCTION_MISSED"]); ins_c += int(row["INSTRUCTION_COVERED"])
            br_m += int(row["BRANCH_MISSED"]); br_c += int(row["BRANCH_COVERED"])
            ln_m += int(row["LINE_MISSED"]); ln_c += int(row["LINE_COVERED"])
    return ins_m, ins_c, br_m, br_c, ln_m, ln_c


def pct(covered, missed):
    total = covered + missed
    return (100.0 * covered / total) if total else 0.0


def color(p):
    return ("brightgreen" if p >= 80 else "green" if p >= 70 else "yellowgreen"
            if p >= 60 else "yellow" if p >= 50 else "orange" if p >= 40 else "red")


def badge(p):
    # static shields.io badge — renders immediately, no external service / account
    return (f"![coverage](https://img.shields.io/badge/coverage-"
            f"{p:.1f}%25%20instructions-{color(p)})")


def main():
    csvs = find_csvs()
    rows = []
    tot = [0, 0, 0, 0, 0, 0]
    for c in csvs:
        vals = read_csv(c)
        rows.append((module_name(c), vals))
        tot = [a + b for a, b in zip(tot, vals)]
    rows.sort(key=lambda r: pct(r[1][1], r[1][0]))  # worst coverage first

    ins, br, ln = pct(tot[1], tot[0]), pct(tot[3], tot[2]), pct(tot[5], tot[4])

    # full per-module table (the consolidated "aggregate report")
    lines = ["| Module | Instr. | Branch | Line |", "|:--|--:|--:|--:|"]
    for name, v in rows:
        lines.append(f"| {name} | {pct(v[1], v[0]):.1f}% | {pct(v[3], v[2]):.1f}% | {pct(v[5], v[4]):.1f}% |")
    lines.append(f"| **TOTAL ({len(rows)} modules)** | **{ins:.1f}%** | **{br:.1f}%** | **{ln:.1f}%** |")
    table = "\n".join(lines)

    out_dir = os.path.join(ROOT, "target", "coverage")
    os.makedirs(out_dir, exist_ok=True)
    summary_path = os.path.join(out_dir, "coverage-summary.md")
    with open(summary_path, "w", encoding="utf-8") as f:
        f.write(f"# Coverage — {ins:.1f}% instructions, {br:.1f}% branches, {ln:.1f}% lines\n\n{table}\n")

    # README badge + headline
    readme = os.path.join(ROOT, "README.md")
    if os.path.isfile(readme):
        with open(readme, encoding="utf-8") as f:
            content = f.read()
        block = (f"{START}\n{badge(ins)}\n\n"
                 f"Coverage: **{ins:.1f}%** instructions · **{br:.1f}%** branches · "
                 f"**{ln:.1f}%** lines across {len(rows)} modules "
                 f"(JaCoCo; full per-module report in CI artifacts).\n{STOP}")
        if START in content and STOP in content:
            content = re.sub(re.escape(START) + ".*?" + re.escape(STOP), block, content, flags=re.DOTALL)
            with open(readme, "w", encoding="utf-8") as f:
                f.write(content)

    # GitHub Actions step summary
    step = os.environ.get("GITHUB_STEP_SUMMARY")
    if step:
        with open(step, "a", encoding="utf-8") as f:
            f.write(f"## Coverage — {ins:.1f}% instructions / {br:.1f}% branches / {ln:.1f}% lines\n\n{table}\n")

    print(f"Coverage: {ins:.1f}% instructions, {br:.1f}% branches, {ln:.1f}% lines "
          f"across {len(rows)} modules")
    print(f"Summary written to {os.path.relpath(summary_path, ROOT)}")


if __name__ == "__main__":
    main()
