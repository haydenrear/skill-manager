#!/usr/bin/env bash
# Wrapper for env.py — locates `uv` (skill-manager's bundled copy first, then
# system PATH) and invokes the script via `uv run` so the right Python and
# tomllib are guaranteed without polluting the agent's environment.
#
# Usage: env.sh [--skills NAME ...] [--pretty]
# Output: same JSON as env.py — see scripts/env.py for the contract.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
env_py="${script_dir}/env.py"

if [[ ! -f "${env_py}" ]]; then
    echo "env.sh: env.py not found at ${env_py}" >&2
    exit 2
fi

home="${SKILL_MANAGER_HOME:-${HOME}/.skill-manager}"
bundled_uv="${home}/pm/uv/current/bin/uv"

uv_bin=""
if [[ -x "${bundled_uv}" ]]; then
    uv_bin="${bundled_uv}"
elif command -v uv >/dev/null 2>&1; then
    uv_bin="$(command -v uv)"
else
    cat >&2 <<EOF
env.sh: \`uv\` is required but was not found.

Looked in:
  - ${bundled_uv}   (skill-manager's bundled uv)
  - PATH           (system uv)

Install one of:
  - Run \`skill-manager install\` for any skill with a pip CLI dependency —
    that triggers the bundled-uv install.
  - Or install uv directly: https://github.com/astral-sh/uv
EOF
    exit 3
fi

exec "${uv_bin}" run "${env_py}" "$@"
