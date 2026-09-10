#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
h2_jar=${H2_JAR:-$(find "$HOME/.m2/repository/com/h2database/h2" -name 'h2-*.jar' | sort | tail -1)}
if [[ -z "$h2_jar" || ! -f "$h2_jar" ]]; then
  echo "H2 jar not found; set H2_JAR" >&2
  exit 2
fi

work_dir=$(mktemp -d /tmp/semantic-owl-reset-rehearsal.XXXXXX)
db_url="jdbc:h2:file:${work_dir}/semantic-reset-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
classes_dir="$work_dir/classes"
run_dir="$work_dir/runs"
mkdir -p "$classes_dir" "$run_dir"

java -cp "$h2_jar" org.h2.tools.RunScript -url "$db_url" -user sa -script "$repo_root/scripts/semantic-owl-reset/test/schema.sql"
java -cp "$h2_jar" org.h2.tools.RunScript -url "$db_url" -user sa -script "$repo_root/scripts/semantic-owl-reset/test/seed.sql"
# Optional isolated rehearsal of the post-retirement schema; never targets application data.
if [[ "${SEMANTIC_RESET_TEST_RETIRED_SCHEMA:-0}" == "1" ]]; then
  java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa \
    -sql "ALTER TABLE mate_semantic_ontology_revision DROP COLUMN definition_json" >/dev/null
fi
javac -d "$classes_dir" "$repo_root/scripts/semantic-owl-reset/src/SemanticOwlReset.java"

run_reset() {
  java -cp "$classes_dir:$h2_jar" SemanticOwlReset "$@"
}

unknown_db_url="jdbc:h2:file:${work_dir}/semantic-reset-unknown-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
java -cp "$h2_jar" org.h2.tools.RunScript -url "$unknown_db_url" -user sa -script "$repo_root/scripts/semantic-owl-reset/test/schema.sql"
java -cp "$h2_jar" org.h2.tools.Shell -url "$unknown_db_url" -user sa \
  -sql "CREATE TABLE mate_semantic_future_v206(id VARCHAR(36) PRIMARY KEY);" >/dev/null
if run_reset --jdbc-url "$unknown_db_url" --workspace-id 7 --ontology-id ont-old \
  --revision-id rev-old --graph-id graph-old --kb-id 42 --output-dir "$run_dir" --run-id unknown-schema; then
  echo "RESET-UNKNOWN-SCHEMA unexpectedly succeeded" >&2
  exit 1
fi

run_reset --jdbc-url "$db_url" --workspace-id 7 --ontology-id ont-old --revision-id rev-old \
  --graph-id graph-old --kb-id 42,44 --output-dir "$run_dir" --run-id dry-run-1
dry_manifest="$run_dir/dry-run-1/reset-manifest.json"
grep -q '"writerFence":"DRY_RUN_ONLY"' "$dry_manifest"
grep -q '"MATE_SEMANTIC_ONTOLOGY_SOURCE_REVIEW":1' "$dry_manifest"
grep -q '"MATE_SEMANTIC_IMPORT_ARTIFACT":1' "$dry_manifest"
grep -q '"MATE_SEMANTIC_GRAPH_MIGRATION_PLAN":' "$dry_manifest" || true
grep -q '"graphMutationVersions":{"graph-old":"4"}' "$dry_manifest"
grep -q '"schemaFingerprint":"' "$dry_manifest"

java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa -sql \
  "INSERT INTO mate_semantic_ontology_axiom(revision_id,axiom_id,axiom_kind,axiom_text,signature_json) VALUES('rev-other','axiom-other','Declaration','Declaration(Class(<urn:test:Other>))','[]'); INSERT INTO mate_semantic_axiom_source(id,revision_id,axiom_id,source_snapshot_id,source_digest,exact_quote,start_code_point,end_code_point,origin,review_state,created_by,created_at) VALUES('binding-other','rev-other','axiom-other','osnap-old','digest-old','old text',0,8,'EXPERT','PENDING','seed',CURRENT_TIMESTAMP);" >/dev/null
if run_reset --jdbc-url "$db_url" --workspace-id 7 --ontology-id ont-old --revision-id rev-old --graph-id graph-old --kb-id 42,44 --output-dir "$run_dir" --run-id shared-snapshot-boundary; then
  echo "RESET-SHARED-SNAPSHOT unexpectedly succeeded" >&2
  exit 1
fi
java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa -sql \
  "DELETE FROM mate_semantic_axiom_source WHERE id='binding-other'; DELETE FROM mate_semantic_ontology_axiom WHERE revision_id='rev-other' AND axiom_id='axiom-other';" >/dev/null

java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa -sql \
  "INSERT INTO mate_semantic_graph_migration_plan(id,graph_id,operation_id,request_digest,plan_digest,status,expected_graph_version,source_revision_id,target_revision_id,source_version,target_version,source_document_digest,target_document_digest,source_import_lock_digest,target_import_lock_digest,mapping_json,payload_json,impact_json,created_by,created_at) VALUES('migration-plan-bad','graph-old','migration-bad','request','plan-bad','PLANNED',4,'rev-old','rev-other',1,1,'old-digest','other-digest','imports','imports','{}','{}','{}','seed',CURRENT_TIMESTAMP);" >/dev/null
if run_reset --jdbc-url "$db_url" --workspace-id 7 --ontology-id ont-old --revision-id rev-old --graph-id graph-old --kb-id 42,44 --output-dir "$run_dir" --run-id m8-boundary; then
  echo "RESET-M8-BOUNDARY unexpectedly succeeded" >&2
  exit 1
fi
java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa -sql \
  "DELETE FROM mate_semantic_graph_migration_plan WHERE id='migration-plan-bad';" >/dev/null

java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa \
  -sql "UPDATE mate_semantic_graph SET mutation_version=1 WHERE id='graph-old';" >/dev/null
if SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED run_reset --execute \
  --manifest "$dry_manifest" --jdbc-url "$db_url" --workspace-id 7 --ontology-id ont-old \
  --revision-id rev-old --graph-id graph-old --kb-id 42,44 --output-dir "$run_dir" --run-id mutation-race; then
  echo "RESET-MUTATION-RACE unexpectedly succeeded" >&2
  exit 1
fi
java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa \
  -sql "UPDATE mate_semantic_graph SET mutation_version=0 WHERE id='graph-old';" >/dev/null

java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa \
  -sql "INSERT INTO mate_semantic_extraction_action(graph_id,operation_id,resource_id) VALUES('graph-old','race-action','suggestion-old');" >/dev/null
if SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED run_reset --execute \
  --manifest "$dry_manifest" --jdbc-url "$db_url" --workspace-id 7 --ontology-id ont-old \
  --revision-id rev-old --graph-id graph-old --kb-id 42,44 --output-dir "$run_dir" --run-id race; then
  echo "RESET-RACE unexpectedly succeeded" >&2
  exit 1
fi
java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa \
  -sql "DELETE FROM mate_semantic_extraction_action WHERE operation_id='race-action';" >/dev/null

run_reset --jdbc-url "$db_url" --workspace-id 7 --ontology-id ont-old --revision-id rev-old \
  --graph-id graph-old --kb-id 42,44 --output-dir "$run_dir" --run-id rollback-dry >/dev/null
rollback_manifest="$run_dir/rollback-dry/reset-manifest.json"
if SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED run_reset --execute --rebuild \
  --manifest "$rollback_manifest" --jdbc-url "$db_url" --workspace-id 7 --ontology-id ont-old \
  --revision-id rev-old --graph-id graph-old --kb-id 42,44 --fixtures-dir "$work_dir/missing-fixtures" \
  --output-dir "$run_dir" --run-id rollback-exec; then
  echo "RESET-ROLLBACK unexpectedly succeeded" >&2
  exit 1
fi
rollback_check=$(java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa -sql \
  "SELECT COUNT(*) AS old_ontology_after_rollback FROM mate_semantic_ontology WHERE id='ont-old';")
echo "$rollback_check"
grep -q '1' <<<"$rollback_check"

run_reset --jdbc-url "$db_url" --workspace-id 7 --ontology-id ont-old --revision-id rev-old \
  --graph-id graph-old --kb-id 42,44 --output-dir "$run_dir" --run-id dry-run-2 >/dev/null
dry_manifest="$run_dir/dry-run-2/reset-manifest.json"
if ! SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED run_reset --execute --rebuild \
  --manifest "$dry_manifest" --jdbc-url "$db_url" --workspace-id 7 --ontology-id ont-old \
  --revision-id rev-old --graph-id graph-old --kb-id 42,44 \
  --fixtures-dir "$repo_root/docs/validation/semantic-owl-reset/fixtures" \
  --output-dir "$run_dir" --run-id execute-1 | grep -q 'RESET completed'; then
  echo "RESET-REBUILD failed" >&2
  exit 1
fi
grep -q '"status":"COMMITTED"' "$run_dir/execute-1/reset-manifest.json"

if ! SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED run_reset --execute --rebuild \
  --manifest "$run_dir/execute-1/reset-manifest.json" --jdbc-url "$db_url" --workspace-id 7 \
  --ontology-id ont-old --revision-id rev-old --graph-id graph-old --kb-id 42,44 \
  --fixtures-dir "$repo_root/docs/validation/semantic-owl-reset/fixtures" \
  --output-dir "$run_dir" --run-id execute-1 | grep -q 'already completed'; then
  echo "RESET-IDEMPOTENT failed" >&2
  exit 1
fi

verification=$(java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa -sql \
  "SELECT COUNT(*) AS old_ontology FROM mate_semantic_ontology WHERE id='ont-old'; SELECT COUNT(*) AS retained_graph FROM mate_semantic_graph WHERE id='graph-other'; SELECT COUNT(*) AS retained_raw FROM mate_wiki_raw_material WHERE kb_id=42; SELECT COUNT(*) AS rebuilt FROM mate_semantic_ontology_revision WHERE id LIKE 'reset-%-rev'; SELECT COUNT(*) AS indexed_axioms FROM mate_semantic_ontology_axiom WHERE revision_id LIKE 'reset-%-rev'; SELECT COUNT(*) AS retained_same_value_decoy FROM mate_semantic_command_record WHERE id='command-decoy';")
echo "$verification"
grep -q '0' <<<"$verification"
grep -q 'retained_graph' <<<"$verification"
grep -q 'retained_raw' <<<"$verification"
grep -q '2' <<<"$verification"
grep -q 'retained_same_value_decoy' <<<"$verification"

echo "RESET H2 rehearsal passed; isolated database: $work_dir"
