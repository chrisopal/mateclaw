#!/usr/bin/env python3
"""Quality entry point. Dev is quick; commit/ci run applicable real toolchains.

No --skip-* flag, automatic baseline rewrite, git add/stash/reset, --fix, or fake
success on missing tools. Exit 0 PASS, 1 FAIL, 2 BLOCKED. Logs are local artifacts.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
from gate import GateError, evaluate, git, load_policy, repo_root, resolve_commit, snapshot

HERE = Path(__file__).resolve().parent
CONTROL_ROOT = HERE.parents[1]


def changed_paths(repo: Path, base: str, mode: str) -> list[str]:
    if mode == "commit":
        raw = git(repo, "diff", "--cached", "--name-only", "-z", base, "--")
    elif mode in {"ci", "push"}:
        raw = git(repo, "diff", "--name-only", "-z", base, "HEAD", "--")
    else:
        raw = git(repo, "diff", "--name-only", "-z", base, "--")
        raw += git(repo, "ls-files", "--others", "--exclude-standard", "-z")
    return sorted({p.decode("utf-8") for p in raw.split(b"\0") if p})


def classify(paths: list[str]) -> dict[str, bool]:
    control = any(p.startswith(("scripts/quality/", ".quality/", ".github/", ".githooks/", ".agents/"))
                  or Path(p).name in {"AGENTS.md", "AGENTS.override.md", ".gitignore", ".gitattributes"}
                  for p in paths)
    java = control or any(p == "pom.xml" or p.startswith(".mvn/") or (p.startswith("mateclaw-") and not p.startswith(("mateclaw-ui/", "mateclaw-desktop/", "mateclaw-webchat/"))) for p in paths)
    # Unknown executable/build areas are conservative, never a silent docs-only pass.
    extra = any(p.startswith(("scripts/", "docker/")) or p in {"Dockerfile", "docker-compose.yml"} for p in paths)
    java = java or extra
    ui = control or java or any(p.startswith(("mateclaw-ui/", "scripts/check-snowflake")) for p in paths)
    return {"java": java, "ui": ui, "control": control}


def ensure_exact_worktree(repo: Path, mode: str) -> str:
    """Full tools run from disk, so refuse partial staging rather than test wrong bytes."""
    if git(repo, "diff", "--name-only", "-z", "--"):
        raise GateError("PARTIAL_STAGING: tracked worktree differs from index. Stage the intended content or use a clean worktree; no stash is performed.")
    if mode in {"ci", "push"} and git(repo, "diff", "--cached", "--name-only", "-z", "HEAD", "--"):
        raise GateError("CI_DIRTY_INDEX: checkout differs from HEAD")
    untracked = [p for p in git(repo, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0") if p]
    if untracked:
        names = ", ".join(p.decode("utf-8", "replace") for p in untracked[:10])
        raise GateError(f"UNTRACKED_INPUT: {names}. Keep test input tracked and generated files outside/ignored.")
    return git(repo, "write-tree").decode().strip()


def clean_test_env() -> dict[str, str]:
    result = {}
    for key, value in os.environ.items():
        if re.search(r"TOKEN|SECRET|PASSWORD|API_KEY|CREDENTIAL", key, re.I):
            continue
        if key.startswith(("SPRING_", "MATECLAW_", "GIT_")) or key in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_ARGS", "MAVEN_OPTS", "_JAVA_OPTIONS", "NODE_OPTIONS", "PYTHONPATH"}:
            continue
        result[key] = value
    result.update(CI="true", PYTHONDONTWRITEBYTECODE="1")
    return result


def maven_java_major(env: dict[str, str]) -> int | None:
    if not shutil.which("mvn", path=env.get("PATH")):
        return None
    try:
        proc = subprocess.run(["mvn", "-v"], env=env, capture_output=True, text=True,
                              timeout=20, check=False)
    except subprocess.TimeoutExpired as exc:
        raise GateError("MAVEN_VERSION_TIMEOUT") from exc
    match = re.search(r"^Java version: (\d+)", proc.stdout, re.M)
    if proc.returncode or not match:
        raise GateError("MAVEN_JDK_UNDETERMINED: mvn -v did not report a Java version")
    return int(match.group(1))


def java21_env(env: dict[str, str]) -> dict[str, str]:
    """Use the project's Java 21 for Maven, including macOS hook processes."""
    if maven_java_major(env) in {None, 21}:
        return env
    if sys.platform == "darwin" and not env.get("JAVA_HOME"):
        try:
            proc = subprocess.run(["/usr/libexec/java_home", "-v", "21"], env=env,
                                  capture_output=True, text=True, timeout=20, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise GateError("MAVEN_JDK21_REQUIRED: set JAVA_HOME to a Java 21 JDK") from exc
        if proc.returncode == 0 and proc.stdout.strip():
            selected = {**env, "JAVA_HOME": proc.stdout.strip()}
            if maven_java_major(selected) == 21:
                return selected
    raise GateError("MAVEN_JDK21_REQUIRED: set JAVA_HOME to a Java 21 JDK")


def run_step(name: str, argv: list[str], cwd: Path, reports: Path, env: dict[str, str], timeout: int = 1800) -> dict:
    if not shutil.which(argv[0], path=env.get("PATH")):
        return {"name": name, "status": "BLOCKED", "reason": f"TOOL_MISSING: {argv[0]}", "command": argv}
    log = reports / f"{name}.log"
    started = time.monotonic()
    try:
        with log.open("wb") as handle:
            proc = subprocess.run(argv, cwd=cwd, env=env, stdout=handle, stderr=subprocess.STDOUT,
                                  timeout=timeout, check=False)
        status = "PASS" if proc.returncode == 0 else "FAIL"
        return {"name": name, "status": status, "exit_code": proc.returncode, "command": argv,
                "log": str(log), "log_sha256": hashlib.sha256(log.read_bytes()).hexdigest(),
                "elapsed_seconds": round(time.monotonic() - started, 3)}
    except subprocess.TimeoutExpired:
        return {"name": name, "status": "BLOCKED", "reason": "COMMAND_TIMEOUT", "command": argv, "log": str(log)}


def java_test_count(repo: Path) -> int:
    executed = 0
    for path in repo.glob("**/target/*-reports/TEST-*.xml"):
        if path.parent.name not in {"surefire-reports", "failsafe-reports"}:
            continue
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            if int(suite.get("failures", 0)) or int(suite.get("errors", 0)):
                raise GateError("JAVA_TEST_FAILURE_REPORT")
            executed += int(suite.get("tests", 0)) - int(suite.get("skipped", 0))
    if executed <= 0:
        raise GateError("JAVA_ZERO_TESTS: no executed Surefire/Failsafe tests")
    return executed


def vet_maven_config(repo: Path) -> None:
    config = repo / ".mvn/maven.config"
    if config.exists() and re.search(r"skipTests|maven\.test\.skip|maven\.test\.failure\.ignore|skipITs", config.read_text()):
        raise GateError("MAVEN_SKIP_CONFIG: remove implicit test-skipping flags")


def formatter_configured(repo: Path) -> bool:
    pom = repo / "pom.xml"
    if not pom.exists():
        return False
    root = ET.parse(pom).getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    return any(p.findtext("m:artifactId", namespaces=ns) == "spotless-maven-plugin"
               for p in root.findall("m:build/m:plugins/m:plugin", ns))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--mode", choices=["dev", "commit", "ci", "push", "audit"], default="dev")
    parser.add_argument("--base", help="CI: required immutable base SHA; local: defaults HEAD")
    parser.add_argument("--report-dir", type=Path)
    args = parser.parse_args()
    reports = (args.report_dir or Path(tempfile.mkdtemp(prefix="mateclaw-quality-"))).resolve()
    reports.mkdir(parents=True, exist_ok=True)
    report = {"schema_version": 1, "mode": args.mode, "started_at": datetime.now(timezone.utc).isoformat(),
              "steps": [], "application_acceptance": "NOT_RUN", "report_dir": str(reports)}
    steps = report["steps"]
    try:
        repo = repo_root(args.repo)
        if args.mode == "ci" and not args.base:
            raise GateError("CI_BASE_REQUIRED: never silently compare only HEAD~1")
        base = resolve_commit(repo, args.base or "HEAD")
        policy_file = CONTROL_ROOT / ".quality/policy.json"
        policy = load_policy(policy_file)
        report.update(base_commit=base, policy_sha256=hashlib.sha256(policy_file.read_bytes()).hexdigest(),
                      runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
        if args.mode == "ci":
            # In the installed workflow this script is run from the separate BASE checkout.
            control_head = resolve_commit(CONTROL_ROOT, "HEAD")
            if control_head != base:
                raise GateError("TRUSTED_POLICY_MISMATCH: CI runner must be loaded from the base checkout")
        if args.mode in {"commit", "ci", "push"}:
            before_tree = ensure_exact_worktree(repo, args.mode)
        else:
            before_tree = ""
        before, _ = snapshot(repo, "base", base)
        snap_mode = {"dev": "worktree", "audit": "worktree", "commit": "staged", "ci": "head", "push": "head"}[args.mode]
        after, identity = snapshot(repo, snap_mode)
        report["target_identity"] = identity
        analysis = evaluate(before, after, policy)
        (reports / "architecture.json").write_text(json.dumps(analysis, indent=2, ensure_ascii=False) + "\n")
        steps.append({"name": "architecture-ratchet", "status": analysis["status"],
                      "new_violations": len(analysis["new_violations"]), "existing_violations": analysis["existing_violation_count"]})
        paths = changed_paths(repo, base, args.mode)
        if args.mode in {"commit", "ci", "push"} and any(p.startswith(("mateclaw-desktop/", "mateclaw-webchat/")) for p in paths):
            raise GateError("UNMAPPED_COMPONENT: desktop/webchat require a reviewed test-plan adapter; this gate does not silently mark them tested")
        plan = classify(paths)
        report.update(changed_files=paths, plan=plan)
        env = clean_test_env()
        step = run_step("guard-self-tests", [sys.executable, "-m", "unittest", "discover", "-s", str(HERE / "tests"), "-v"], CONTROL_ROOT, reports, env, 120)
        if step["status"] == "PASS":
            counts = re.findall(r"Ran (\d+) tests?", Path(step["log"]).read_text(errors="replace"))
            if not counts or int(counts[-1]) < 1:
                step.update(status="FAIL", reason="GUARD_ZERO_TESTS")
        steps.append(step)
        if plan["control"] and repo != CONTROL_ROOT.resolve():
            candidate_tests = repo / "scripts/quality/tests"
            if not list(candidate_tests.glob("test_*.py")):
                steps.append({"name": "candidate-guard-self-tests", "status": "BLOCKED", "reason": "CANDIDATE_GUARD_TESTS_MISSING"})
            else:
                step = run_step("candidate-guard-self-tests", [sys.executable, "-m", "unittest", "discover", "-s", str(candidate_tests), "-v"], repo, reports, env, 120)
                if step["status"] == "PASS":
                    counts = re.findall(r"Ran (\d+) tests?", Path(step["log"]).read_text(errors="replace"))
                    if not counts or int(counts[-1]) < 1:
                        step.update(status="FAIL", reason="CANDIDATE_GUARD_ZERO_TESTS")
                steps.append(step)
        if args.mode in {"dev", "audit"}:
            steps.append({"name": "application-toolchains", "status": "NOT_RUN", "reason": "Quick scan only. commit/ci is required before submission."})
        elif any(s["status"] != "PASS" for s in steps):
            steps.append({"name": "application-toolchains", "status": "BLOCKED", "reason": "Fix static gate before running build/test toolchains"})
        else:
            if plan["java"]:
                vet_maven_config(repo)
                java_env = java21_env(env)
                if not formatter_configured(repo):
                    steps.append({"name": "java-format", "status": "BLOCKED", "reason": "TOOLING_BOOTSTRAP_REQUIRED: merge integration/pom-format-plugin.xml into root build/plugins; do not skip formatter"})
                else:
                    steps.append(run_step("java-format", ["mvn", "-B", f"-Dquality.base={base}", "spotless:check"], repo, reports, java_env))
                with tempfile.TemporaryDirectory(prefix="mateclaw-test-skills-") as skills:
                    step = run_step("java-tests", ["mvn", "-B", "-Dmaven.compiler.proc=full", f"-Dmateclaw.skill.workspace.root={skills}", "clean", "verify"], repo, reports, java_env)
                    if step["status"] == "PASS":
                        try:
                            step["executed_tests"] = java_test_count(repo)
                        except (GateError, ET.ParseError) as exc:
                            step.update(status="FAIL", reason=str(exc))
                    steps.append(step)
            else:
                steps.append({"name": "java-toolchain", "status": "NOT_APPLICABLE", "reason": "No backend/control-plane changes"})
            if plan["ui"]:
                ui = repo / "mateclaw-ui"
                if not ui.is_dir():
                    raise GateError("UI_DIRECTORY_MISSING")
                package = json.loads((ui / "package.json").read_text())
                if "prettier" not in package.get("devDependencies", {}):
                    steps.append({"name": "ui-format", "status": "BLOCKED", "reason": "TOOLING_BOOTSTRAP_REQUIRED: add the pinned formatter and regenerate/commit pnpm lockfile"})
                else:
                    changed_ui = [p[len("mateclaw-ui/"):] for p in paths if p.startswith("mateclaw-ui/") and (repo / p).is_file() and Path(p).suffix in {".vue", ".ts", ".tsx", ".js", ".mjs", ".css", ".json"}]
                    if changed_ui:
                        steps.append(run_step("ui-format", ["pnpm", "exec", "prettier", "--check", "--config", str(CONTROL_ROOT / ".quality/prettier.json"), *changed_ui], ui, reports, env))
                    else:
                        steps.append({"name": "ui-format", "status": "NOT_APPLICABLE", "reason": "No changed frontend formatter-supported files"})
                changed_lint = [p[len("mateclaw-ui/"):] for p in paths if p.startswith("mateclaw-ui/src/") and (repo / p).is_file() and Path(p).suffix in {".vue", ".ts", ".tsx", ".js", ".mjs"}]
                if changed_lint:
                    steps.append(run_step("ui-lint", ["pnpm", "exec", "eslint", "--max-warnings=0", *changed_lint], ui, reports, env))
                else:
                    steps.append({"name": "ui-lint", "status": "NOT_APPLICABLE", "reason": "No changed frontend lint targets"})
                steps.append(run_step("ui-typecheck", ["pnpm", "exec", "vue-tsc", "--noEmit"], ui, reports, env))
                steps.append(run_step("id-precision", ["bash", str(repo / "scripts/check-snowflake-precision.sh")], repo, reports, env))
                vitest_report = reports / "vitest.json"
                step = run_step("ui-vitest", ["pnpm", "exec", "vitest", "run", "--reporter=json", f"--outputFile={vitest_report}"], ui, reports, env)
                if step["status"] == "PASS":
                    try:
                        vt = json.loads(vitest_report.read_text())
                        count = int(vt.get("numPassedTests", 0))
                        if count < 1 or vt.get("numFailedTests", 0) or not vt.get("success", False):
                            raise GateError("VITEST_ZERO_OR_FAILED_TESTS")
                        step["executed_tests"] = count
                    except (OSError, ValueError, GateError) as exc:
                        step.update(status="FAIL", reason=str(exc))
                steps.append(step)
                legacy = sorted(str(p.relative_to(ui)) for p in (ui / "test").rglob("*") if p.is_file() and re.search(r"\.(?:test|spec)\.(?:ts|js|mjs)$", p.name))
                if not legacy:
                    steps.append({"name": "ui-node-tests", "status": "BLOCKED", "reason": "LEGACY_TESTS_MISSING: do not silently drop the existing Node test convention"})
                else:
                    step = run_step("ui-node-tests", ["node", "--experimental-strip-types", "--test", "--test-reporter=tap", *legacy], ui, reports, env)
                    if step["status"] == "PASS":
                        text = Path(step["log"]).read_text(errors="replace")
                        matches = re.findall(r"^# tests (\d+)\s*$", text, re.M)
                        if not matches or int(matches[-1]) < 1:
                            step.update(status="FAIL", reason="NODE_ZERO_TESTS")
                    steps.append(step)
                for theme in ("enterprise", "classic"):
                    steps.append(run_step(f"ui-build-{theme}", ["pnpm", "build", "--mode", theme], ui, reports, env))
            else:
                steps.append({"name": "ui-toolchain", "status": "NOT_APPLICABLE", "reason": "No frontend/backend/control changes"})
            after_tree = ensure_exact_worktree(repo, args.mode)
            if after_tree != before_tree:
                raise GateError("INDEX_CHANGED_DURING_CHECK: rerun; no stale PASS receipt")
        if any(s["status"] == "FAIL" for s in steps):
            report["status"], code = "FAIL", 1
        elif any(s["status"] == "BLOCKED" for s in steps):
            report["status"], code = "BLOCKED", 2
        else:
            report["status"], code = ("SCAN_PASS" if args.mode in {"dev", "audit"} else "PASS"), 0
        report["submission_ready"] = report["status"] == "PASS" and args.mode in {"commit", "ci", "push"}
    except (GateError, OSError, ValueError, ET.ParseError) as exc:
        report.update(status="BLOCKED", error=str(exc), submission_ready=False)
        code = 2
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    (reports / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "report": str(reports / 'report.json'),
                      "submission_ready": report["submission_ready"], "error": report.get("error")}, ensure_ascii=False))
    for step in steps:
        print(f"{step['status']}: {step['name']} {step.get('reason', '')}")
    return code


if __name__ == "__main__":
    sys.exit(main())
