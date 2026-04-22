# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
# # To also import user code when this scaffold lives at <user-project>/test_graph/,
# # add the user's package as a dependency and wire a [tool.uv.sources] path for it:
# #   dependencies = ["testgraphsdk", "acme-domain"]
# # and under [tool.uv.sources] below:
# #   acme-domain = { path = "../..", editable = true }
# # Then in the body:  from acme_domain import User
# # See test-graph-skill/SKILL.md → "Importing user code".
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# # acme-domain = { path = "../..", editable = true }
# ///
"""Fixture node: seeds a user record the downstream login assertion depends on."""
from __future__ import annotations

import json
import uuid

from testgraphsdk import NodeResult, NodeSpec, node


SPEC = (
    NodeSpec("user.seeded")
    .kind("fixture")
    .depends_on("app.running")
    .tags("fixture")
    .timeout("30s")
    .side_effects("db:writes")
    .input("username", "string")
    .output("userId", "string")
)


@node(SPEC)
def main(ctx):
    username = ctx.input("username") or "smoke-user"
    user_id = f"u-{uuid.uuid4().hex[:8]}"

    # Write fixtures inside this run's reportDir — keeps them tied to the
    # run that produced them and out of the report aggregator's way.
    fixtures_dir = ctx.report_dir / "fixtures"
    fixtures_dir.mkdir(parents=True, exist_ok=True)
    record_path = fixtures_dir / f"{user_id}.json"
    record_path.write_text(json.dumps({"id": user_id, "username": username}))

    return (
        NodeResult.pass_("user.seeded")
        .assertion("record_written", record_path.exists())
        .artifact("seed-record", str(record_path))
        .metric("created_users", 1)
        .publish("userId", user_id)
        .publish("username", username)
    )


if __name__ == "__main__":
    main()
