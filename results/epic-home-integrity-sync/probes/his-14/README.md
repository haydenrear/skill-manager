# HIS-14 probes

- `def029-repro.md` — DEF-029 reproduced and closed with the real CLI, against a
  scratch decoy `$HOME`. Before/after transcripts, plus the printed remedy
  EXECUTED in a shell whose environment names the other home.
- `vacuity-checks.txt` — V1…V4, each disabling the fix, each with the verbatim
  failure. Includes the two fixture defects the vacuity pass found.
- `real-homes.before.sha256`, `real-homes.after.sha256` — **digests only** of
  the operator's real agent config files and the three agent `skills/`
  directories. The before-digests were taken before the first command of the
  session, not after a surprise.

  **These used to be byte-for-byte COPIES of `~/.codex/config.toml`,
  `~/.claude/settings.json`, `~/.gemini/settings.json` and
  `~/.claude/plugins/known_marketplaces.json`, plus `ls -la` listings of the
  operator's installed skills, committed to a public repository.** No
  credentials — but `JAVA_HOME`, every project path on the machine with its
  trust level, MCP endpoints, and hook command paths. Caught in review of #234
  and removed from the branch's history, not just from its tip.

  A digest is strictly better evidence than a copy, because same-or-different
  is the only thing the assertion ever claimed. For the `skills/` directories
  what is hashed is the sorted list of **entry names and symlink targets**, so
  "no link appeared, none was repointed, none vanished" is provable without
  publishing which skills the operator has installed.

  **`~/.claude.json` is in the AFTER set and not the BEFORE set**, and that is
  a real gap rather than a formatting detail: it is the file this ticket's own
  reproduction names as damage, and the first snapshot omitted it. It is also
  written continuously by the live agent session hosting this ticket, so this
  session cannot make a claim about it either way. What covers it instead is
  `ticket.lifecycle.global.home.untouched`'s
  `the_config_check_covers_the_sibling_claude_json_file`, and the fact that
  every CLI run here was driven with `$HOME` pointed at a scratch directory.
- `check-real-homes.sh` — the comparison. Hashes in place and copies nothing;
  `./check-real-homes.sh real-homes.before.sha256` prints a diff and exits 1 on
  any change.
- `runtests.sh` — how the unit suite was run, in BOTH modes:
  `./runtests.sh` leaves the four agent/store variables unset,
  `./runtests.sh --set` exports them at a scratch home.
  `HomeBindsBothAxesTest` is **17/17 either way**, which is the point of
  shipping both: in the first round it was run only one way and went 9/1 on a
  precondition when a real `CLAUDE_CONFIG_DIR` was present. `--set` still shows
  19 failures in OTHER suites — the same 19, in the same suites, as on the base
  commit before any of this ticket's code — filed as DEF-041.
  `$HOME` is a scratch directory in both modes.
- `unit-suite-green.txt` — the green runs, both modes.
- `graph-*-summary.json` — the three graphs, node by node. `execution.complete`
  is the field to read (it is nested under `execution`, not at the top level).
