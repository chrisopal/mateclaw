#!/usr/bin/env python3
"""Run the opt-in W3C OWL 2 harness through the real Maven test class.

This launcher performs no network access. The Java harness reads only the
pinned archive and index under docs/validation/semantic-owl-w3c and invokes
the existing OwlDocumentAdapter and HermitReasoningWorker APIs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VALIDATION = ROOT / "docs" / "validation" / "semantic-owl-w3c"
ARCHIVE = VALIDATION / "all.rdf"
SOURCE = VALIDATION / "source.json"
INDEX = VALIDATION / "case-index.json"


def verify_pinned_inputs() -> tuple[str, int]:
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    index = json.loads(INDEX.read_text(encoding="utf-8"))
    digest = hashlib.sha256(ARCHIVE.read_bytes()).hexdigest()
    if digest != source["sha256"] or digest != index["sourceSha256"]:
        raise SystemExit("W3C archive digest does not match source.json and case-index.json")
    selected = [case for case in index["cases"] if case["selection"] == "selected"]
    if len(selected) != 266:
        raise SystemExit(f"expected 266 selected cases, found {len(selected)}")
    if any(case.get("execution") != "NOT_RUN" for case in selected):
        raise SystemExit("case-index execution state must remain NOT_RUN before a harness run")
    return digest, len(selected)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, help="run only the first N sorted cases")
    parser.add_argument("--timeout-seconds", type=int, default=15)
    parser.add_argument("--memory-mb", type=int, default=512)
    parser.add_argument("--results", type=Path, default=VALIDATION / "results.json")
    parser.add_argument("--maven", default=os.environ.get("MAVEN", "mvn"))
    args = parser.parse_args()
    digest, selected = verify_pinned_inputs()
    if args.limit is not None and not 1 <= args.limit <= selected:
        parser.error(f"--limit must be between 1 and {selected}")
    if args.timeout_seconds < 1:
        parser.error("--timeout-seconds must be positive")
    if args.memory_mb < 64:
        parser.error("--memory-mb must be at least 64")

    results = args.results if args.results.is_absolute() else ROOT / args.results
    command = [
        args.maven,
        "-pl",
        "mateclaw-semantic-owl",
        "-am",
        "-Dsemantic.owl.w3c=true",
        f"-Dsemantic.owl.w3c.timeoutSeconds={args.timeout_seconds}",
        f"-Dsemantic.owl.w3c.memoryMb={args.memory_mb}",
        f"-Dsemantic.owl.w3c.results={results}",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dtest=W3cOwl2DlHarnessTest",
    ]
    if args.limit is not None:
        command.append(f"-Dsemantic.owl.w3c.limit={args.limit}")
    command.append("test")
    print(f"Pinned archive: {digest} ({selected} selected cases)")
    print("Executing offline W3C harness; no archive/index mutation is performed.")
    completed = subprocess.run(command, cwd=ROOT)
    if completed.returncode == 0 and results.is_file():
        print(f"Result report: {results}")
    return completed.returncode


if __name__ == "__main__":
    sys.exit(main())
