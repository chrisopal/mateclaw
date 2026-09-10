#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"
mkdir -p /tmp/mateclaw-owl-runtime
RUNTIME_DIR=$(mktemp -d /tmp/mateclaw-owl-runtime/server-XXXXXXXX)
RUNTIME_JAR="$RUNTIME_DIR/server.jar"
cp mateclaw-server/target/mateclaw-server-2.3.0-SNAPSHOT.jar "$RUNTIME_JAR"
exec /Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home/bin/java -jar "$RUNTIME_JAR" --server.port=18109 '--spring.datasource.url=jdbc:h2:file:/tmp/mateclaw-owl-runtime/database;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE' --mateclaw.semantic.enabled=true --mateclaw.semantic.extraction.enabled=false
