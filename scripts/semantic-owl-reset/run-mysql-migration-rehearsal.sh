#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
container=${MYSQL_RESET_CONTAINER:-mateclaw-mysql}
if ! docker inspect "$container" >/dev/null 2>&1; then
  echo "MySQL rehearsal skipped: container '$container' is unavailable" >&2
  exit 2
fi
root_password=$(docker inspect "$container" --format '{{range .Config.Env}}{{println .}}{{end}}' | awk -F= '$1=="MYSQL_ROOT_PASSWORD"{print substr($0,index($0,"=")+1)}')
if [[ -z "$root_password" ]]; then
  echo "MySQL rehearsal skipped: root password is not exposed by the configured container environment" >&2
  exit 2
fi

work_dir=$(mktemp -d /tmp/semantic-owl-reset-mysql-migrations.XXXXXX)
db_name="semantic_reset_flyway_$(date +%s)"
classes_dir="$work_dir/classes"
run_dir="$work_dir/runs"
mkdir -p "$classes_dir" "$run_dir"
cleanup() {
  docker exec -e MYSQL_PWD="$root_password" "$container" mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS \`$db_name\`;" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$root_password" "$container" mysql -h127.0.0.1 -uroot "$db_name" "$@"
}

docker exec -e MYSQL_PWD="$root_password" "$container" mysql -h127.0.0.1 -uroot -e "CREATE DATABASE \`$db_name\`;" >/dev/null
cat >"$work_dir/base.sql" <<'SQL'
CREATE TABLE mate_tool(id BIGINT PRIMARY KEY,name VARCHAR(128),display_name VARCHAR(255),description TEXT,tool_type VARCHAR(32),bean_name VARCHAR(128),icon VARCHAR(128),enabled BOOLEAN,builtin BOOLEAN,create_time TIMESTAMP,update_time TIMESTAMP,deleted BOOLEAN);
SQL
mysql_exec <"$work_dir/base.sql"

for migration in "$repo_root"/mateclaw-server/src/main/resources/db/migration/mysql/V19{1,2,3,4,5,6,7,8,9}__*.sql "$repo_root"/mateclaw-server/src/main/resources/db/migration/mysql/V20{0,1,2,3,4,6,7}__*.sql; do
  [[ -f "$migration" ]] || continue
  mysql_exec <"$migration"
done

# Validate retirement against exact migrated metadata before seeding RESET fixtures.
if [[ "${SEMANTIC_RESET_TEST_COLUMN_EXECUTOR:-0}" == "1" ]]; then
  retirement_cp=$(python3 - "$repo_root" <<'PYCP'
import sys,xml.etree.ElementTree as E
from pathlib import Path
p=Path(sys.argv[1])/'mateclaw-server/target/surefire-reports/TEST-vip.mate.semantic.SemanticOntologyIntegrationTest.xml'
r=E.parse(p).getroot()
print(next(x.attrib['value'] for x in r.findall('properties/property') if x.attrib['name']=='java.class.path'))
PYCP
)
  javac -proc:none -cp "$retirement_cp" -d "$classes_dir" \
    "$repo_root/scripts/semantic-owl-reset/src/LegacyColumnRetirementGuard.java" \
    "$repo_root/scripts/semantic-owl-reset/src/LegacyColumnRetirement.java"
  for action in plan apply restore; do
    SEMANTIC_RESET_DB_USER=root SEMANTIC_RESET_DB_PASSWORD="$root_password" \
      SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED \
      java -cp "$classes_dir:$retirement_cp" LegacyColumnRetirement "$action" \
      "jdbc:mysql://127.0.0.1:13306/$db_name?useSSL=false&allowPublicKeyRetrieval=true" \
      "$work_dir/retirement.properties"
  done
fi

cat >"$work_dir/seed.sql" <<'SQL'
INSERT INTO mate_semantic_ontology(id,workspace_id,name,description,latest_version,latest_revision_id,draft_id,draft_counter,updated_at) VALUES('ont-old',7,'old test ontology','reset target',1,'rev-old',NULL,1,CURRENT_TIMESTAMP(6));
INSERT INTO mate_semantic_ontology_revision(id,ontology_id,version,draft_version,revision_state,draft_slot,name,description,definition_json,base_revision_id,available_for_new_bindings,published_at,published_by,publication_note,document_text,document_syntax,document_digest,ontology_iri,version_iri,import_lock_digest,model_schema,imports_json,policy_json) VALUES('rev-old','ont-old',1,1,'PUBLISHED',NULL,'old test ontology','reset target',NULL,NULL,TRUE,CURRENT_TIMESTAMP(6),'seed','', 'Ontology(<urn:test:old>)','FUNCTIONAL','old-digest','urn:test:old',NULL,'imports','owl-document-v1','[]','{"version":"old","rules":[]}');
INSERT INTO mate_semantic_graph(id,workspace_id,kb_id,ontology_revision_id,enabled,mutation_version,created_at,updated_at) VALUES('graph-old',7,99,'rev-old',TRUE,4,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6));
INSERT INTO mate_semantic_ontology(id,workspace_id,name,description,latest_version,latest_revision_id,draft_id,draft_counter,updated_at) VALUES('ont-other',7,'other ontology','retained',1,'rev-other',NULL,1,CURRENT_TIMESTAMP(6));
INSERT INTO mate_semantic_ontology_revision(id,ontology_id,version,draft_version,revision_state,draft_slot,name,description,definition_json,base_revision_id,available_for_new_bindings,published_at,published_by,publication_note,document_text,document_syntax,document_digest,ontology_iri,version_iri,import_lock_digest,model_schema,imports_json,policy_json) VALUES('rev-other','ont-other',1,1,'PUBLISHED',NULL,'other ontology','retained',NULL,NULL,TRUE,CURRENT_TIMESTAMP(6),'seed','', 'Ontology(<urn:test:other>)','FUNCTIONAL','other-digest','urn:test:other',NULL,'imports','owl-document-v1','[]','{"version":"other","rules":[]}');
INSERT INTO mate_semantic_graph(id,workspace_id,kb_id,ontology_revision_id,enabled,mutation_version,created_at,updated_at) VALUES('graph-other',7,43,'rev-other',TRUE,1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6));
INSERT INTO mate_semantic_source_change_run(id,graph_id,operation_id,status,expected_graph_version,request_digest,baseline_json,created_by,created_at,updated_at) VALUES('change-run-old','graph-old','source-change-old','OPEN',4,'request-digest','{}','seed',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6));
INSERT INTO mate_semantic_source_change_lock(graph_id,source_kind,source_ref,touched_at) VALUES('graph-old','WIKI','source-old',CURRENT_TIMESTAMP(6));
INSERT INTO mate_semantic_source_change(id,run_id,graph_id,source_kind,source_ref,old_snapshot_id,new_snapshot_id,old_digest,new_digest,source_state,observed_graph_version,created_by,created_at) VALUES('change-old','change-run-old','graph-old','WIKI','source-old',NULL,NULL,'digest-old','digest-new','CHANGED',4,'seed',CURRENT_TIMESTAMP(6));
INSERT INTO mate_semantic_source_change_item(id,change_id,graph_id,source_kind,source_ref,item_kind,item_id,item_revision,old_snapshot_id,new_snapshot_id,old_digest,new_digest,source_state,review_state,decision,reason,decision_operation_id,observed_graph_version,created_by,created_at) VALUES('change-item-old','change-old','graph-old','WIKI','source-old','ASSERTION','item-old',1,NULL,NULL,'digest-old','digest-new','CHANGED','PENDING',NULL,NULL,NULL,4,'seed',CURRENT_TIMESTAMP(6));
INSERT INTO mate_semantic_graph_migration_plan(id,graph_id,operation_id,request_digest,plan_digest,status,expected_graph_version,source_revision_id,target_revision_id,source_version,target_version,source_document_digest,target_document_digest,source_import_lock_digest,target_import_lock_digest,mapping_json,payload_json,impact_json,created_by,created_at) VALUES('migration-plan-old','graph-old','migration-old','request','plan','PLANNED',4,'rev-old','rev-old',1,1,'old-digest','old-digest','imports','imports','{}','{}','{}','seed',CURRENT_TIMESTAMP(6));
SQL
mysql_exec <"$work_dir/seed.sql"
# Exercise RESET against the post-retirement schema in this disposable database only.
if [[ "${SEMANTIC_RESET_TEST_RETIRED_SCHEMA:-0}" == "1" ]]; then
  mysql_exec -e "ALTER TABLE mate_semantic_ontology_revision DROP COLUMN definition_json"
fi


javac -Xlint:all -d "$classes_dir" "$repo_root/scripts/semantic-owl-reset/src/SemanticOwlReset.java"
mysql_jar=$(find "$HOME/.m2/repository/com/mysql/mysql-connector-j" -name 'mysql-connector-j-*.jar' | sort | tail -1)
url="jdbc:mysql://127.0.0.1:13306/$db_name?useSSL=false&allowPublicKeyRetrieval=true"
run_reset() {
  SEMANTIC_RESET_DB_PASSWORD="$root_password" java -cp "$classes_dir:$mysql_jar" SemanticOwlReset "$@"
}

run_reset --jdbc-url "$url" --user root --password-env SEMANTIC_RESET_DB_PASSWORD --workspace-id 7 --ontology-id ont-old --revision-id rev-old --graph-id graph-old --kb-id 42,44,99 --output-dir "$run_dir" --run-id flyway-dry >/dev/null
manifest="$run_dir/flyway-dry/reset-manifest.json"
grep -q '"MATE_SEMANTIC_SOURCE_CHANGE_RUN":1' "$manifest"
grep -q '"MATE_SEMANTIC_SOURCE_CHANGE":1' "$manifest"
grep -q '"MATE_SEMANTIC_SOURCE_CHANGE_ITEM":1' "$manifest"
grep -q '"MATE_SEMANTIC_SOURCE_CHANGE_LOCK":1' "$manifest"
SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED run_reset --execute --rebuild --allow-non-h2 --manifest "$manifest" --jdbc-url "$url" --user root --password-env SEMANTIC_RESET_DB_PASSWORD --workspace-id 7 --ontology-id ont-old --revision-id rev-old --graph-id graph-old --kb-id 42,44,99 --fixtures-dir "$repo_root/docs/validation/semantic-owl-reset/fixtures" --output-dir "$run_dir" --run-id flyway-exec >/dev/null
committed="$run_dir/flyway-exec/reset-manifest.json"
backup=$(grep -o '"backupPath":"[^"]*"' "$committed" | cut -d'"' -f4)
mysql_exec -e "ALTER TABLE mate_semantic_ontology ADD COLUMN reset_schema_probe VARCHAR(8);"
if SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED run_reset --execute --allow-non-h2 --manifest "$committed" --restore-backup "$backup" --jdbc-url "$url" --user root --password-env SEMANTIC_RESET_DB_PASSWORD --workspace-id 7 --ontology-id ont-old --revision-id rev-old --graph-id graph-old --kb-id 42,44,99 --output-dir "$run_dir" --run-id flyway-restore-schema-race; then
  echo "RESET-SCHEMA-RACE unexpectedly succeeded" >&2
  exit 1
fi
mysql_exec -e "ALTER TABLE mate_semantic_ontology DROP COLUMN reset_schema_probe;"
SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED run_reset --execute --allow-non-h2 --manifest "$committed" --restore-backup "$backup" --jdbc-url "$url" --user root --password-env SEMANTIC_RESET_DB_PASSWORD --workspace-id 7 --ontology-id ont-old --revision-id rev-old --graph-id graph-old --kb-id 42,44,99 --output-dir "$run_dir" --run-id flyway-restore
readback=$(mysql_exec -N -e "SELECT 'restored_old_ontology',COUNT(*) FROM mate_semantic_ontology WHERE id='ont-old'; SELECT 'restored_source_change_run',COUNT(*) FROM mate_semantic_source_change_run WHERE id='change-run-old'; SELECT 'retained_graph',COUNT(*) FROM mate_semantic_graph WHERE id='graph-other'; SELECT 'rebuilt',COUNT(*) FROM mate_semantic_ontology_revision WHERE id LIKE 'reset-%-rev';")
echo "$readback"
grep -q 'restored_old_ontology.*1' <<<"$readback"
grep -q 'restored_source_change_run.*1' <<<"$readback"
grep -q 'retained_graph.*1' <<<"$readback"
grep -q 'rebuilt.*2' <<<"$readback"
echo "RESET MySQL V191-V207 migration rehearsal passed; isolated database was dropped: $db_name"
