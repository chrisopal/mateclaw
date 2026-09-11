#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"
STATE_DIR=${MATECLAW_SEMANTIC_RUNTIME_DIR:-"$ROOT/data/semantic-owl-runtime"}
DATABASE=${MATECLAW_SEMANTIC_DATABASE:-"$STATE_DIR/database"}
case "$DATABASE" in
  /*) ;;
  *) echo 'Database path must be absolute (without the .mv.db suffix).' >&2; exit 1 ;;
esac
case "$DATABASE" in
  *';'*) echo 'Database path cannot contain JDBC options.' >&2; exit 1 ;;
esac
# Never turn a missing user database into an apparently successful empty installation.
if [ ! -f "$DATABASE.mv.db" ]; then
  echo "Database not found: $DATABASE.mv.db" >&2
  echo 'Restore a verified database or explicitly set MATECLAW_SEMANTIC_DATABASE to its existing path.' >&2
  exit 1
fi
mkdir -p "$STATE_DIR"
RUNTIME_DIR=$(mktemp -d "$STATE_DIR/server-XXXXXXXX")
RUNTIME_JAR="$RUNTIME_DIR/server.jar"
cp mateclaw-server/target/mateclaw-server-2.3.0-SNAPSHOT.jar "$RUNTIME_JAR"
exec /Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home/bin/java -jar "$RUNTIME_JAR" --server.port=18109 "--spring.datasource.url=jdbc:h2:file:$DATABASE;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;IFEXISTS=TRUE" --mateclaw.semantic.enabled=true --mateclaw.semantic.extraction.enabled=false
