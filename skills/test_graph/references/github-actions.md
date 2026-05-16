# GitHub Actions Reference

Use `scripts/github-action.py` from an existing scaffolded project to add a
GitHub Actions workflow that installs the test-graph skill with
skill-manager, resolves the scaffold symlinks, discovers the graph, runs it,
and uploads `test_graph/build/validation-reports/` as an artifact.

For the normal local workflow, start with [`workflows.md`](workflows.md).

## Scaffold a Workflow

From the project repo root, or from anywhere inside `test_graph/`:

```bash
<skill>/scripts/github-action.py
```

This writes:

```text
.github/workflows/test-graph.yml
```

The default workflow runs every registered graph:

```bash
"$TEST_GRAPH_SKILL_HOME/scripts/run.py" --all
```

To run one or more named graph tasks instead:

```bash
<skill>/scripts/github-action.py --graph smoke
<skill>/scripts/github-action.py --graph smoke --graph regression
```

The generated workflow is intentionally ordinary YAML. Review the path
triggers after scaffolding; projects usually need to add their real source
directories in addition to `test_graph/**`.

## Why the Workflow Installs the Skill

`scripts/scaffold.py` creates these scaffold entries as symlinks:

```text
test_graph/sdk
test_graph/build-logic
```

Those links point into the installed test-graph skill, not into the consuming
project. A GitHub checkout only contains the symlink records. The runner must
install the test-graph skill before Gradle can load the SDK and build logic.

The generated workflow does this in order:

1. Check out the repository.
2. Prepare `SKILL_MANAGER_HOME`.
3. Install `skill-manager`, JBang, and Python with Homebrew.
4. Install the test-graph skill with `skill-manager install`.
5. Resolve `test_graph/sdk` and `test_graph/build-logic`.
6. Run `discover.py` and `run.py`.
7. Upload `test_graph/build/validation-reports/`.

## Symlink Modes

Default mode is `repair`:

```bash
<skill>/scripts/github-action.py --symlink-mode repair
```

The workflow installs the skill at `/Users/runner/.skill-manager`, then
rewrites the checkout symlinks in the Actions workspace to point at:

```text
$SKILL_MANAGER_HOME/skills/test-graph/project_sdk_sources/sdk
$SKILL_MANAGER_HOME/skills/test-graph/project_sdk_sources/build-logic
```

This is the most portable mode because it does not require the committed
symlink targets to match the runner's filesystem.

Use `preserve` when the checked-in symlinks already point under a fixed
skill-manager home and you want the workflow to create that same location:

```bash
<skill>/scripts/github-action.py --symlink-mode preserve
```

In preserve mode the script infers `SKILL_MANAGER_HOME` from a symlink target
like:

```text
/Users/hayde/.skill-manager/skills/test-graph/project_sdk_sources/sdk
```

and the workflow validates that the checked-in symlinks resolve to the
installed skill. Pass `--skill-manager-home <absolute-path>` if inference is
not possible.

## Private Installs

If the test-graph skill coordinate or any dependent skill is private, expose a
repository secret and scaffold with:

```bash
<skill>/scripts/github-action.py --token-secret SKILL_MANAGER_GITHUB_TOKEN
```

The workflow maps that secret to:

```text
SKILL_MANAGER_GITHUB_TOKEN
GH_TOKEN
GITHUB_TOKEN
```

and fails early if the secret is missing.

## Useful Options

```bash
<skill>/scripts/github-action.py <repo-root>
<skill>/scripts/github-action.py --workflow-name validation.yml
<skill>/scripts/github-action.py --runner macos-latest
<skill>/scripts/github-action.py --timeout-minutes 90
<skill>/scripts/github-action.py --skill-coordinate github:haydenrear/test_graph_skill
<skill>/scripts/github-action.py --artifact-name validation-reports
<skill>/scripts/github-action.py --path app/**
<skill>/scripts/github-action.py --force
```

The generated setup assumes a macOS runner because it installs
`skill-manager`, JBang, and Python through Homebrew, matching the current
known-good CI path.
