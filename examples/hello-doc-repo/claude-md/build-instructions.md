# Build instructions

For this repo:

- `jbang RunTests.java` — full Layer-2 test suite.
- `python skills/test_graph/scripts/run.py --all` — integration suite (~7-10 min).
- `python skills/test_graph/scripts/run.py <graph>` — single graph in isolation.

For new features:

1. Land Layer-2 unit tests before integration tests.
2. Run `--all` before committing.
3. Match existing commit-message style (`feat(area): summary` + 1-2 line body).
