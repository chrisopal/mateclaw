#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT/mateclaw-ui"
# Keep the persistent acceptance runtime on the approved enterprise UI.
export VITE_UI_PROFILE=enterprise
exec ./node_modules/.bin/vite --mode enterprise --config ../scripts/semantic-owl-runtime/vite.config.mjs --host 127.0.0.1 --port 5189
