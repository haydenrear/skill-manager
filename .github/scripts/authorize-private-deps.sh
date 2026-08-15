#!/usr/bin/env bash
# Let `uv sync` fetch virtual-mcp-gateway's one private dependency.
#
# `virtual-mcp-gateway` depends on
#   tracing-skill-observability @ git+https://github.com/haydenrear/tracing_skill
# which is a PRIVATE repository. A hosted runner has no credential for it, so
# `uv sync` dies with "could not read Username for 'https://github.com'". That
# grounds every job in ci.yml that touches the gateway venv — which, because
# `InstallCommand` runs `EnsureGateway`, is every test graph that installs
# anything, not just the ones with a gateway node.
#
# Provisioning a `PRIVATE_DEPS_TOKEN` repository secret with read access to
# haydenrear/tracing_skill is one of the two fixes (the other is making the
# dependency an optional extra, which is a change to the package rather than to
# CI). This script is the whole of the CI half, and it is shared by all three
# jobs that build that venv — `test-graph`, `test-graph-browser` and
# `gateway-tests` — so that the secret is sufficient for the file rather than
# for one job in it.
#
# SCOPE IS DELIBERATELY ONE REPOSITORY PREFIX. The first version rewrote all of
# `https://github.com/`, which would have attached the token to every github
# fetch any node makes — and graph nodes run arbitrary subprocesses whose
# diagnostics are uploaded as artifacts. Review of #190 found no leak path in
# practice (`collect-test-graph-diagnostics.sh` reads neither `~/.gitconfig` nor
# the environment), but a credential scoped to what it is for costs nothing.
# `insteadOf` matches by prefix, so this covers the `.git` suffix too.
#
# No-op by construction when the secret is absent: the caller guards on
# `env.PRIVATE_DEPS_TOKEN != ''`, and this refuses rather than writing a config
# line with an empty token, which would break the anonymous path that otherwise
# still works for public dependencies.
set -euo pipefail

PRIVATE_REPO="https://github.com/haydenrear/tracing_skill"

if [ -z "${PRIVATE_DEPS_TOKEN:-}" ]; then
    echo "PRIVATE_DEPS_TOKEN is empty — leaving git credentials untouched." >&2
    exit 0
fi

git config --global \
    "url.https://x-access-token:${PRIVATE_DEPS_TOKEN}@github.com/haydenrear/tracing_skill.insteadOf" \
    "$PRIVATE_REPO"

# Never echo the configured value: it contains the token. Confirm the rewrite
# exists by its key alone.
if git config --global --get-regexp '^url\..*haydenrear/tracing_skill\.insteadof$' >/dev/null 2>&1; then
    echo "authorized: $PRIVATE_REPO (token redacted)"
else
    echo "::error::failed to configure the private-dependency rewrite"
    exit 1
fi
