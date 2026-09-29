#!/bin/zsh
cd "$(dirname "$0")" || exit 1
if [[ -x .venv/bin/python ]]; then
  exec .venv/bin/python app.py "$@"
fi
if python3 -c 'import openpyxl' >/dev/null 2>&1; then
  exec python3 app.py "$@"
fi
bundled_python="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"
if [[ -x "$bundled_python" ]] && "$bundled_python" -c 'import openpyxl' >/dev/null 2>&1; then
  exec "$bundled_python" app.py "$@"
fi
echo '请先运行：python3 -m venv .venv && .venv/bin/pip install -r requirements.txt'
read '?按回车关闭'
