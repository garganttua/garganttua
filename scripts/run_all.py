#!/usr/bin/env python3
"""Run the README auto-block generators (doc generation, wired into the reactor build).

Globs the doc-generation scripts in this directory and runs each. Scripts that are NOT
README generators — notably `quality-gate.py`, which is a standalone release-only CI job that
requires `-Pquality` reports absent from a normal build — are excluded; running them here would
fail every doc-generation build.
"""
import subprocess
import glob

# Scripts in this directory that are not README generators and must not run during doc generation.
NOT_DOC_GENERATORS = {"run_all.py", "quality-gate.py"}

for script in glob.glob("*.py"):
    if script not in NOT_DOC_GENERATORS:
        subprocess.run(["python3", script], check=True)