# GitHub Actions Reference

Use `scripts/github-action.py` from an existing scaffolded project to add a
GitHub Actions workflow that installs the test-graph skill with
skill-manager, prepares managed bindings (or repairs legacy symlinks),
discovers the graph, runs it, and uploads
`test_graph/build/validation-reports/` as an artifact.

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

New `scripts/scaffold.py` projects commit `provider-bindings.json` and generate
these ignored runtime links:

```text
test_graph/sdk
test_graph/build-logic
test_graph/standard-nodes
```

The GitHub checkout contains the portable manifest, not machine-specific
symlink records. The runner installs the provider and `prepare-bindings.py`
materializes the links before Gradle loads the SDK and build logic. Legacy
committed links still use the repair path described below.

The generated workflow does this in order:

1. Check out the repository.
2. Prepare `SKILL_MANAGER_HOME`.
3. Install `skill-manager`, JBang, and Python with Homebrew.
4. Install the test-graph skill with `skill-manager install`.
5. Prepare `test_graph/sdk`, `test_graph/build-logic`, and
   `test_graph/standard-nodes`.
6. Run `discover.py` and `run.py`.
7. Upload `test_graph/build/validation-reports/`.

## Symlink Modes

Default mode is `repair`:

```bash
<skill>/scripts/github-action.py --symlink-mode repair
```

For a managed project, the workflow runs `prepare-bindings.py`; unavailable
workspace candidates fall back to the installed skill at
`/Users/runner/.skill-manager`. For a legacy project, it rewrites checkout
symlinks in the Actions workspace to point at:

```text
$SKILL_MANAGER_HOME/skills/test-graph/project_sdk_sources/sdk
$SKILL_MANAGER_HOME/skills/test-graph/project_sdk_sources/build-logic
$SKILL_MANAGER_HOME/skills/test-graph/project_sdk_sources/standard-nodes
```

This is the portable mode because it does not require committed symlink targets
to match the runner's filesystem.

Use `preserve` only for a legacy project whose checked-in symlinks point under a fixed
skill-manager home and you want the workflow to create that same location:

```bash
<skill>/scripts/github-action.py --symlink-mode preserve
```

In preserve mode the script infers `SKILL_MANAGER_HOME` by resolving a scaffold
symlink and taking the prefix above
`skills/test-graph/project_sdk_sources/<name>`. When that home turns out to be
inside the checkout - the per-checkout `<repo>/.skill-manager` layout - the
workflow emits it as `${{ github.workspace }}/.skill-manager` and installs the
skill there, rather than baking the generating machine's absolute path into
committed YAML that no runner can reproduce.

The workflow then validates the shape by resolved directory, not by `readlink`
string:

- each entry is still a symlink;
- it resolves to `$TEST_GRAPH_SKILL_HOME/project_sdk_sources/<name>`;
- when the home is inside the workspace, its target is relative, not absolute -
  an absolute target there names one machine's checkout path, so the step fails
  and points at `migrate-bindings.py`.

A home inferred outside the workspace is emitted as an absolute path and the
relative-target assertion is skipped. Managed `provider-bindings.json` projects
reject preserve mode; use repair. Pass `--skill-manager-home <absolute-path>`
if legacy inference is not possible.

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

## Environment Repository Graphs

Environment repository validation is safe for default GitHub Actions runs:

- `environmentRepositoryGithubActionLifecycle` exercises the
  `local-github-action` target/backend with the repository-local OpenTofu shim.
  It provisions a missing branch environment, proves existing-environment
  reuse does not recreate it, resets deployment state, and keeps the
  environment active by default.
- `environmentRepositoryGithubActionLifecycleDestroy` proves destroy is
  guarded unless `TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true` is present.
- `environmentRepositoryAwsLifecycle` and
  `environmentRepositoryAwsLifecycleDestroy` are discoverable in normal CI but
  do not create AWS resources unless `TEST_GRAPH_RUN_AWS_LIFECYCLE=true` and
  AWS credentials are present. Destroy additionally requires
  `TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true` or
  `TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT=true`.

Do not set the AWS opt-in variables in broad pull-request CI unless the runner,
credentials, account limits, and teardown policy are intentionally configured
for preview infrastructure.
