"""Source-freeze guard regressions; not database or Flyway runtime acceptance."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from gate import DEFAULT_POLICY, evaluate, git, invariant_changes, snapshot

MANIFEST = ".quality/frozen-migrations.json"
JAVA = "mateclaw-server/src/main/java/"
HELPER = JAVA + "vip/mate/presales/migration/AlgorithmV1.java"


def entries(version=218):
    return {JAVA + f"db/migration/{d}/V{version}__backfill.java": "class Backfill {}\n"
            for d in ("h2", "mysql", "kingbase")}


def frozen(files):
    return json.dumps({"version": 1, "files": {
        p: hashlib.sha256(t.encode("utf-8")).hexdigest() for p, t in files.items()
    }}, sort_keys=True) + "\n"


def declared():
    files = entries() | {HELPER: "class AlgorithmV1 {}\n"}
    return files | {MANIFEST: frozen(files)}


def violations(before, after):
    return {f.rule for f in invariant_changes(before, after)}


class JavaMigrationTest(unittest.TestCase):
    def test_java_edit_delete_rename_blocked(self):
        before = declared()
        path = next(iter(entries()))
        for operation in ("edit", "delete", "rename"):
            with self.subTest(operation=operation):
                after = dict(before)
                if operation == "edit":
                    after[path] = "class Changed {}\n"
                else:
                    del after[path]
                    if operation == "rename":
                        after[path.replace("V218", "V219")] = before[path]
                self.assertIn("DB-001", violations(before, after))

    def test_one_java_dialect_requires_other_two(self):
        p = JAVA + "db/migration/mysql/V219__backfill.java"
        findings = invariant_changes({}, {p: "class X {}\n"})
        self.assertEqual(sum(f.rule == "DB-002" for f in findings), 2)

    def test_java_entries_require_freeze_declaration(self):
        self.assertIn("DB-003", violations({}, entries()))

    def test_shared_helper_edit_delete_or_rename_blocked(self):
        before = declared()
        for operation in ("edit", "delete", "rename"):
            with self.subTest(operation=operation):
                after = dict(before)
                if operation == "edit":
                    after[HELPER] = "class Changed {}\n"
                else:
                    del after[HELPER]
                    if operation == "rename":
                        after[HELPER.replace("V1", "V2")] = before[HELPER]
                self.assertIn("DB-004", violations(before, after))

    def test_rehashing_changed_helper_does_not_bypass_base(self):
        before = declared()
        after = dict(before)
        after[HELPER] = "class Changed {}\n"
        after[MANIFEST] = frozen({p: t for p, t in after.items() if p != MANIFEST})
        self.assertIn("DB-004", violations(before, after))

    def test_removing_or_rewriting_base_declarations_blocked(self):
        before = declared()
        for operation in ("remove_file", "remove_manifest", "rewrite_digest"):
            with self.subTest(operation=operation):
                after = dict(before)
                data = json.loads(before[MANIFEST])
                if operation == "remove_manifest":
                    del after[MANIFEST]
                else:
                    if operation == "remove_file":
                        del data["files"][HELPER]
                    else:
                        data["files"][HELPER] = "0" * 64
                    after[MANIFEST] = json.dumps(data)
                self.assertIn("DB-004", violations(before, after))

    def test_deleting_all_java_and_manifest_does_not_disable_freeze(self):
        self.assertIn("DB-004", violations(declared(), {}))

    def test_initial_declaration_cannot_freeze_modified_existing_helper(self):
        after = declared()
        before = {HELPER: "class Published {}\n"}
        self.assertIn("DB-004", violations(before, after))

    def test_unchanged_published_sources_allowed(self):
        before = declared()
        self.assertEqual(violations(before, before), set())

    def test_complete_new_version_with_new_helper_allowed(self):
        before = declared()
        after = before | entries(219) | {HELPER.replace("V1", "V2"): "class AlgorithmV2 {}\n"}
        after[MANIFEST] = frozen({p: t for p, t in after.items() if p != MANIFEST})
        self.assertEqual(violations(before, after), set())

    def test_initial_declaration_of_unchanged_existing_helper_allowed(self):
        after = declared()
        self.assertEqual(violations({HELPER: after[HELPER]}, after), set())

    def test_bad_manifest_rejected(self):
        valid = json.loads(declared()[MANIFEST])
        mutations = ["not json", "[]", "null", '{"version":true,"files":{}}',
                     '{"version":1,"version":1,"files":{}}']
        for data in ({"version": 2, "files": valid["files"]},
                     {"version": 1, "files": []},
                     {"version": 1, "files": {}},
                     valid | {"unknown": "field"},
                     {"version": 1, "files": {HELPER: "invalid"}},
                     {"version": 1, "files": {"../" + HELPER: "0" * 64}},
                     {"version": 1, "files": {HELPER.replace("/vip/", "/./vip/"): "0" * 64}},
                     {"version": 1, "files": {"scripts/quality/gate.py": "0" * 64}}):
            mutations.append(json.dumps(data))
        for raw in mutations:
            with self.subTest(manifest=raw):
                self.assertIn("DB-003", violations({}, declared() | {MANIFEST: raw}))

    def test_missing_source_and_digest_mismatch_rejected(self):
        for operation in ("missing", "mismatch"):
            with self.subTest(operation=operation):
                after = declared()
                if operation == "missing":
                    del after[HELPER]
                else:
                    after[HELPER] = "class Different {}\n"
                self.assertIn("DB-004", violations({}, after))

    def test_entry_omitted_from_manifest_blocked(self):
        after = declared()
        files = json.loads(after[MANIFEST])
        del files["files"][next(iter(entries()))]
        after[MANIFEST] = json.dumps(files)
        self.assertIn("DB-003", violations({}, after))

    def test_invalid_base_cannot_be_repaired_silently(self):
        after = declared()
        before = after | {MANIFEST: "not json"}
        self.assertIn("DB-003", violations(before, after))

    def test_violation_is_not_ratchet_grandfathered(self):
        after = declared()
        after[HELPER] = "class Changed {}\n"
        self.assertEqual(evaluate(after, after, DEFAULT_POLICY)["status"], "FAIL")


class JavaMigrationGitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mateclaw-java-freeze-")
        self.repo = Path(self.temp.name)
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.name", "Guard Fixture")
        git(self.repo, "config", "user.email", "guard@example.invalid")
        for p, t in declared().items():
            self.write(p, t.encode())
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-qm", "Freeze synthetic published sources")

    def tearDown(self):
        self.temp.cleanup()

    def write(self, path, content):
        p = self.repo / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)

    def cli(self, mode):
        return subprocess.run([sys.executable, "-B", str(HERE / "gate.py"),
                               "--repo", str(self.repo), "--mode", mode, "--base", "HEAD"],
                              capture_output=True, text=True)

    def test_real_cli_blocks_rehashed_helper_in_worktree_and_index(self):
        after = declared() | {HELPER: "class Changed {}\n"}
        after[MANIFEST] = frozen({p: t for p, t in after.items() if p != MANIFEST})
        for p, t in after.items():
            self.write(p, t.encode())
        git(self.repo, "add", ".")
        for mode in ("worktree", "staged"):
            with self.subTest(mode=mode):
                result = self.cli(mode)
                self.assertEqual(result.returncode, 1)
                self.assertIn("DB-004", {f["rule"] for f in json.loads(result.stdout)["new_violations"]})

    def test_real_cli_unchanged_source_passes(self):
        result = self.cli("worktree")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_worktree_snapshot_preserves_git_utf8_crlf_bytes(self):
        self.write(HELPER, b"class AlgorithmV1 {}\r\n")
        git(self.repo, "add", HELPER)
        staged, _ = snapshot(self.repo, "staged")
        working, _ = snapshot(self.repo, "worktree")
        self.assertEqual(working[HELPER], staged[HELPER])


if __name__ == "__main__":
    unittest.main()
