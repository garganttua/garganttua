#!/usr/bin/env python3
"""Run the README auto-block generators (doc generation, wired into the reactor build).

Globs the doc-generation scripts in this directory and runs each. Scripts that are NOT
README generators — notably `quality-gate.py`, which is a standalone release-only CI job that
requires `-Pquality` reports absent from a normal build — are excluded; running them here would
fail every doc-generation build.
"""
import glob
import os
import subprocess

# Scripts in this directory that are not README generators and must not run during doc generation.
NOT_DOC_GENERATORS = {"run_all.py", "quality-gate.py"}

# Resolved from THIS file, not the current directory: the documented command
# (`python3 scripts/run_all.py`, run from the repository root) used to glob an empty
# directory and silently regenerate nothing.
HERE = os.path.dirname(os.path.abspath(__file__))

for script in sorted(glob.glob(os.path.join(HERE, "*.py"))):
    if os.path.basename(script) not in NOT_DOC_GENERATORS:
        subprocess.run(["python3", script], check=True, cwd=HERE)