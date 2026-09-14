# Architecture scorecard rubric, version 3

Scored by two blind judges. Evidence lands under specs/results/scorecards/<ticket>/.

## D1 Port boundary
- 0: RUBRIC-ANCHOR-D1-0 the adapter is called directly from domain code.
- 2: RUBRIC-ANCHOR-D1-2 a port exists but one caller bypasses it.
- 4: RUBRIC-ANCHOR-D1-4 every domain call goes through the port; the adapter is swappable in tests.

## D2 Fake fidelity
- 0: RUBRIC-ANCHOR-D2-0 no fake.
- 4: RUBRIC-ANCHOR-D2-4 the fake passes the adapter's conformance suite.

## Scoring rules
- RUBRIC-RULE-1 A dimension's score is the lower of the two judges' scores.
- RUBRIC-RULE-2 Scores are comparable only across runs of the same rubric version.
