# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node


REPO_ROOT = Path(__file__).resolve().parents[2]
SDK_SOURCE_ROOTS = [
    REPO_ROOT / "project_sdk_sources" / "build-logic" / "src",
    REPO_ROOT / "project_sdk_sources" / "sdk" / "java" / "src",
    REPO_ROOT / "project_sdk_sources" / "sdk" / "python" / "src",
]
TEXT_SUFFIXES = {".java", ".kt", ".kts", ".py", ".md", ".toml", ".yaml", ".yml"}
FORBIDDEN = ("deploy-cdc", "deploy_helm", "deploy-helm")

SPEC = (
    NodeSpec("tg5.deploy_cdc.sdk.uncoupled")
    .kind("assertion")
    .depends_on("tg5.deploy_cdc.issue.contract")
    .tags("tg5", "deploy-cdc", "sdk-boundary")
)


def source_files() -> list[Path]:
    files: list[Path] = []
    for root in SDK_SOURCE_ROOTS:
        files.extend(path for path in root.rglob("*") if path.is_file() and path.suffix in TEXT_SUFFIXES)
    return sorted(files)


@node(SPEC)
def main(ctx):
    matches: list[str] = []
    for path in source_files():
        text = path.read_text(encoding="utf-8", errors="ignore")
        for forbidden in FORBIDDEN:
            if forbidden in text:
                matches.append(f"{path.relative_to(REPO_ROOT)} contains {forbidden}")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("sdk_source_roots_exist", all(root.is_dir() for root in SDK_SOURCE_ROOTS))
        .assertion("sdk_sources_scanned", bool(source_files()))
        .assertion("sdk_has_no_deploy_cdc_or_deploy_helm_coupling", not matches)
        .log("No deploy-cdc/deploy-helm coupling was found in SDK runtime source." if not matches else "\n".join(matches))
    )


if __name__ == "__main__":
    main()
