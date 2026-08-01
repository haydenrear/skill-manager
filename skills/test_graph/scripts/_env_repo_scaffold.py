from __future__ import annotations

import os
import stat
from dataclasses import dataclass
from pathlib import Path


DEFAULT_TEMPLATE = "branch-preview"


@dataclass(frozen=True)
class TargetTemplate:
    target: str
    backend: str
    filename: str
    display_name: str
    notes: str


TARGETS: dict[str, TargetTemplate] = {
    "local-preview": TargetTemplate(
        target="local-preview",
        backend="local",
        filename="local.tf",
        display_name="Local k3d preview",
        notes="Starter locals for a developer-machine k3d cluster.",
    ),
    "local-github-action": TargetTemplate(
        target="local-github-action",
        backend="github-action",
        filename="local-github-action.tf",
        display_name="GitHub Actions local preview",
        notes="Starter locals for CI-safe local preview validation.",
    ),
    "aws-preview": TargetTemplate(
        target="aws-preview",
        backend="aws",
        filename="aws.tf",
        display_name="AWS preview",
        notes="Starter locals for an explicitly selected AWS preview environment.",
    ),
}


def normalize_template(value: str) -> str:
    template = value.strip().strip("/")
    if template.startswith("templates/"):
        template = template.removeprefix("templates/")
    parts = template.split("/") if template else []
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise ValueError("template must be a relative path under templates/ without '.', '..', or empty segments")
    return "/".join(parts)


def template_path(repo_root: Path, template: str) -> Path:
    return repo_root / "templates" / normalize_template(template)


def target_template(target: str) -> TargetTemplate:
    try:
        return TARGETS[target]
    except KeyError as exc:
        valid = ", ".join(sorted(TARGETS))
        raise ValueError(f"unsupported target {target!r}; expected one of: {valid}") from exc


def write_file(
    path: Path,
    text: str,
    *,
    force: bool = False,
    executable: bool = False,
    preserve_existing: bool = False,
) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if preserve_existing:
            if executable:
                make_executable(path)
            return False
        existing = path.read_text(encoding="utf-8")
        if existing == text:
            if executable:
                make_executable(path)
            return False
        if not force:
            raise FileExistsError(f"{path} already exists with different content; pass --force to overwrite")
    path.write_text(text, encoding="utf-8")
    if executable:
        make_executable(path)
    return True


def make_executable(path: Path) -> None:
    mode = path.stat().st_mode
    path.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def ensure_repository(
    repo_root: Path,
    template: str,
    *,
    force: bool = False,
    preserve_existing: bool = False,
) -> list[Path]:
    repo_root.mkdir(parents=True, exist_ok=True)
    created: list[Path] = []
    for relative, text in {
        "README.md": readme_text(template),
        ".gitignore": gitignore_text(),
        f"templates/{normalize_template(template)}/main.tf": main_tf_text(),
        f"templates/{normalize_template(template)}/variables.tf": variables_tf_text(),
        f"templates/{normalize_template(template)}/outputs.tf": outputs_tf_text(),
    }.items():
        path = repo_root / relative
        if write_file(path, text, force=force, preserve_existing=preserve_existing):
            created.append(path)
    return created


def add_environment_template(repo_root: Path, template: str, target: str, *, force: bool = False) -> Path:
    selected = target_template(target)
    ensure_repository(repo_root, template, preserve_existing=True)
    path = template_path(repo_root, template) / selected.filename
    write_file(path, target_tf_text(selected), force=force)
    return path


def add_tofu_shim(repo_root: Path, *, force: bool = False) -> Path:
    path = repo_root / "bin" / "tofu"
    write_file(path, tofu_shim_text(), force=force, executable=True)
    return path


def add_lifecycle_node_templates(
    sources_dir: Path,
    *,
    target: str,
    runtime: str = "all",
    force: bool = False,
) -> list[Path]:
    selected = target_template(target)
    runtimes = ["uv", "jbang"] if runtime == "all" else [runtime]
    invalid = [value for value in runtimes if value not in {"uv", "jbang"}]
    if invalid:
        raise ValueError("runtime must be one of: uv, jbang, all")

    created: list[Path] = []
    if "uv" in runtimes:
        for filename, text in python_lifecycle_templates(selected).items():
            path = sources_dir / filename
            write_file(path, text, force=force)
            created.append(path)
    if "jbang" in runtimes:
        for filename, text in java_lifecycle_templates(selected).items():
            path = sources_dir / filename
            write_file(path, text, force=force)
            created.append(path)
    return created


def readme_text(template: str) -> str:
    contract_path = f"templates/{normalize_template(template)}"
    return f"""# Test Graph Environment Repository

This repository contains OpenTofu templates for branch-scoped test graph
environments. Use `{contract_path}` as the `environmentRepository.template`
value in a test graph node.

Required outputs:

- `EnvironmentId`
- `KUBECONFIG`
- `KUBECONTEXT`

Targets can coexist in one template directory as target-specific files:

- `local.tf` for `local-preview` with backend `local`
- `local-github-action.tf` for `local-github-action` with backend `github-action`
- `aws.tf` for `aws-preview` with backend `aws`

Initialize this directory as an ordinary Git repository before using it from
test graph contract tests:

```bash
git init
git add .
git commit -m "Initial environment repository"
```
"""


def gitignore_text() -> str:
    return """.terraform/
.terraform.lock.hcl
terraform.tfstate
terraform.tfstate.backup
generated/
*.tfplan
"""


def main_tf_text() -> str:
    return """terraform {
  required_version = ">= 1.6.0"
}

locals {
  environment_id  = var.environment_id
  kubeconfig_path = "${path.module}/generated/${var.target}/kubeconfig"
  kubecontext     = "test-graph-${var.target}-${var.branch}"
}
"""


def variables_tf_text() -> str:
    return """variable "environment_id" {
  description = "Branch-scoped test graph environment id."
  type        = string
}

variable "branch" {
  description = "Feature branch that owns this preview environment."
  type        = string
}

variable "target" {
  description = "Environment target selected by the test graph node."
  type        = string
  default     = "local-preview"
}

variable "backend" {
  description = "Execution backend selected by the test graph node."
  type        = string
  default     = "local"
}
"""


def outputs_tf_text() -> str:
    return """output "EnvironmentId" {
  description = "Stable branch environment identifier."
  value       = local.environment_id
}

output "KUBECONFIG" {
  description = "Kubeconfig path for downstream Kubernetes and Helm nodes."
  value       = local.kubeconfig_path
}

output "KUBECONTEXT" {
  description = "Kubernetes context name for downstream Kubernetes and Helm nodes."
  value       = local.kubecontext
}
"""


def target_tf_text(target: TargetTemplate) -> str:
    prefix = target.target.replace("-", "_")
    return f"""# {target.display_name}
#
# {target.notes}
# Replace these locals with provider resources or modules for your environment.

locals {{
  {prefix}_enabled      = var.target == "{target.target}" && var.backend == "{target.backend}"
  {prefix}_cluster_name = "tg-${{var.branch}}-{target.target}"
}}
"""


def tofu_shim_text() -> str:
    return """#!/usr/bin/env sh
set -eu

cmd="${1:-}"
shift || true

target="${TF_VAR_target:-${TEST_GRAPH_ENVIRONMENT_TARGET:-local-preview}}"
backend="${TF_VAR_backend:-${TEST_GRAPH_ENVIRONMENT_BACKEND:-local}}"
branch="${TF_VAR_branch:-${TEST_GRAPH_FEATURE_BRANCH:-local}}"
environment_id="${TF_VAR_environment_id:-${TEST_GRAPH_BRANCH_ENVIRONMENT_ID:-unknown}}"
kubecontext="test-graph-${target}-${branch}"

case "$cmd" in
  init)
    mkdir -p .terraform
    ;;
  apply)
    rm -rf "generated/${target}"
    mkdir -p "generated/${target}"
    printf 'apiVersion: v1\\nkind: Config\\ncurrent-context: %s\\n' "$kubecontext" > "generated/${target}/kubeconfig"
    printf '%s\\n' "$backend" > "generated/${target}/backend"
    ;;
  output)
    cat <<JSON
{"EnvironmentId":{"sensitive":false,"type":"string","value":"${environment_id}"},"KUBECONFIG":{"sensitive":false,"type":"string","value":"${PWD}/generated/${target}/kubeconfig"},"KUBECONTEXT":{"sensitive":false,"type":"string","value":"${kubecontext}"}}
JSON
    ;;
  destroy)
    rm -rf generated .terraform
    ;;
  *)
    echo "unsupported tofu command: $cmd" >&2
    exit 64
    ;;
esac
"""


def python_lifecycle_templates(target: TargetTemplate) -> dict[str, str]:
    return {
        "deploy_cluster.py": python_lifecycle_template(
            "branch.environment.python.deploy-cluster",
            "deploy_cluster",
            f'"{target.target}", "{target.backend}"',
        ),
        "reset_node.py": python_lifecycle_template(
            "branch.environment.python.reset-node",
            "reset_node",
            f'"{target.target}", "{target.backend}"',
        ),
        "delete_cluster.py": python_lifecycle_template(
            "branch.environment.python.delete-cluster",
            "delete_cluster",
            f'"{target.target}", "{target.backend}", destroy_requested=destroy_requested()',
            imports="import os\n",
            helpers="""DESTROY_KEYS = ("TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT", "TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT")


def destroy_requested() -> bool:
    return any(os.environ.get(key, "").lower() in {"1", "true", "yes", "y"} for key in DESTROY_KEYS)


""",
        ),
    }


def python_lifecycle_template(node_id: str, function: str, args: str, imports: str = "", helpers: str = "") -> str:
    return f"""# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = {{ path = "../sdk/python", editable = true }}
# ///
from __future__ import annotations

{imports}\
from testgraphsdk import NodeResult, NodeSpec, {function}, node


SPEC = NodeSpec("{node_id}").kind("action").tags("environment", "lifecycle")


{helpers}\
@node(SPEC)
def main(ctx):
    plan = {function}({args})
    result = NodeResult.pass_(ctx.node_id).assertion("lifecycle_plan_resolved", bool(plan.dispatch_key))
    for key, value in plan.published().items():
        result.publish(key, value)
    return result


if __name__ == "__main__":
    main()
"""


def java_lifecycle_templates(target: TargetTemplate) -> dict[str, str]:
    return {
        "DeployCluster.java": java_lifecycle_template(
            "DeployCluster",
            "branch.environment.java.deploy-cluster",
            f'ClusterLifecycle.deployCluster("{target.target}", "{target.backend}", false)',
        ),
        "ResetNode.java": java_lifecycle_template(
            "ResetNode",
            "branch.environment.java.reset-node",
            f'ClusterLifecycle.resetNode("{target.target}", "{target.backend}", false, false)',
        ),
        "DeleteCluster.java": java_lifecycle_template(
            "DeleteCluster",
            "branch.environment.java.delete-cluster",
            f'ClusterLifecycle.deleteCluster("{target.target}", "{target.backend}", destroyRequested())',
            helpers="""    private static boolean destroyRequested() {
        for (String key : new String[] {"TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT", "TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT"}) {
            String value = System.getenv(key);
            if (value != null && java.util.Set.of("1", "true", "yes", "y").contains(value.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

""",
        ),
    }


def java_lifecycle_template(class_name: str, node_id: str, expression: str, helpers: str = "") -> str:
    return f"""///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import java.util.Map;

import com.hayden.testgraphsdk.sdk.ClusterLifecycle;
import com.hayden.testgraphsdk.sdk.ClusterLifecyclePlan;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

public class {class_name} {{
    private static final NodeSpec SPEC = NodeSpec.of("{node_id}")
            .kind(NodeSpec.Kind.ACTION)
            .tags("environment", "lifecycle");

{helpers}\
    public static void main(String[] args) {{
        Node.run(args, SPEC, ctx -> {{
            ClusterLifecyclePlan plan = {expression};
            NodeResult result = NodeResult.pass(ctx.nodeId())
                    .assertion("lifecycle_plan_resolved", !plan.dispatchKey().isBlank());
            for (Map.Entry<String, String> entry : plan.published().entrySet()) {{
                result.publish(entry.getKey(), entry.getValue());
            }}
            return result;
        }});
    }}
}}
"""


def rel(path: Path, root: Path) -> str:
    try:
        return os.fspath(path.relative_to(root))
    except ValueError:
        return os.fspath(path)
