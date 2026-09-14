# CI — graph_set=full on feature/OHV-7

- Run: https://github.com/haydenrear/skill-manager/actions/runs/34790187503
  (workflow_dispatch, head c27bd018, the runner change before rebasing onto 8bd883f3)
- Artifact: `ci-34790187503.graphs-executed.json`

| | this run | main baseline (34786751392) |
| --- | --- | --- |
| graphs_selected | 25 | 25 |
| graphs_executed | 25 | 25 |
| graphs_passed | 19 | 19 |
| graphs_failed | 6 | 6 |

Failed graphs: artifact-dag, checkout-home, home-clone, home-tripwire, onboarding,
ticket-lifecycle. That is **exactly the baseline set, so nothing new failed**. All other jobs
succeeded; the Selenium job was skipped, as it is on any dispatch without browser graphs.

CI calls `run.py <graph>` once per graph, which is the single-graph path this ticket
leaves unchanged, so this run guards against collateral damage and does not
exercise the new sweep path. The local signal covers the sweep (README.md).
