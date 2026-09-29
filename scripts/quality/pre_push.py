#!/usr/bin/env python3
"""Check the full pushed range; fail closed on unsupported multi-head pushes."""
from __future__ import annotations
from pathlib import Path
import subprocess
import sys
sys.dont_write_bytecode = True
from gate import GateError, git, repo_root, resolve_commit


def main() -> int:
    try:
        repo = repo_root(Path.cwd())
        head = resolve_commit(repo, "HEAD")
        lines = [line.split() for line in sys.stdin.read().splitlines() if line.strip()]
        if not lines:
            raise GateError("PUSH_REFS_MISSING")
        bases = []
        for row in lines:
            if len(row) != 4:
                raise GateError("PUSH_REF_FORMAT")
            local_ref, local_oid, remote_ref, remote_oid = row
            if set(local_oid) == {"0"}:
                raise GateError("REMOTE_DELETION_REQUIRES_MANUAL_REVIEW: not allowed through the engineering hook")
            if local_oid != head or not remote_ref.startswith("refs/heads/"):
                raise GateError("PUSH_HEAD_MISMATCH: check out the exact branch being pushed and rerun")
            if set(remote_oid) == {"0"}:
                base = git(repo, "merge-base", head, resolve_commit(repo, "origin/dev")).decode().strip()
            else:
                # Missing remote object is not an empty range. Fetch it explicitly and retry.
                base = resolve_commit(repo, remote_oid)
                ancestor = subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", base, head], check=False)
                if ancestor.returncode:
                    raise GateError("NON_FAST_FORWARD_REQUIRES_REVIEW")
            bases.append(base)
        for base in sorted(set(bases)):
            code = subprocess.run([sys.executable, str(Path(__file__).with_name("verify.py")), "--repo", str(repo), "--mode", "push", "--base", base], check=False).returncode
            if code:
                return code
        return 0
    except (GateError, OSError) as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
