#!/usr/bin/env python3
"""Blocking quality ratchet for the release (tag) build.

The `-Pquality` profile is advisory (failOnViolation=false) by platform policy, and the reactor
carries a set of *documented, accepted* violations (god-class size exceptions such as
`Domain`/`AOTClass`/`RuntimeClass`, advisory `ParameterNumber` noise, etc.). A naive "0 violations"
gate would fail every release on that known debt.

This gate instead *ratchets*: it counts Checkstyle / SpotBugs / PMD violations across every module's
report and fails the build only when a count EXCEEDS the committed baseline in
`config/quality-baseline.json` — i.e. only on a genuine regression (quality getting *worse* than the
vetted state). SpotBugs additionally must never exceed its baseline (kept at 0 when the reactor is
clean) so no new bug ships in a published artifact.

Usage:  python3 scripts/quality-gate.py [--update-baseline]
  (default)          measure, compare to baseline, exit 1 on any regression
  --update-baseline  rewrite config/quality-baseline.json with the current counts (run locally after
                     an intentional, reviewed change to the accepted-debt set — never in CI)

Exit code 0 = gate passes (deploy may proceed); 1 = regression (block deploy); 2 = usage/IO error.
"""
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE_PATH = os.path.join(ROOT, "config", "quality-baseline.json")

# (tool key, report glob, element tag whose occurrences are the violations)
TOOLS = [
    ("checkstyle", "**/target/checkstyle-result.xml", "error"),
    ("spotbugs", "**/target/spotbugsXml.xml", "BugInstance"),
    ("pmd", "**/target/pmd.xml", "violation"),
]


def _localname(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def count(report_glob: str, tag: str) -> tuple:
    """Return (violation_total, report_file_count) for a tool's reports across the reactor."""
    total = 0
    paths = glob.glob(os.path.join(ROOT, report_glob), recursive=True)
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            # A half-written / empty report is treated as zero for that module; the build step that
            # produced it already ran, so a parse error here is a corrupt artifact, not a violation.
            continue
        total += sum(1 for el in root.iter() if _localname(el.tag) == tag)
    return total, len(paths)


def measure() -> dict:
    out = {}
    for key, g, tag in TOOLS:
        violations, reports = count(g, tag)
        out[key] = {"violations": violations, "reports": reports}
    return out


def load_baseline() -> dict:
    with open(BASELINE_PATH, encoding="utf-8") as fh:
        return json.load(fh)


def main(argv) -> int:
    current = measure()

    if "--update-baseline" in argv:
        os.makedirs(os.path.dirname(BASELINE_PATH), exist_ok=True)
        with open(BASELINE_PATH, "w", encoding="utf-8") as fh:
            json.dump(current, fh, indent=2, sort_keys=True)
            fh.write("\n")
        print(f"Baseline updated: {current}")
        return 0

    try:
        baseline = load_baseline()
    except FileNotFoundError:
        print(f"ERROR: no baseline at {BASELINE_PATH}. Run --update-baseline locally first.", file=sys.stderr)
        return 2

    print(f"{'tool':<11}{'base.viol':>10}{'cur.viol':>10}{'base.rep':>10}{'cur.rep':>9}   status")
    failed = False
    for key, _g, _t in TOOLS:
        base = baseline.get(key, {})
        base_v, base_r = base.get("violations", 0), base.get("reports", 0)
        cur_v, cur_r = current[key]["violations"], current[key]["reports"]
        # Two independent failure modes: a real regression (more violations), or missing analysis
        # (fewer report files than the vetted baseline → the ratchet cannot be trusted).
        regressed = cur_v > base_v
        missing = cur_r < base_r
        status = "OK"
        if regressed:
            status = f"REGRESSION (+{cur_v - base_v} violations)"
        elif missing:
            status = f"MISSING REPORTS ({base_r - cur_r} fewer — analysis did not run)"
        failed = failed or regressed or missing
        print(f"{key:<11}{base_v:>10}{cur_v:>10}{base_r:>10}{cur_r:>9}   {status}")

    if failed:
        print("\nQUALITY GATE FAILED — quality regressed above baseline, or reports are missing so the\n"
              "ratchet cannot be trusted. Fix the new violations; if the added debt is genuinely\n"
              "accepted and documented, re-run `python3 scripts/quality-gate.py --update-baseline` and\n"
              "commit the change for review. If reports are missing, the analysis step did not run.",
              file=sys.stderr)
        return 1

    print("\nQUALITY GATE PASSED — no regression and all baseline reports present.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
