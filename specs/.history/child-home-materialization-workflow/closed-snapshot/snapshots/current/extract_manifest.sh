#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 spec_manifest.yaml" >&2
  exit 2
fi

manifest_path="$1"
if [ ! -f "$manifest_path" ]; then
  echo "manifest not found: $manifest_path" >&2
  exit 1
fi

python_bin=""
if command -v python3 >/dev/null 2>&1; then
  python_bin="$(command -v python3)"
elif command -v python >/dev/null 2>&1; then
  python_bin="$(command -v python)"
else
  echo "python3 or python was not found." >&2
  exit 1
fi

if [ -n "${SPEC_DOUBLE_COMPILER_HOME:-}" ]; then
  script="$SPEC_DOUBLE_COMPILER_HOME/scripts/extract_spec_manifest.py"
  if [ -f "$script" ]; then
    exec "$python_bin" "$script" "$manifest_path"
  fi
fi

skill_home="${SKILL_MANAGER_HOME:-$HOME/.skill-manager}"
script="$skill_home/skills/spec-double-compiler/scripts/extract_spec_manifest.py"
if [ -f "$script" ]; then
  exec "$python_bin" "$script" "$manifest_path"
fi

echo "spec-double-compiler manifest extractor was not found. Install the skill with skill-manager or set SPEC_DOUBLE_COMPILER_HOME." >&2
exit 1
