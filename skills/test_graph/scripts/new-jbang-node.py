#!/usr/bin/env python3
"""Create a new JBang (Java) validation node from a template.

Run from inside a scaffolded test_graph project (cwd anywhere within it).

Usage:
    new-jbang-node.py <node-id> <kind>

Kinds: testbed | fixture | action | assertion | evidence | report
"""
from __future__ import annotations

import argparse
import sys

from _common import (
    add_test_graph_root_arg,
    class_name_from_id,
    render_template,
    target_project_root,
    target_sources_dir,
    templates_dir,
    validate_kind,
    validate_node_id,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("node_id", help="dotted lowercase node id, e.g. checkout.smoke")
    parser.add_argument(
        "kind",
        help="node kind (testbed | fixture | action | assertion | evidence | report)",
    )
    add_test_graph_root_arg(parser)
    args = parser.parse_args()

    validate_node_id(args.node_id)
    validate_kind(args.kind)

    class_name = class_name_from_id(args.node_id)
    template = templates_dir() / "jbang-node.java.template"
    out_path = target_sources_dir(args.test_graph_root) / f"{class_name}.java"

    if out_path.exists():
        sys.exit(f"error: node already exists: {out_path}")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        render_template(
            template,
            {
                "__CLASS_NAME__": class_name,
                "__NODE_ID__": args.node_id,
                "__KIND_UPPER__": args.kind.upper(),
            },
        )
    )

    rel = out_path.relative_to(target_project_root(args.test_graph_root))
    print(f"created {rel}")
    print("next steps:")
    print("  1. fill in the body")
    print(f'  2. add `node("{rel}")` to build.gradle.kts')
    print("     (or leave it out — it'll be pulled in transitively if something dependsOn it)")
    print("  3. ./gradlew validationPlanGraph --name=<graph>   # dry-run the plan")
    print(f"  4. ./gradlew <graph>                                # run the graph that includes {args.node_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
