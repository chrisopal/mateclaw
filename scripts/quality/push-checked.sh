#!/bin/sh
# Keep the SSH transport alive while the pre-push hook runs the full gate.
set -eu
if [ -z "${GIT_SSH_COMMAND:-}" ]; then
  GIT_SSH_COMMAND='ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=20'
  export GIT_SSH_COMMAND
fi
exec git push "$@"
