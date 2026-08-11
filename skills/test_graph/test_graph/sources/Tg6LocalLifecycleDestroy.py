# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import os

from testgraphsdk import EnvironmentRepository, NodeResult, NodeSpec, SideEffect, node


REPOSITORY = EnvironmentRepository.of(
    "build/tg6-environment-repository-local-preview",
    "templates/branch-preview",
).with_target("local-preview").with_backend("local")
RESET = "tg6.local.lifecycle.reset"
DESTROY_KEYS = ("TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT", "TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT")
TRUTHY = {"1", "true", "yes", "y"}


def destroy_requested() -> bool:
    return any(os.environ.get(key, "").strip().lower() in TRUTHY for key in DESTROY_KEYS)


def destroy_intent_absent_or_falsey() -> bool:
    return all(os.environ.get(key, "").strip().lower() not in TRUTHY for key in DESTROY_KEYS)


spec_builder = (
    NodeSpec("tg6.local.lifecycle.destroy")
    .kind("action")
    .depends_on(RESET)
    .tags("tg6", "environment", "local-lifecycle", "destroy-guard")
    .output("destroyRequested")
    .output("EnvironmentId")
)

if destroy_requested():
    spec_builder = spec_builder.side_effects(SideEffect.environment("destroy")).environment_repository(REPOSITORY)

SPEC = spec_builder


@node(SPEC)
def main(ctx):
    requested = destroy_requested()
    environment_id = ctx.get(RESET, "EnvironmentId") or os.environ.get("TEST_GRAPH_BRANCH_ENVIRONMENT_ID", "")
    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("destroy_requires_explicit_intent", requested or destroy_intent_absent_or_falsey())
        .assertion("environment_id_available", bool(environment_id))
        .publish("destroyRequested", str(requested).lower())
        .publish("EnvironmentId", environment_id)
    )


if __name__ == "__main__":
    main()
