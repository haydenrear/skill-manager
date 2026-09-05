from __future__ import annotations

from .types import UNCHECKED, ActionMetadata, StateGraphCase, StateGraphInput, StateGraphOutput, StateGraphRejection


SCHEMA_VERSION = 'tla-testgraph.trace.v1'
SOURCE_MODULE = 'ClaimantRefreshInternal'
SOURCE_VIEW = 'internal'
STATE_COUNT = 2
TRANSITION_COUNT = 1

CASES = [
    StateGraphCase(
        name='case_0001_reconcile_claiming_project_from_selected_parent',
        before={'claimant_child_revision': 'A', 'claimant_fetches': 0, 'claimant_parent_revision': 'A', 'claimant_phase': 'selected', 'claimant_selected_revision': 'A', 'claimant_trunk_revision': 'B'},
        input=StateGraphInput(
            action='ReconcileClaimingProjectFromSelectedParent',
            source_node='5443867627986022932',
            target_node='-5872759604132347719',
            params={},
        ),
        output=StateGraphOutput(changed={'claimant_phase': {'after': 'complete', 'before': 'selected'}}),
        after={'claimant_child_revision': 'A', 'claimant_fetches': 0, 'claimant_parent_revision': 'A', 'claimant_phase': 'complete', 'claimant_selected_revision': 'A', 'claimant_trunk_revision': 'B'},
        labels=frozenset(('ReconcileClaimingProjectFromSelectedParent',)),
        schema_version=SCHEMA_VERSION,
        view=SOURCE_VIEW,
        layer='internal',
        controllability='unit_direct',
        generates=frozenset(('spec_unit',)),
        tags=frozenset(()),
        metadata=ActionMetadata(
            layer='internal',
            controllability='unit_direct',
            generates=frozenset(('spec_unit',)),
            tags=frozenset(()),
        ),
    ),
]

CASES_BY_NAME = {case.name: case for case in CASES}
