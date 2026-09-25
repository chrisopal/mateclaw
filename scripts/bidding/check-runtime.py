#!/usr/bin/env python3
"""Fail-closed, read-only bidding database startup preflight."""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import re
import sys
import tempfile
from pathlib import Path
from typing import Any

LOGGER = logging.getLogger("check_runtime")
REPO_ROOT = Path(__file__).resolve().parents[2]
SECRET_KEY = re.compile(r"(?:password|passwd|secret|token|api[_-]?key|credential|jdbc[_-]?url|database[_-]?url)", re.I)
URI_USERINFO = re.compile(r"\w+://[^/@\s]+:[^/@\s]+@", re.I)
URL_SECRET_PARAMETER = re.compile(r"[?&](?:password|passwd|token|api[_-]?key)=([^&\s]+)", re.I)


def _problem(code: str, path: Path | None = None) -> None:
    suffix = f" path={path}" if path is not None else ""
    LOGGER.error("%s%s", code, suffix)


def _contains_secret(value: Any) -> bool:
    if isinstance(value, dict):
        return any(SECRET_KEY.search(str(key)) or _contains_secret(item) for key, item in value.items())
    if isinstance(value, list):
        return any(_contains_secret(item) for item in value)
    return isinstance(value, str) and bool(URI_USERINFO.search(value) or URL_SECRET_PARAMETER.search(value))


def _as_absolute_path(raw: Any) -> Path | None:
    if not isinstance(raw, str) or not raw.strip():
        return None
    candidate = Path(raw)
    if not candidate.is_absolute():
        return None
    try:
        return candidate.resolve(strict=False)
    except (OSError, RuntimeError, ValueError):
        return None


def _is_unsafe_data_path(path: Path) -> bool:
    temp_root = Path(tempfile.gettempdir()).resolve()
    try:
        path.relative_to(temp_root)
        return True
    except ValueError:
        pass
    try:
        path.relative_to(REPO_ROOT)
        return True
    except ValueError:
        pass
    if any(part.lower() in {"output", "target", ".worktree", ".worktrees"} for part in path.parts):
        return True
    return False


def _verify_backup(raw_manifest: Any) -> bool:
    if raw_manifest is None:
        return True
    manifest_path = _as_absolute_path(raw_manifest)
    if manifest_path is None or not manifest_path.is_file():
        _problem("BACKUP_MANIFEST_MISSING")
        return False
    try:
        backup = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        _problem("BACKUP_MANIFEST_INVALID", manifest_path)
        return False
    if not isinstance(backup, dict):
        _problem("BACKUP_MANIFEST_INVALID", manifest_path)
        return False
    if _contains_secret(backup):
        _problem("BACKUP_MANIFEST_CONTAINS_CREDENTIAL_FIELD", manifest_path)
        return False
    if backup.get("complete") is not True:
        _problem("BACKUP_MANIFEST_INCOMPLETE", manifest_path)
        return False
    files = backup.get("files")
    if not isinstance(files, list) or not files:
        _problem("BACKUP_FILES_MISSING", manifest_path)
        return False
    valid = True
    for entry in files:
        if not isinstance(entry, dict):
            valid = False
            continue
        raw_path = entry.get("path")
        path = _as_absolute_path(raw_path)
        if path is None and isinstance(raw_path, str) and raw_path.strip():
            path = (manifest_path.parent / raw_path).resolve(strict=False)
        expected = entry.get("sha256")
        if path is None or not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            valid = False
            continue
        try:
            if not path.is_file() or path.stat().st_size == 0:
                valid = False
                continue
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            if digest.hexdigest() != expected:
                _problem("BACKUP_CHECKSUM_MISMATCH", path)
                valid = False
        except OSError:
            _problem("BACKUP_FILE_UNREADABLE", path)
            valid = False
    if not valid:
        _problem("BACKUP_INCOMPLETE", manifest_path)
    return valid


def check(manifest: dict[str, Any]) -> int:
    """Return 0 for a safe, verified path; otherwise return 2 without mutation."""
    if not isinstance(manifest, dict):
        _problem("MANIFEST_INVALID")
        return 2
    if _contains_secret(manifest):
        _problem("MANIFEST_CONTAINS_CREDENTIAL_FIELD")
        return 2

    mode = manifest.get("mode")
    if mode not in {"existing", "new"}:
        _problem("MODE_INVALID")
        return 2

    database_kind = manifest.get("databaseKind", "local")
    if database_kind == "remote":
        summary = manifest.get("probeSummary")
        summary_is_verified = (
            isinstance(summary, dict)
            and summary.get("connected") is True
            and summary.get("biddingProjectTablePresent") is True
            and isinstance(summary.get("biddingProjectCount"), int)
            and not isinstance(summary.get("biddingProjectCount"), bool)
            and summary["biddingProjectCount"] >= 0
        )
        if manifest.get("databaseVerified") is not True or not summary_is_verified:
            _problem("REMOTE_DATABASE_UNVERIFIED")
            return 2
        if _contains_secret(summary):
            _problem("PROBE_SUMMARY_CONTAINS_CREDENTIAL_FIELD")
            return 2
        data_path = None
        expected: list[Any] = []
    elif database_kind == "local":
        raw_data_path = manifest.get("dataDirectory")
        data_path = _as_absolute_path(raw_data_path)
        if data_path is None:
            _problem("DATA_DIRECTORY_NOT_ABSOLUTE")
            return 2
        if _is_unsafe_data_path(data_path):
            _problem("DATA_DIRECTORY_UNSAFE", data_path)
            return 2
        expected = manifest.get("expectedDatabaseFiles")
        if not isinstance(expected, list):
            _problem("DATABASE_FILES_INVALID", data_path)
            return 2
    else:
        _problem("DATABASE_KIND_INVALID")
        return 2

    valid = True
    if database_kind == "local":
        if mode == "new":
            if data_path.exists():
                _problem("NEW_DATA_DIRECTORY_ALREADY_EXISTS", data_path)
                valid = False
            if expected:
                _problem("NEW_DATABASE_FILES_MUST_BE_EMPTY", data_path)
                valid = False
        else:
            if not data_path.is_dir():
                _problem("EXISTING_DATA_DIRECTORY_MISSING", data_path)
                valid = False
            if not expected:
                _problem("DATABASE_FILES_MISSING", data_path)
                valid = False
            for raw_file in expected:
                path = _as_absolute_path(raw_file)
                if path is None:
                    _problem("DATABASE_FILE_NOT_ABSOLUTE", data_path)
                    valid = False
                    continue
                try:
                    path.relative_to(data_path)
                except ValueError:
                    _problem("DATABASE_FILE_OUTSIDE_DATA_DIRECTORY", path)
                    valid = False
                    continue
                try:
                    if not path.is_file() or path.stat().st_size == 0:
                        _problem("DATABASE_FILE_MISSING_OR_EMPTY", path)
                        valid = False
                except OSError:
                    _problem("DATABASE_FILE_UNREADABLE", path)
                    valid = False

    if not _verify_backup(manifest.get("backupManifest")):
        valid = False
    if valid:
        if data_path is None:
            LOGGER.info("RUNTIME_PATH_CHECK_OK databaseKind=remote")
        else:
            LOGGER.info("RUNTIME_PATH_CHECK_OK dataDirectory=%s", data_path)
        return 0
    return 2


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, help="absolute path to a runtime manifest JSON file")
    args = parser.parse_args(argv)
    path = _as_absolute_path(args.manifest)
    if path is None:
        _problem("MANIFEST_PATH_NOT_ABSOLUTE")
        return 2
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        _problem("MANIFEST_UNREADABLE", path)
        return 2
    return check(manifest)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    sys.exit(main())
