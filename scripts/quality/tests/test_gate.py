"""Offline tests for guard behavior; NOT tests of the MateClaw application."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from gate import (DEFAULT_POLICY, GateError, eligible, evaluate, git, invariant_changes,
                  load_policy, masked, resolve_commit, scan_file, snapshot)
from verify import (classify, clean_test_env, ensure_exact_worktree, java21_env, java_test_count,
                    run_step, vet_maven_config)
from install_hooks import install

SERVER = "mateclaw-server/src/main/java/vip/mate/"
UI = "mateclaw-ui/src/"


def rules(path, text):
    return {x.rule for x in scan_file(path, text)}


class RulesTest(unittest.TestCase):
    def test_core_concrete_import_blocked(self):
        self.assertTrue(eligible(SERVER + "agent/output/X.java"))
        self.assertIn("AR-001", rules(SERVER + "agent/X.java", "import vip.mate.presales.PresalesToolPolicy;"))

    def test_core_fully_qualified_field_blocked(self):
        self.assertIn("AR-001", rules(SERVER + "tool/X.java", "private vip.mate.delivery.DeliveryToolPolicy policy;"))

    def test_business_can_use_common_contract(self):
        self.assertNotIn("AR-001", rules(SERVER + "presales/X.java", "import vip.mate.agent.execution.ProjectToolPolicy;"))

    def test_comments_and_strings_not_java_dependencies(self):
        text = '// vip.mate.presales.Bad\nString x = "vip.mate.bidding.Bad";'
        self.assertNotIn("AR-001", rules(SERVER + "agent/X.java", text))

    def test_java_text_block_not_dependency(self):
        text = 'String x = """\nvip.mate.presales.Bad\n""";'
        self.assertNotIn("AR-001", rules(SERVER + "agent/X.java", text))

    def test_controller_jdbc_blocked(self):
        self.assertIn("AR-002", rules(SERVER + "bidding/BiddingController.java", "private JdbcTemplate jdbc;"))

    def test_annotated_nonstandard_controller_blocked(self):
        self.assertIn("AR-002", rules(SERVER + "bidding/Endpoint.java", "@RestController\nclass Endpoint { DataSource ds; }"))

    def test_controller_mapper_blocked(self):
        self.assertIn("AR-002", rules(SERVER + "presales/XController.java", "private FooMapper mapper;"))

    def test_controller_serializer_allowed(self):
        self.assertNotIn("AR-002", rules(SERVER + "presales/XController.java", "private ObjectMapper json;"))

    def test_repository_jdbc_allowed(self):
        self.assertNotIn("AR-002", rules(SERVER + "bidding/repository/X.java", "private JdbcTemplate jdbc;"))

    def test_shared_scope_helper_wrong_place_blocked(self):
        p = UI + "features/presales/api/x.ts"
        s = "import { scopedConfig } from '@/features/semantic/api/ontologyApi'"
        self.assertIn("AR-003", rules(p, s))

    def test_relative_cross_feature_import_blocked(self):
        self.assertIn("AR-003", rules(UI + "features/bidding/api/x.ts", "import { a } from '../../semantic/api/ontologyApi'"))

    def test_public_domain_contract_allowed(self):
        self.assertNotIn("AR-003", rules(UI + "features/bidding/api/x.ts", "import { a } from '@/features/presales/public'"))

    def test_same_domain_import_allowed(self):
        self.assertNotIn("AR-003", rules(UI + "features/bidding/api/x.ts", "import { a } from '../shared/types'"))

    def test_public_infrastructure_no_feature_import(self):
        self.assertIn("AR-003", rules(UI + "api/workspaceRequest.ts", "import { a } from '@/features/semantic/public'"))

    def test_dynamic_literal_import_blocked(self):
        self.assertIn("AR-003", rules(UI + "features/bidding/api/x.ts", "const p = import('@/features/semantic/api/ontologyApi')"))

    def test_presales_semantic_auth_dependency_blocked(self):
        self.assertIn("AR-004", rules(SERVER + "presales/Access.java", "import vip.mate.semantic.security.SemanticPrincipalResolver;"))

    def test_foreign_table_sql_in_business_blocked(self):
        self.assertIn("AR-005", rules(SERVER + "presales/Context.java", 'String q = "SELECT id FROM mate_wiki_raw_material";'))

    def test_integration_sql_is_not_this_rule(self):
        self.assertNotIn("AR-005", rules(SERVER + "presales/integration/Source.java", 'String q = "SELECT id FROM mate_wiki_raw_material";'))

    def test_any_in_contract_blocked(self):
        self.assertIn("TS-001", rules(UI + "features/presales/api/x.ts", "type Data = Record<string, any>"))

    def test_unknown_in_contract_allowed(self):
        self.assertNotIn("TS-001", rules(UI + "features/presales/api/x.ts", "type Data = Record<string, unknown>"))

    def test_any_in_user_text_not_type(self):
        self.assertNotIn("TS-001", rules(UI + "features/presales/api/x.ts", "const label = 'any'"))

    def test_vue_script_any_blocked(self):
        self.assertIn("TS-001", rules(UI + "features/presales/pages/X.vue", '<template>any</template>\n<script setup lang="ts">let x: any</script>'))

    def test_vue_template_any_not_type(self):
        self.assertNotIn("TS-001", rules(UI + "features/presales/pages/X.vue", '<template>any item</template>'))

    def test_inline_i18n_blocked(self):
        self.assertIn("UI-001", rules(UI + "features/bidding/pages/X.vue", "<template>{{ l('项目','Project') }}</template>"))

    def test_i18n_key_allowed(self):
        self.assertNotIn("UI-001", rules(UI + "features/bidding/pages/X.vue", "<template>{{ t('bidding.project') }}</template>"))

    def test_new_skipped_test_blocked(self):
        self.assertIn("TEST-001", rules(UI + "features/presales/__tests__/x.test.ts", "it.skip('test', () => {})"))

    def test_disabled_java_test_blocked(self):
        self.assertIn("TEST-001", rules("mateclaw-server/src/test/java/X.java", "@Disabled\nclass X {}"))

    def test_long_source_line_flagged(self):
        self.assertIn("STYLE-001", rules(SERVER + "bidding/X.java", "x" * 201))

    def test_mask_preserves_newlines(self):
        value = '/* a\nb */\n"x"\nclass A {}'
        self.assertEqual(masked(value).count("\n"), value.count("\n"))


class RatchetTest(unittest.TestCase):
    path = SERVER + "agent/X.java"
    code = "import vip.mate.presales.PresalesToolPolicy;\n"

    def test_existing_debt_reported_not_new(self):
        r = evaluate({self.path: self.code}, {self.path: self.code}, DEFAULT_POLICY)
        self.assertEqual(r["status"], "PASS")
        self.assertEqual(r["existing_violation_count"], 1)

    def test_new_dependency_fails(self):
        self.assertEqual(evaluate({}, {self.path: self.code}, DEFAULT_POLICY)["status"], "FAIL")

    def test_added_duplicate_occurrence_fails(self):
        r = evaluate({self.path: self.code}, {self.path: self.code * 2}, DEFAULT_POLICY)
        self.assertEqual(len(r["new_violations"]), 1)

    def test_rename_cannot_launder_debt(self):
        r = evaluate({self.path: self.code}, {self.path.replace("X", "Y"): self.code}, DEFAULT_POLICY)
        self.assertEqual(r["status"], "FAIL")

    def test_new_dependency_cannot_spend_removed_dependency_budget(self):
        r = evaluate({self.path: self.code}, {self.path: "import vip.mate.bidding.BiddingRepository;\n"}, DEFAULT_POLICY)
        self.assertEqual(r["status"], "FAIL")

    def test_whitespace_only_does_not_change_import_fingerprint(self):
        r = evaluate({self.path: self.code}, {self.path: "  " + self.code}, DEFAULT_POLICY)
        self.assertEqual(r["status"], "PASS")

    def test_sealed_scope_has_zero_tolerance(self):
        p = {"zero_tolerance": {"AR-001": [SERVER + "agent/"]}}
        self.assertEqual(evaluate({self.path: self.code}, {self.path: self.code}, p)["status"], "FAIL")

    def test_remediation_reduces_debt(self):
        r = evaluate({self.path: self.code}, {self.path: "class X {}\n"}, DEFAULT_POLICY)
        self.assertEqual(r["removed_fingerprint_count"], 1)

    def test_existing_migration_edit_fails(self):
        p = "mateclaw-server/src/main/resources/db/migration/mysql/V211__x.sql"
        self.assertTrue(any(f.rule == "DB-001" for f in invariant_changes({p: "old"}, {p: "new"})))

    def test_existing_migration_delete_fails(self):
        p = "mateclaw-server/src/main/resources/db/migration/h2/V211__x.sql"
        self.assertTrue(any(f.rule == "DB-001" for f in invariant_changes({p: "old"}, {})))

    def test_new_migration_requires_three_dialects(self):
        p = "mateclaw-server/src/main/resources/db/migration/mysql/V300__x.sql"
        self.assertEqual(len([f for f in invariant_changes({}, {p: "new"}) if f.rule == "DB-002"]), 2)

    def test_complete_new_migrations_allowed(self):
        files = {f"mateclaw-server/src/main/resources/db/migration/{d}/V300__x.sql": "dialect specific\n" for d in ("h2", "mysql", "kingbase")}
        self.assertFalse(invariant_changes({}, files))

    def test_markdown_line_break_allowed(self):
        self.assertFalse(invariant_changes({}, {"docs/readme.md": "paragraph  \n"}))

    def test_new_trailing_space_fails(self):
        self.assertTrue(invariant_changes({}, {"a.ts": "let x = 1  \n"}))


class GitFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="quality-fixture-")
        self.repo = Path(self.temp.name)
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.name", "Fixture")
        git(self.repo, "config", "user.email", "fixture@example.invalid")
        (self.repo / "README.md").write_text("fixture\n")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-qm", "fixture")

    def tearDown(self):
        self.temp.cleanup()

    def write(self, path, text):
        p = self.repo / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def test_read_actual_index_not_worktree(self):
        p = SERVER + "agent/X.java"
        self.write(p, "import vip.mate.presales.PresalesToolPolicy;\n")
        git(self.repo, "add", "--", p)
        self.write(p, "class X {}\n")
        staged, _ = snapshot(self.repo, "staged")
        working, _ = snapshot(self.repo, "worktree")
        self.assertIn("AR-001", rules(p, staged[p]))
        self.assertNotIn("AR-001", rules(p, working[p]))
        with self.assertRaisesRegex(GateError, "PARTIAL_STAGING"):
            ensure_exact_worktree(self.repo, "commit")

    def test_untracked_input_blocks_full_check(self):
        self.write("new.ts", "let x = 1\n")
        with self.assertRaisesRegex(GateError, "UNTRACKED_INPUT"):
            ensure_exact_worktree(self.repo, "commit")

    def test_clean_index_allowed(self):
        self.assertEqual(len(ensure_exact_worktree(self.repo, "commit")), 40)

    def test_missing_base_not_empty_success(self):
        with self.assertRaises(GateError):
            resolve_commit(self.repo, "does-not-exist")

    def test_ref_option_injection_rejected(self):
        with self.assertRaises(GateError):
            resolve_commit(self.repo, "--help")

    def test_symlink_source_not_followed(self):
        (self.repo / "x.ts").symlink_to("/etc/passwd")
        with self.assertRaisesRegex(GateError, "SYMLINK"):
            snapshot(self.repo, "worktree")

    def test_unicode_space_filename(self):
        self.write("文档 a.ts", "const value = 1\n")
        git(self.repo, "add", "--", "文档 a.ts")
        data, _ = snapshot(self.repo, "staged")
        self.assertIn("文档 a.ts", data)

    def test_existing_hook_manager_not_overwritten(self):
        git(self.repo, "config", "core.hooksPath", ".husky")
        with self.assertRaisesRegex(GateError, "EXISTING_HOOK_MANAGER"):
            install(self.repo, True)
        self.assertEqual(git(self.repo, "config", "--get", "core.hooksPath").strip(), b".husky")

    def test_hook_install_is_dry_by_default(self):
        for n in ("pre-commit", "pre-push"):
            self.write(".githooks/" + n, "#!/bin/sh\nexit 0\n")
        self.assertEqual(install(self.repo, False)["status"], "DRY_RUN")
        result = subprocess.run(["git", "-C", str(self.repo), "config", "--get", "core.hooksPath"], capture_output=True)
        self.assertNotEqual(result.returncode, 0)

    def test_other_active_hooks_not_disabled(self):
        for n in ("pre-commit", "pre-push"):
            self.write(".githooks/" + n, "#!/bin/sh\nexit 0\n")
        p = self.repo / ".git/hooks/post-commit"
        p.write_text("#!/bin/sh\nexit 0\n")
        p.chmod(0o755)
        with self.assertRaisesRegex(GateError, "EXISTING_HOOKS"):
            install(self.repo, True)

    def test_maven_skip_configuration_blocked(self):
        self.write(".mvn/maven.config", "-DskipTests\n")
        with self.assertRaisesRegex(GateError, "MAVEN_SKIP_CONFIG"):
            vet_maven_config(self.repo)

    def test_zero_java_tests_not_pass(self):
        with self.assertRaisesRegex(GateError, "ZERO_TESTS"):
            java_test_count(self.repo)

    def test_all_skipped_java_tests_not_pass(self):
        self.write("module/target/surefire-reports/TEST-X.xml", '<testsuite tests="2" skipped="2" errors="0" failures="0"/>')
        with self.assertRaisesRegex(GateError, "ZERO_TESTS"):
            java_test_count(self.repo)

    def test_executed_java_count(self):
        self.write("module/target/surefire-reports/TEST-X.xml", '<testsuite tests="3" skipped="1" errors="0" failures="0"/>')
        self.assertEqual(java_test_count(self.repo), 2)

    def test_failing_process_not_green(self):
        r = run_step("fail", [sys.executable, "-c", "raise SystemExit(3)"], self.repo, self.repo, clean_test_env())
        self.assertEqual(r["status"], "FAIL")

    def test_missing_tool_not_green(self):
        r = run_step("missing", ["this-quality-tool-does-not-exist"], self.repo, self.repo, clean_test_env())
        self.assertEqual(r["status"], "BLOCKED")

    def test_policy_missing_not_defaults(self):
        with self.assertRaisesRegex(GateError, "POLICY_MISSING"):
            load_policy(self.repo / "missing.json")

    def test_cli_nonzero_on_violation(self):
        p = SERVER + "agent/X.java"
        self.write(p, "import vip.mate.presales.PresalesToolPolicy;\n")
        result = subprocess.run([sys.executable, str(HERE / "gate.py"), "--repo", str(self.repo), "--mode", "worktree"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)["status"], "FAIL")


class PlanTest(unittest.TestCase):
    def test_docs_only_not_application_change(self):
        self.assertEqual(classify(["docs/a.md"]), {"java": False, "ui": False, "control": False})

    def test_backend_change_triggers_reverse_ui_regression(self):
        result = classify([SERVER + "agent/AgentService.java"])
        self.assertTrue(result["java"] and result["ui"])

    def test_gate_change_triggers_all(self):
        self.assertTrue(all(classify(["scripts/quality/gate.py"]).values()))

    def test_frontend_only(self):
        result = classify([UI + "features/bidding/api/types.ts"])
        self.assertTrue(result["ui"])
        self.assertFalse(result["java"])

    def test_sensitive_env_not_forwarded(self):
        with patch.dict(os.environ, {"OPENAI_API_KEY": "fake", "SPRING_DATASOURCE_URL": "prod", "JAVA_TOOL_OPTIONS": "-DskipTests=true", "MAVEN_OPTS": "-DskipTests", "_JAVA_OPTIONS": "-DskipTests"}):
            env = clean_test_env()
        self.assertNotIn("OPENAI_API_KEY", env)
        self.assertNotIn("SPRING_DATASOURCE_URL", env)
        self.assertNotIn("JAVA_TOOL_OPTIONS", env)
        self.assertNotIn("MAVEN_OPTS", env)
        self.assertNotIn("_JAVA_OPTIONS", env)

    def test_java21_environment_keeps_matching_maven(self):
        env = {"PATH": "/tools"}
        with patch("verify.maven_java_major", return_value=21):
            self.assertIs(java21_env(env), env)

    def test_java21_environment_selects_macos_jdk_for_hook(self):
        env = {"PATH": "/tools"}
        selected = subprocess.CompletedProcess([], 0, "/jdk21\n", "")
        with patch("verify.sys.platform", "darwin"), \
             patch("verify.maven_java_major", side_effect=[25, 21]), \
             patch("verify.subprocess.run", return_value=selected):
            self.assertEqual(java21_env(env)["JAVA_HOME"], "/jdk21")

    def test_java21_environment_rejects_wrong_explicit_jdk(self):
        with patch("verify.maven_java_major", return_value=25):
            with self.assertRaisesRegex(GateError, "MAVEN_JDK21_REQUIRED"):
                java21_env({"JAVA_HOME": "/jdk25"})


if __name__ == "__main__":
    unittest.main()
