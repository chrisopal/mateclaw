# Bidding runtime data preflight

`check-runtime.py` is a read-only gate to run before starting the server. It checks a declared local data directory and database files, or requires an explicit successful probe summary for a remote database. It never creates a directory, initializes a database, applies migrations, or restores a backup.

Run it with an absolute manifest path:

```sh
python3 scripts/bidding/check-runtime.py --manifest /etc/mateclaw/bidding-runtime.json
```

For a local existing database, set `mode` to `existing`, set `dataDirectory` to its absolute directory, and list every expected nonempty database file by absolute path. Every listed file must resolve inside that directory. A missing directory, empty or missing file, repository checkout, `output`/`target` tree, or system temporary directory returns exit code 2. The checker resolves symlinks before applying those rules.

```json
{
  "mode": "existing",
  "databaseKind": "local",
  "dataDirectory": "/var/lib/mateclaw",
  "expectedDatabaseFiles": ["/var/lib/mateclaw/mateclaw.mv.db"],
  "backupManifest": "/var/backups/mateclaw/latest.json"
}
```

Use `mode: "new"` only after the operator has selected a genuinely new, absolute data path outside the checkout and temporary/output directories. The destination must not already exist and no database files may be listed. This protects historical directories from being silently replaced with an empty database. For an existing installation, locate and verify its actual data path first. The default H2 URL is relative (`./data/mateclaw`), so its resolved location depends on the process working directory. Do not treat an absent default path as permission to create a new database: stop and identify the prior business data directory and its bidding project count first.

Remote databases have no local files to check. Declare `databaseKind: "remote"`, `databaseVerified: true`, and a `probeSummary` with `connected: true`, `biddingProjectTablePresent: true`, and a nonnegative integer `biddingProjectCount`. These values summarize a controlled connection/table/data probe. The probe must use the configured connection without putting its URL, username, password, API key, or token in this manifest or its output. A checker PASS means only that the declared preflight evidence exists; it does not connect to the remote database itself.

An optional `backupManifest` is a JSON file with a nonempty `files` array. It must declare `complete: true` and a nonempty `files` array. Each entry contains `path` (absolute or relative to that manifest) and a lowercase SHA-256 digest:

```json
{"complete":true,"files":[{"path":"mateclaw.mv.db","sha256":"<64 lowercase hex characters>"}]}
```

The checker verifies that each backup file exists, is nonempty, and matches its digest. This does not establish that a backup can be restored. Restore readiness requires a separate isolated restore rehearsal and a readback of representative records. Never rehearse against the source database or a directory shared with another worktree.

The checker prints only a success marker or problem code and relevant filesystem path. It does not print manifest contents or environment values. A local or remote preflight does not prove browser behavior, model availability, analysis quality, or production readiness.
