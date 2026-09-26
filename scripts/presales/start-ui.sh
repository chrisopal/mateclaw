#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$TASK_ROOT/mateclaw-ui"
exec node node_modules/vite/bin/vite.js --config ../scripts/presales/vite.config.mjs --mode enterprise
