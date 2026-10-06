#!/usr/bin/env python3
"""Read-only, dependency-free architecture ratchet for MateClaw (Python 3.10+).

This is a lexical guard, not a compiler, AST type checker or authorization proof.
The comparison is against an immutable Git base, not an editable baseline file.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import asdict, dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import re
import subprocess
import sys
from typing import Any

VERSION = "1.0.0"
SOURCE_SUFFIXES = {".java", ".ts", ".tsx", ".vue", ".js", ".mjs", ".cjs", ".py", ".sql", ".xml", ".json", ".yml", ".yaml", ".sh", ".md"}
SKIP_PARTS = {"node_modules", "target", "dist", ".git", ".worktrees", "output", "__pycache__"}
BUSINESSES = {"semantic", "presales", "bidding", "delivery"}
SERVER = "mateclaw-server/src/main/java/vip/mate/"
UI = "mateclaw-ui/src/"
DEFAULT_POLICY = {
    "version": 1,
    "zero_tolerance": {},
    "source_limit_bytes": 5_000_000,
}
FROZEN_MIGRATIONS = ".quality/frozen-migrations.json"


class GateError(RuntimeError):
    pass


def git(repo: Path, *args: str, input_bytes: bytes | None = None) -> bytes:
    proc = subprocess.run(["git", "-C", str(repo), *args], input=input_bytes,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if proc.returncode:
        raise GateError(f"git {args[0]} failed: {proc.stderr.decode('utf-8', 'replace').strip()}")
    return proc.stdout


def resolve_commit(repo: Path, ref: str) -> str:
    if not ref or ref.startswith("-"):
        raise GateError("BASE_REQUIRED: provide an existing commit/ref")
    return git(repo, "rev-parse", "--verify", "--end-of-options", ref + "^{commit}").decode().strip()


def repo_root(path: Path) -> Path:
    return Path(git(path, "rev-parse", "--show-toplevel").decode().strip()).resolve()


def eligible(path: str) -> bool:
    parts = PurePosixPath(path).parts
    # Never hide a real source package merely because it is named output/dist.
    in_source = "/src/" in path or path.startswith("scripts/quality/")
    skipped = any(p in SKIP_PARTS for p in parts) if not in_source else any(p in {"node_modules", "__pycache__", ".git"} for p in parts)
    return not skipped and PurePosixPath(path).suffix in SOURCE_SUFFIXES


def tree_entries(repo: Path, ref: str) -> dict[str, tuple[str, str]]:
    entries: dict[str, tuple[str, str]] = {}
    for raw in git(repo, "ls-tree", "-r", "-z", ref).split(b"\0"):
        if not raw:
            continue
        meta, name = raw.split(b"\t", 1)
        mode, kind, oid = meta.decode().split()
        path = name.decode("utf-8", "strict")
        if kind == "blob" and eligible(path):
            entries[path] = (mode, oid)
    return entries


def blob_map(repo: Path, entries: dict[str, tuple[str, str]], limit: int) -> dict[str, str]:
    result: dict[str, str] = {}
    selected = list(entries.items())
    if not selected:
        return result
    # Batch avoids one subprocess per source file; never executes repository code.
    oids = [value[1] for _, value in selected]
    raw = git(repo, "cat-file", "--batch", input_bytes=("\n".join(oids) + "\n").encode())
    offset = 0
    for (path, (mode, _oid)) in selected:
        end = raw.find(b"\n", offset)
        header = raw[offset:end].decode().split()
        if len(header) != 3 or header[1] != "blob":
            raise GateError("GIT_BLOB_UNAVAILABLE")
        size = int(header[2])
        data = raw[end + 1:end + 1 + size]
        offset = end + size + 2
        if size > limit:
            raise GateError(f"SOURCE_TOO_LARGE: {path}; review limit rather than silently skipping")
        if mode == "120000":
            raise GateError(f"SOURCE_SYMLINK_UNSUPPORTED: {path}; no external target is followed")
        try:
            result[path] = data.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise GateError(f"SOURCE_NOT_UTF8: {path}") from exc
    return result


def snapshot(repo: Path, mode: str, ref: str = "HEAD", limit: int = 5_000_000) -> tuple[dict[str, str], str]:
    if mode in {"head", "base"}:
        commit = resolve_commit(repo, ref)
        return blob_map(repo, tree_entries(repo, commit), limit), commit
    if mode == "staged":
        # write-tree fails on unmerged index entries and reads the *index*, not the worktree.
        tree = git(repo, "write-tree").decode().strip()
        return blob_map(repo, tree_entries(repo, tree), limit), tree
    if mode != "worktree":
        raise GateError("UNKNOWN_SNAPSHOT_MODE")
    names = git(repo, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
    result = {}
    for raw in set(names.split(b"\0")):
        if not raw:
            continue
        path = raw.decode("utf-8", "strict")
        if not eligible(path):
            continue
        target = repo / path
        if not target.exists() and not target.is_symlink():
            continue
        if target.is_symlink():
            raise GateError(f"SOURCE_SYMLINK_UNSUPPORTED: {path}")
        if target.stat().st_size > limit:
            raise GateError(f"SOURCE_TOO_LARGE: {path}")
        try:
            # Preserve the same bytes as Git blobs; read_text normalizes CRLF.
            result[path] = target.read_bytes().decode("utf-8")
        except UnicodeDecodeError as exc:
            raise GateError(f"SOURCE_NOT_UTF8: {path}") from exc
    digest = hashlib.sha256(json.dumps(result, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return result, digest


def masked(text: str, strings: bool = True) -> str:
    """Mask common Java/JS comments and quoted strings, keeping newlines/positions.

    Regex literals, computed imports, reflection and generated bytecode require AST/
    ArchUnit/review gates. This function deliberately does not claim to parse a language.
    """
    out = list(text)
    i = 0
    while i < len(text):
        start = i
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
            hide = True
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = len(text) if end < 0 else end + 2
            hide = True
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = len(text) if end < 0 else end + 3
            hide = strings
        elif text[i] in "\"'`":
            quote = text[i]
            i += 1
            while i < len(text):
                if text[i] == "\\":
                    i += 2
                elif text[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
            hide = strings
        else:
            i += 1
            continue
        if hide:
            for at in range(start, min(i, len(out))):
                if out[at] not in "\r\n":
                    out[at] = " "
    return "".join(out)


def script_part(text: str, path: str) -> str:
    if not path.endswith(".vue"):
        return text
    out = ["\n" if x == "\n" else " " for x in text]
    for match in re.finditer(r"<script\b[^>]*>(.*?)</script>", text, re.S | re.I):
        out[match.start(1):match.end(1)] = list(match.group(1))
    return "".join(out)


@dataclass(frozen=True)
class Finding:
    rule: str
    path: str
    line: int
    detail: str
    fingerprint: str


def finding(rule: str, path: str, text: str, at: int, detail: str, token: str) -> Finding:
    stable = re.sub(r"\s+", " ", token).strip()
    fp = hashlib.sha256(f"{rule}\0{path}\0{stable}".encode()).hexdigest()
    return Finding(rule, path, text.count("\n", 0, at) + 1, detail, fp)


def scan_file(path: str, text: str) -> list[Finding]:
    out: list[Finding] = []
    code = masked(script_part(text, path))
    comments_removed = masked(script_part(text, path), strings=False)
    is_java = path.endswith(".java")
    is_prod_java = is_java and "/src/main/java/" in path
    is_ui = path.startswith(UI) and PurePosixPath(path).suffix in {".ts", ".tsx", ".vue", ".js", ".mjs"}
    domain_match = re.search(r"/features/([^/]+)/", path)
    domain = domain_match.group(1) if domain_match else None
    is_test = any(p in path for p in ("/src/test/", "/__tests__/", "/test/")) or bool(re.search(r"\.(test|spec)\.", path))

    if path.startswith(SERVER):
        area = path[len(SERVER):].split("/", 1)[0]
        if area in {"agent", "auth", "workspace", "common", "tool", "skill"}:
            for m in re.finditer(r"\bvip\.mate\.(presales|bidding|delivery)(?:\.[\w$]+)*", code):
                out.append(finding("AR-001", path, text, m.start(), "通用层依赖具体工作台；改用公共扩展接口", m.group()))
        if area in {"presales", "bidding", "delivery"}:
            for m in re.finditer(r"\b(?:vip\.mate\.semantic\.(?:security|web)\.[\w$]+)", code):
                out.append(finding("AR-004", path, text, m.start(), "身份/通用错误依赖语义模块内部实现", m.group()))
    if is_prod_java and (path.endswith("Controller.java") or "@RestController" in code):
        pattern = r"\b(?:JdbcTemplate|NamedParameterJdbcTemplate|DataSource|EntityManager)\b|\bjava\.sql\.[\w.]+|\b\w+(?:Mapper|Repository)\s+\w+\s*[;,)]"
        for m in re.finditer(pattern, code):
            # ObjectMapper is a serializer, not a database mapper.
            if m.group().startswith("ObjectMapper "):
                continue
            out.append(finding("AR-002", path, text, m.start(), "Controller 不可直接依赖持久化访问", m.group()))
    if is_prod_java and path.startswith(SERVER) and not any(p in path for p in ("/repository/", "/integration/")):
        area = path[len(SERVER):].split("/", 1)[0]
        if area in {"presales", "bidding", "delivery"}:
            for m in re.finditer(r"\b(?:FROM|JOIN|UPDATE|INTO)\s+(mate_(?:wiki|agent|workspace|semantic)(?:_[\w]+)?)\b", comments_removed, re.I):
                out.append(finding("AR-005", path, text, m.start(), "业务层直接访问外域表；需受控服务/适配器", m.group().upper()))

    if is_ui:
        imports = r"(?:\bfrom\s*|\bimport\s*\(\s*|\brequire\s*\(\s*|\bimport\s*)['\"]([^'\"]+)['\"]"
        for m in re.finditer(imports, comments_removed):
            target = m.group(1)
            if target.startswith("@/"):
                resolved = UI + target[2:]
            elif target.startswith("."):
                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
            else:
                continue
            other = re.search(r"/features/([^/]+)/(.*)", resolved)
            if other:
                target_domain, suffix = other.groups()
                public = suffix == "public" or suffix.startswith("public/") or suffix.startswith("public.")
                if (domain and domain != target_domain and not public) or (not domain and (path.startswith(UI + "api/") or path.startswith(UI + "shared/"))):
                    out.append(finding("AR-003", path, text, m.start(), "公共层/兄弟域不可导入 feature 内部文件", resolved))
        if domain in BUSINESSES and not is_test:
            for m in re.finditer(r"\bany\b", code):
                # Key includes surrounding declaration; avoid all any occurrences sharing one budget.
                start = max(0, code.rfind("\n", 0, m.start()) + 1)
                end = code.find("\n", m.end())
                out.append(finding("TS-001", path, text, m.start(), "稳定业务契约禁止新增 any；边界用 unknown 后校验", code[start:len(code) if end < 0 else end]))
            ui_text = masked(text, strings=False)
            for m in re.finditer(r"\bl\s*\(\s*['\"][^'\"\n]*[\u4e00-\u9fff][^'\"\n]*['\"]\s*,", ui_text):
                out.append(finding("UI-001", path, text, m.start(), "新增界面文案必须进入 i18n 命名空间", m.group()))
    if is_test:
        for m in re.finditer(r"@(?:Disabled|Ignore)\b|\b(?:it|test|describe)\.(?:skip|only)\s*\(", code):
            out.append(finding("TEST-001", path, text, m.start(), "不可新增跳过/仅跑指定测试来使门禁变绿", m.group()))
    # Lightweight density/readability guard complements (does not replace) actual formatters.
    if (is_prod_java or is_ui) and not is_test:
        for i, line in enumerate(text.splitlines(keepends=True)):
            value = line.rstrip("\r\n")
            if len(value) > 200:
                at = sum(len(x) for x in text.splitlines(keepends=True)[:i])
                out.append(finding("STYLE-001", path, text, at, "新增超长源代码行；先格式化并拆分职责", value))
    return out


def scan(files: dict[str, str]) -> list[Finding]:
    return [f for p, t in sorted(files.items()) for f in scan_file(p, t)]


def is_migration(path: str) -> bool:
    return bool(re.search(r"/db/migration/(?:h2|mysql|kingbase)/V[^/]+\.(?:sql|java)$", path))


def frozen_sources(raw: str) -> dict[str, str]:
    """Validate an explicit source closure; never infer Java dependencies."""
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

    data = json.loads(raw, object_pairs_hook=unique_object)
    if (not isinstance(data, dict) or set(data) != {"version", "files"}
            or type(data["version"]) is not int or data["version"] != 1
            or not isinstance(data["files"], dict) or not data["files"]):
        raise ValueError("invalid source-freeze schema")
    for path, digest in data["files"].items():
        if (not isinstance(path, str) or str(PurePosixPath(path)) != path
                or ".." in PurePosixPath(path).parts
                or not re.fullmatch(r"mateclaw-[^/]+/src/main/java/[^\\]+\.java", path)
                or not eligible(path) or not isinstance(digest, str)
                or not re.fullmatch(r"[0-9a-f]{64}", digest)):
            raise ValueError("invalid frozen source path or SHA-256")
    return data["files"]


def frozen_migration_changes(before: dict[str, str], after: dict[str, str]) -> list[Finding]:
    out = []
    declarations = []
    for files in (before, after):
        raw = files.get(FROZEN_MIGRATIONS)
        try:
            declared = frozen_sources(raw) if raw is not None else {}
        except ValueError:
            out.append(finding("DB-003", FROZEN_MIGRATIONS, raw, 0,
                               "冻结迁移清单格式无效；版本、路径、摘要与重复键需审核", "invalid manifest"))
            declared = {}
        declarations.append(declared)
        for path, digest in declared.items():
            text = files.get(path)
            if text is None or hashlib.sha256(text.encode("utf-8")).hexdigest() != digest:
                out.append(finding("DB-004", path, text or "", 0,
                                   "冻结源码缺失或不匹配已声明 SHA-256；新增版本替代", path))

    old, new = declarations
    for path, digest in old.items():
        if new.get(path) != digest or after.get(path) != before.get(path):
            out.append(finding("DB-004", path, before.get(path, ""), 0,
                               "基线冻结源码及摘要不可删除、修改或重命名", path))
    for path in new.keys() - old.keys():
        if path in before and before[path] != after.get(path):
            out.append(finding("DB-004", path, before[path], 0,
                               "首次冻结已有源码必须保持基线字节；不能同时重写历史", path))
    for path, text in after.items():
        if path.endswith(".java") and is_migration(path) and path not in new:
            out.append(finding("DB-003", path, text, 0,
                               "Java Flyway 入口及算法闭包必须登记冻结清单", path))
    return out


def invariant_changes(before: dict[str, str], after: dict[str, str]) -> list[Finding]:
    out = frozen_migration_changes(before, after)
    for p, text in before.items():
        if is_migration(p) and after.get(p) != text:
            out.append(finding("DB-001", p, text, 0, "已存在 Flyway 迁移不可修改、删除或重命名；新增版本迁移", p))
    new_migrations = [p for p in after.keys() - before.keys() if is_migration(p)]
    for p in new_migrations:
        for dialect in ("h2", "mysql", "kingbase"):
            counterpart = re.sub(r"/db/migration/(h2|mysql|kingbase)/", f"/db/migration/{dialect}/", p)
            if counterpart not in after:
                out.append(finding("DB-002", p, after[p], 0, f"新增迁移缺少 {dialect} 方言文件（内容需按方言实现）", counterpart))
    # Whitespace is checked only on added/modified lines; Markdown hard breaks are valid.
    import difflib
    for p in sorted(after.keys()):
        if before.get(p) == after[p] or p.endswith(".md"):
            continue
        old, new = before.get(p, "").splitlines(), after[p].splitlines()
        sm = difflib.SequenceMatcher(a=old, b=new, autojunk=False)
        for tag, _a, _b, start, end in sm.get_opcodes():
            if tag not in {"insert", "replace"}:
                continue
            for n in range(start, end):
                if new[n].endswith((" ", "\t")):
                    out.append(Finding("STYLE-002", p, n + 1, "新增行尾空白", hashlib.sha256(f"{p}:{n}".encode()).hexdigest()))
    return out


def evaluate(before: dict[str, str], after: dict[str, str], policy: dict[str, Any]) -> dict[str, Any]:
    old, new = scan(before), scan(after)
    remaining = Counter(f.fingerprint for f in old)
    introduced, grandfathered = [], []
    sealed = policy.get("zero_tolerance", {})
    for f in new:
        zero = any(f.path.startswith(prefix) for prefix in sealed.get(f.rule, []))
        if not zero and remaining[f.fingerprint] > 0:
            remaining[f.fingerprint] -= 1
            grandfathered.append(f)
        else:
            introduced.append(f)
    introduced.extend(invariant_changes(before, after))
    changed = sorted(p for p in before.keys() | after.keys() if before.get(p) != after.get(p))
    return {
        "status": "FAIL" if introduced else "PASS",
        "meaning": "No new lexical violations; not an application or security acceptance",
        "changed_files": changed,
        "new_violations": [asdict(f) for f in introduced],
        "existing_violation_count": len(grandfathered),
        "existing_by_rule": dict(Counter(f.rule for f in grandfathered)),
        "removed_fingerprint_count": sum((Counter(f.fingerprint for f in old) - Counter(f.fingerprint for f in new)).values()),
    }


def load_policy(path: Path | None) -> dict[str, Any]:
    if path is None:
        return DEFAULT_POLICY
    if not path.is_file():
        raise GateError(f"POLICY_MISSING: {path}")
    p = json.loads(path.read_text(encoding="utf-8"))
    if p.get("version") != 1 or not isinstance(p.get("zero_tolerance"), dict):
        raise GateError("POLICY_INVALID")
    for rule, values in p["zero_tolerance"].items():
        if not isinstance(rule, str) or not isinstance(values, list) or not all(isinstance(x, str) and x for x in values):
            raise GateError("POLICY_INVALID_ZERO_TOLERANCE")
    return p


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--base", default="HEAD")
    parser.add_argument("--mode", choices=["worktree", "staged", "head"], default="worktree")
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    try:
        repo = repo_root(args.repo)
        p = load_policy(args.policy)
        before, base = snapshot(repo, "base", args.base, p.get("source_limit_bytes", 5_000_000))
        after, identity = snapshot(repo, args.mode, "HEAD", p.get("source_limit_bytes", 5_000_000))
        report = evaluate(before, after, p)
        report.update(version=VERSION, base_commit=base, target_identity=identity, mode=args.mode)
        code = 0 if report["status"] == "PASS" else 1
    except (GateError, OSError, ValueError) as exc:
        report, code = {"status": "BLOCKED", "error": str(exc)}, 2
    payload = json.dumps(report, indent=2, ensure_ascii=False)
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(payload + "\n", encoding="utf-8")
    print(payload)
    return code


if __name__ == "__main__":
    sys.exit(main())
