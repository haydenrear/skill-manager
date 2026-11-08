# Manual acceptance, epic honest-pins

**Commit tested:** 1f4272a3 (`epic/honest-pins`). **Environment:** macOS, the checkout's `./skill-manager` launcher, throwaway homes with `SKILL_MANAGER_HOME`, `CLAUDE_HOME` and `CODEX_HOME` all pointed at them. Real git, real `uv`/pip for the CLI dependency. **Tester:** the epic agent, on the owner's request for a quick pass. The owner did not run these personally. Raw output is `output.txt` here; `W` stands for the scratch directory.

| # | Scenario | Result |
|---|---|---|
| M1 | Install; `sync` to upstream; `sync --to <older sha>`; plain `sync` stays; `--to main`; `--to <annotated tag>`; `--to <missing>` | Pass. HEAD, record and lock agree at every step. The annotated tag records the commit, not the tag object. A missing revision exits 1 and changes nothing. |
| M2 | Local edit plus an unreadable file, then `sync --merge` | Pass. Refused by name, exit 1, edit kept. With the file fixed, a real conflict exits 8, rolls back and keeps the edit. |
| M3 | Bump a CLI pin; a second unit declares the old pin | Pass. The tool moved from 0.0.0.1 to 0.0.0.2 and the lock says so. The second install halts, exit 4, naming both units, both pins and the ways out. |
| M4 | Removed commands, bare-name install, `publish --dry-run`, `unit validate` | Pass. Removed commands exit 2. A bare name fails with the coordinate pointer. `publish` exits 2 and points to `unit validate`, which works. |
| M5 | Transitive bare reference, dependency installed and absent | Pass. Installed is satisfied; absent fails with the pointer and rolls back. |
| M6 | `home clone`, commit in the clone, `home sync`, launch, ack, launch | Pass. Sync warns, drift exits 8, launch refuses, ack clears, launch runs. |
| M7 | A home written by released 0.28.2, driven by this build | Pass. List and sync work; record and HEAD agree after an upstream change. |

**Not exercised by hand:** a real agent launch through `exec` (the launch command was `true`), a real gateway and MCP server (covered by `smoke` and `plugin-smoke`), plugins and harnesses (covered by their graphs), and a real long-lived home.

**Escapes:** no new defect in what the epic changed. Three existing cosmetic behaviours were noted, and released 0.28.2 shows all three: `MAN-CLI-INSTALLED-NOOP`, `MAN-RECONCILE-NOISE`, and a wider `DEF-HP2-001`. See `../attribution/`.

**Full graph run on this commit:** sweep 20261108-200125 (`../goals/final-run.sweep.json`), 27 executed, 25 passed. `doc-smoke` and `onboarding` red on `DEF-HP9-002` (tla-spec-dev text). `onboarding.global.home.untouched` also red, on two timestamp-only changes under `~/.claude/skills/synced` from the live Claude session.
