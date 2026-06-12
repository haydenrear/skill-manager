#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 SPEC.tla MC.cfg" >&2
  exit 2
fi

spec_path="$1"
cfg_path="$2"

if [ ! -f "$spec_path" ]; then
  echo "spec not found: $spec_path" >&2
  exit 1
fi

if [ ! -f "$cfg_path" ]; then
  echo "config not found: $cfg_path" >&2
  exit 1
fi

spec_dir="$(cd "$(dirname "$spec_path")" && pwd)"
spec_file="$(basename "$spec_path")"
cfg_file="$(basename "$cfg_path")"

cd "$spec_dir"

if command -v tlc2 >/dev/null 2>&1; then
  exec tlc2 -config "$cfg_file" "$spec_file"
fi

skill_home="${SKILL_MANAGER_HOME:-$HOME/.skill-manager}"
skill_tlc2="$skill_home/bin/cli/tlc2"
if [ -x "$skill_tlc2" ]; then
  exec "$skill_tlc2" -config "$cfg_file" "$spec_file"
fi

if [ -n "${TLA2TOOLS_JAR:-}" ]; then
  exec java -cp "$TLA2TOOLS_JAR" tlc2.TLC -config "$cfg_file" "$spec_file"
fi

echo "tlc2 was not found. Install spec-double-compiler with skill-manager, add tlc2 to PATH, or set TLA2TOOLS_JAR." >&2
exit 1
