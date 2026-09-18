#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
JAR=mateclaw-server/target/mateclaw-server-2.3.0-SNAPSHOT.jar
[[ -f "$JAR" ]] || { echo 'Build the server package first.' >&2; exit 1; }
JAVA_BIN=java
if [[ -x /usr/libexec/java_home ]]; then JAVA_BIN="$(/usr/libexec/java_home -v 21)/bin/java"; fi
mkdir -p output/presales/runtime
exec "$JAVA_BIN" -jar "$JAR" --server.port=18118 \
 '--spring.datasource.url=jdbc:h2:file:./output/presales/runtime/database;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE' \
 --mateclaw.presales.enabled=true --mateclaw.semantic.enabled=true
