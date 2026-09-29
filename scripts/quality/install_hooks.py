#!/usr/bin/env python3
"""Install this repository's hooks explicitly; never overwrite an existing hook manager."""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import subprocess
import sys
sys.dont_write_bytecode = True
from gate import GateError, git, repo_root


def install(repo: Path, apply: bool) -> dict:
    repo = repo_root(repo)
    result = subprocess.run(["git", "-C", str(repo), "config", "--get", "core.hooksPath"], capture_output=True, text=True)
    current = result.stdout.strip()
    if current and current != ".githooks":
        raise GateError(f"EXISTING_HOOK_MANAGER: {current}; integrate manually, do not overwrite")
    required = [repo / ".githooks" / name for name in ("pre-commit", "pre-push")]
    if any(not p.is_file() or p.is_symlink() for p in required):
        raise GateError("HOOK_FILES_MISSING_OR_SYMLINK")
    worktrees = git(repo, "worktree", "list", "--porcelain").decode().count("worktree ")
    scope = "--local"
    if worktrees > 1:
        ext = subprocess.run(["git", "-C", str(repo), "config", "--bool", "--get", "extensions.worktreeConfig"], capture_output=True, text=True)
        if ext.stdout.strip() != "true":
            raise GateError("MULTI_WORKTREE_REVIEW_REQUIRED: enabling shared hooks can break other worktrees. Ask the maintainer to review worktree-local configuration.")
        scope = "--worktree"
    if not current:
        hooks = Path(git(repo, "rev-parse", "--git-path", "hooks").decode().strip())
        if not hooks.is_absolute():
            hooks = repo / hooks
        # Existing hooks of ANY lifecycle must not be disabled by changing hooksPath.
        if hooks.exists():
            active = [p.name for p in hooks.iterdir() if p.is_file() and not p.name.endswith(".sample") and os.access(p, os.X_OK)]
            if active:
                raise GateError("EXISTING_HOOKS: " + ", ".join(active))
    if apply:
        for p in required:
            p.chmod(p.stat().st_mode | 0o111)
        git(repo, "config", scope, "core.hooksPath", ".githooks")
    return {"status": "INSTALLED" if apply else "DRY_RUN", "config_scope": scope,
            "hook_path": ".githooks", "note": "Local hooks are bypassable; required CI and review policy must also be enabled."}


def main() -> int:
    import json
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo", type=Path, default=Path.cwd())
    p.add_argument("--apply", action="store_true")
    args = p.parse_args()
    try:
        print(json.dumps(install(args.repo, args.apply), ensure_ascii=False))
        return 0
    except (GateError, OSError) as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
