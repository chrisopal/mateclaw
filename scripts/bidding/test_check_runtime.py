import hashlib
import json
import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "check_runtime", pathlib.Path(__file__).with_name("check-runtime.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RuntimeCheckTest(unittest.TestCase):
    def test_missing_existing_database_does_not_create_one(self):
        with tempfile.TemporaryDirectory() as folder:
            db = pathlib.Path(folder) / "business.mv.db"
            with patch.object(module, "_is_unsafe_data_path", return_value=False):
                self.assertEqual(
                    2,
                    module.check(
                        {
                            "mode": "existing",
                            "dataDirectory": folder,
                            "expectedDatabaseFiles": [str(db)],
                            "backupManifest": None,
                        }
                    ),
                )
            self.assertFalse(db.exists())

    def test_existing_database_requires_nonempty_files(self):
        with tempfile.TemporaryDirectory() as folder:
            db = pathlib.Path(folder) / "business.mv.db"
            db.write_bytes(b"h2")
            with patch.object(module, "_is_unsafe_data_path", return_value=False):
                self.assertEqual(
                    0,
                    module.check(
                        {
                            "mode": "existing",
                            "dataDirectory": folder,
                            "expectedDatabaseFiles": [str(db)],
                            "backupManifest": None,
                        }
                    ),
                )

    def test_canonical_tmp_paths_are_rejected(self):
        for raw_path in ("/tmp/mateclaw-business", "/var/tmp/mateclaw-business"):
            with self.subTest(path=raw_path):
                self.assertTrue(module._is_unsafe_data_path(pathlib.Path(raw_path).resolve()))

    def test_nul_in_relative_backup_path_returns_error_code(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            data = root / "data"
            data.mkdir()
            db = data / "business.mv.db"
            db.write_bytes(b"db")
            backup = root / "backup.json"
            backup.write_text(
                json.dumps({"complete": True, "files": [{"path": "bad\u0000path", "sha256": "0" * 64}]}),
                encoding="utf-8",
            )
            with patch.object(module, "_is_unsafe_data_path", return_value=False):
                self.assertEqual(
                    2,
                    module.check(
                        {
                            "mode": "existing",
                            "dataDirectory": str(data),
                            "expectedDatabaseFiles": [str(db)],
                            "backupManifest": str(backup),
                        }
                    ),
                )

    def test_worktree_paths_are_rejected(self):
        worktrees_parent = module.REPO_ROOT.parent
        self.assertTrue(module._is_unsafe_data_path(worktrees_parent / "sibling-worktree" / "data"))

    def test_new_mode_never_creates_an_unsafe_database_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            destination = pathlib.Path(folder) / "new-database"
            self.assertEqual(
                2,
                module.check(
                    {
                        "mode": "new",
                        "dataDirectory": str(destination),
                        "expectedDatabaseFiles": [],
                        "backupManifest": None,
                    }
                ),
            )
            self.assertFalse(destination.exists())

    def test_backup_checksum_must_match(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            data = root / "data"
            data.mkdir()
            db = data / "business.mv.db"
            db.write_bytes(b"db")
            backup = root / "backup.mv.db"
            backup.write_bytes(b"backup")
            manifest = root / "backup.json"
            manifest.write_text(
                '{"complete":true,"files":[{"path":"backup.mv.db","sha256":"' + "0" * 64 + '"}]}',
                encoding="utf-8",
            )
            with patch.object(module, "_is_unsafe_data_path", return_value=False):
                self.assertEqual(
                    2,
                    module.check(
                        {
                            "mode": "existing",
                            "dataDirectory": str(data),
                            "expectedDatabaseFiles": [str(db)],
                            "backupManifest": str(manifest),
                        }
                    ),
                )

    def test_backup_manifest_must_be_complete(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            data = root / "data"
            data.mkdir()
            db = data / "business.mv.db"
            db.write_bytes(b"db")
            backup = root / "backup.mv.db"
            backup.write_bytes(b"backup")
            manifest = root / "backup.json"
            manifest.write_text(
                json.dumps({"files": [{"path": "backup.mv.db", "sha256": hashlib.sha256(backup.read_bytes()).hexdigest()}]}),
                encoding="utf-8",
            )
            with patch.object(module, "_is_unsafe_data_path", return_value=False):
                self.assertEqual(
                    2,
                    module.check(
                        {
                            "mode": "existing",
                            "dataDirectory": str(data),
                            "expectedDatabaseFiles": [str(db)],
                            "backupManifest": str(manifest),
                        }
                    ),
                )

    def test_complete_backup_with_matching_checksum_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            data = root / "data"
            data.mkdir()
            db = data / "business.mv.db"
            db.write_bytes(b"db")
            backup = root / "backup.mv.db"
            backup.write_bytes(b"backup")
            manifest = root / "backup.json"
            digest = hashlib.sha256(backup.read_bytes()).hexdigest()
            manifest.write_text(
                json.dumps({"complete": True, "files": [{"path": "backup.mv.db", "sha256": digest}]}),
                encoding="utf-8",
            )
            with patch.object(module, "_is_unsafe_data_path", return_value=False):
                self.assertEqual(
                    0,
                    module.check(
                        {
                            "mode": "existing",
                            "dataDirectory": str(data),
                            "expectedDatabaseFiles": [str(db)],
                            "backupManifest": str(manifest),
                        }
                    ),
                )

    def test_remote_database_requires_verified_probe_without_credentials(self):
        result = module.check(
            {
                "mode": "existing",
                "databaseKind": "remote",
                "databaseVerified": True,
                "probeSummary": {"connected": True, "biddingProjectTablePresent": True, "biddingProjectCount": 0},
                "expectedDatabaseFiles": [],
                "backupManifest": None,
            }
        )
        self.assertEqual(0, result)

    def test_url_password_is_rejected_without_echoing_value(self):
        secret = "do-not-print-this"
        with self.assertLogs("check_runtime", level="ERROR") as captured:
            result = module.check(
                {
                    "mode": "existing",
                    "databaseKind": "remote",
                    "databaseVerified": True,
                    "probeSummary": {"connected": True, "biddingProjectTablePresent": True, "biddingProjectCount": 0, "connection": "jdbc:mysql://db/service?password=" + secret},
                    "expectedDatabaseFiles": [],
                    "backupManifest": None,
                }
            )
        self.assertEqual(2, result)
        self.assertNotIn(secret, " ".join(captured.output))

    def test_manifest_credentials_are_rejected_without_echoing_values(self):
        with tempfile.TemporaryDirectory() as folder:
            secret = "do-not-print-this"
            with self.assertLogs("check_runtime", level="ERROR") as captured:
                result = module.check(
                    {
                        "mode": "new",
                        "dataDirectory": str(pathlib.Path(folder) / "new-db"),
                        "expectedDatabaseFiles": [],
                        "backupManifest": None,
                        "password": secret,
                    }
                )
            self.assertEqual(2, result)
            self.assertNotIn(secret, " ".join(captured.output))


if __name__ == "__main__":
    unittest.main()
