# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import sys
from pathlib import Path

from testgraphsdk import NodeResult, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from tg6_lifecycle_support import (
    AWS,
    aws_credentials_present,
    aws_guard_reason,
    aws_lifecycle_enabled,
    aws_selected,
    lifecycle_spec,
)


SPEC = lifecycle_spec(
    "tg6.aws.lifecycle.guard",
    "assertion",
    AWS,
    depends_on=(AWS.scaffold_node,),
    outputs=("awsLifecycleSelected", "awsCredentialsPresent", "awsLifecycleEnabled", "awsGuardReason"),
    extra_tags=("aws-guard",),
)


@node(SPEC)
def main(ctx):
    selected = aws_selected()
    credentials = aws_credentials_present()
    enabled = aws_lifecycle_enabled()
    reason = aws_guard_reason()

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("aws_lifecycle_requires_explicit_selection", enabled or not selected or reason == "aws-credentials-missing")
        .assertion("aws_lifecycle_requires_credentials", enabled or not credentials or reason == "aws-lifecycle-not-selected")
        .assertion("aws_guard_reason_matches_state", (enabled and reason == "") or (not enabled and bool(reason)))
        .publish("awsLifecycleSelected", str(selected).lower())
        .publish("awsCredentialsPresent", str(credentials).lower())
        .publish("awsLifecycleEnabled", str(enabled).lower())
        .publish("awsGuardReason", reason)
    )


if __name__ == "__main__":
    main()
