#!/usr/bin/env python3
"""Materialize portable managed provider bindings for a test_graph project."""
from __future__ import annotations

import argparse
import json
import sys

from _common import (
    ProviderBindingError,
    add_test_graph_root_arg,
    prepare_provider_bindings,
    target_project_root,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_test_graph_root_arg(parser)
    args = parser.parse_args()
    root = target_project_root(args.test_graph_root)
    try:
        result = prepare_provider_bindings(root)
    except ProviderBindingError as error:
        parser.error(str(error))
    if result is None:
        parser.error(
            "this is a legacy or copy-mode scaffold with no provider-bindings.json; "
            "run migrate-bindings.py first"
        )
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
