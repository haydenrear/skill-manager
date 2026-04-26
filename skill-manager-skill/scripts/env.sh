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

# Resolve $home/pm/uv/current — symlink on most filesystems, but
# PackageManagerRuntime#setCurrent falls back to writing a plain text
# pointer file (containing the version string) on filesystems without
# symlink support. Mirror that fallback here so env.sh stays usable
# in either layout.
current_marker="${home}/pm/uv/current"
bundled_uv=""
if [[ -L "${current_marker}" ]]; then
    bundled_uv="${current_marker}/bin/uv"
elif [[ -f "${current_marker}" ]]; then
    bundled_version="$(tr -d '[:space:]' < "${current_marker}")"
    if [[ -n "${bundled_version}" ]]; then
        bundled_uv="${home}/pm/uv/${bundled_version}/bin/uv"
    fi
fi

uv_bin=""
if [[ -n "${bundled_uv}" && -x "${bundled_uv}" ]]; then
    uv_bin="${bundled_uv}"
elif command -v uv >/dev/null 2>&1; then
    uv_bin="$(command -v uv)"
else
    cat >&2 <<EOF
env.sh: \`uv\` is required but was not found.

Looked in:
  - ${bundled_uv:-${current_marker}/bin/uv}   (skill-manager's bundled uv)
  - PATH           (system uv)

Install one of:
  - Run \`skill-manager install\` for any skill with a pip CLI dependency —
    that triggers the bundled-uv install.
  - Or install uv directly: https://github.com/astral-sh/uv
EOF
    exit 3
fi

exec "${uv_bin}" run "${env_py}" "$@"
