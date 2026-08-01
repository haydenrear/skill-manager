# Debugging Python and uv Environment Issues

Use this reference when a test graph build fails because Python, uv, or Gradle
appears to use a different environment than expected, especially when the same
graph fails in one agent command and passes when re-run manually.

## Two Python Contexts

There are two separate Python-related contexts:

- The parent interpreter that launches the skill wrapper scripts, such as
  `discover.py` and `run.py`.
- The uv-managed interpreter that runs Python validation nodes under
  `<test_graph>/sources/`.

Do not assume they are the same interpreter. The wrapper scripts invoke Gradle;
Gradle later invokes uv nodes through the resolved uv binary.

## Prefer `python3` for Wrapper Scripts

If a wrapper command fails when launched with `python`, run the same command
with `python3` before changing Gradle, uv, or node code. In observed failures,
the fix was specifically using `python3`; `GRADLE_OPTS` did not matter in every
case.

On some systems, `python` is only an interactive shell alias:

```bash
alias python=python3
```

Aliases are shell-specific. They may not exist in non-interactive agent shells,
CI shells, subprocesses, or shells launched without the user's normal profile.
If an agent uses `python ...` and it fails oddly, retry the wrapper command with
`python3 ...` as the primary fix.

Examples:

```bash
python3 $SKILL_MANAGER_HOME/skills/test-graph/scripts/discover.py <graph>
python3 $SKILL_MANAGER_HOME/skills/test-graph/scripts/run.py <graph>
python3 $SKILL_MANAGER_HOME/skills/test-graph/scripts/run.py --all
```

Use the actual installed skill path if `$SKILL_MANAGER_HOME` is not set.

## Gradle Daemon Can Preserve Stale State

If switching from `python` to `python3` does not explain the failure, or the
graph still shows inconsistent fail-then-pass behavior, disable the Gradle
daemon while debugging:

```bash
GRADLE_OPTS='-Dorg.gradle.daemon=false' python3 $SKILL_MANAGER_HOME/skills/test-graph/scripts/discover.py <graph>
GRADLE_OPTS='-Dorg.gradle.daemon=false' python3 $SKILL_MANAGER_HOME/skills/test-graph/scripts/run.py <graph>
```

This avoids reusing a daemon that may have inherited stale environment variables,
tool paths, working-directory assumptions, or locks from an earlier invocation.

## Check What the Shell Sees

Run these from the same directory and command context as the failing graph:

```bash
type python || true
type python3 || true
python --version || true
python3 --version || true
type uv || true
uv --version || true
echo "$PATH"
echo "$VIRTUAL_ENV"
echo "$SKILL_MANAGER_HOME"
```

If `type python` reports an alias, do not rely on `python` in agent-facing
commands. Use `python3` for the wrapper script.

## Check What Test Graph Resolves

The Gradle plugin logs the resolved jbang and uv binaries during task execution:

```text
[toolchain] jbang=... (path: ...)
[toolchain] uv=... (path: ...)
```

Those paths are the runtimes used for node execution. If uv resolution is
suspect, compare the logged uv path with:

```bash
type uv || true
uv --version || true
```

Downloaded tool binaries live under `<test_graph>/.bin/`. Delete that directory
to force redownload of pinned tool versions.

## Debug a Single uv Node

When Gradle fails while describing or running one Python node, invoke the node
directly with uv:

```bash
uv run sources/my_node.py --describe-out=/tmp/spec.json
cat /tmp/spec.json
```

If direct uv execution passes but the Gradle graph fails, first make sure the
skill wrapper was launched with `python3`. Then compare the Gradle toolchain
log, `PATH`, `VIRTUAL_ENV`, and daemon setting. Re-run with
`GRADLE_OPTS='-Dorg.gradle.daemon=false'` before changing node code if stale
daemon state is still plausible.
