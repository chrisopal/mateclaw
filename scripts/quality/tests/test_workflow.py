"""Regression for the job-level context error rejected by GitHub Actions."""
from pathlib import Path
import re
import unittest


class WorkflowContextTest(unittest.TestCase):
    def test_verify_job_environment_does_not_require_a_runner(self):
        workflow = (Path(__file__).resolve().parents[3]
                    / ".github/workflows/engineering-gate.yml").read_text()
        verify_job = workflow.split("  verify:\n", 1)[1].split("    steps:\n", 1)[0]
        environment = re.search(r"(?m)^    env:\n((?:^      .*\n)+)", verify_job)
        self.assertIsNotNone(environment, "verify job environment must remain explicit")
        # GitHub evaluates job env before a runner exists. This deliberately
        # checks that boundary, not the supported runner context in step env.
        self.assertNotRegex(environment.group(1), r"\$\{\{[^\n}]*\brunner\s*(?:\.|\[)")

    def test_report_producer_and_upload_paths_agree(self):
        workflow = (Path(__file__).resolve().parents[3]
                    / ".github/workflows/engineering-gate.yml").read_text()
        execute = workflow.split("      - name: Execute base-owned runner against candidate\n", 1)[1]
        execute = execute.split("      - name:", 1)[0]
        upload = workflow.split("      - name: Upload engineering evidence\n", 1)[1]
        upload = upload.split("      - name:", 1)[0].split("  required:\n", 1)[0]
        producer = re.search(r"(?m)^          REPORT_DIR: (.+)$", execute)
        consumer = re.search(r"(?m)^          path: (.+)$", upload)
        self.assertIsNotNone(producer)
        self.assertIsNotNone(consumer)
        self.assertEqual(producer.group(1), consumer.group(1))


if __name__ == "__main__":
    unittest.main()
