# OHV-dash: portable shim home anchor (DEF-OHV-190)

## Defect

`ShimHomeContract.selfDerivingRewrite` wrote
`SKILL_MANAGER_SHIM_HOME="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." && pwd)"`.
`isShellShebang` accepts `sh`, `dash`, `bash`, `zsh`, `ksh`. dash cannot parse
`${BASH_SOURCE[0]...}`, so the home resolves to `/` and the exec goes to `//cache/...` (rc 127).

## Fix

Anchor is now `${BASH_SOURCE:-$0}` (`ShimHomeContract.SHIM_HOME_ANCHOR`).

## Rule for shims already on disk

- Shebang interpreter `sh` or `dash` (directly or via `env`) **and** a
  `SKILL_MANAGER_SHIM_HOME=` assignment containing `${BASH_SOURCE[`:
  reported as `FROZEN_HOME_PATH_IN_SHIM`. It is repairable when the line is
  exactly the old line, which `home repair --fix` replaces in place.
  Any other subscripted form is reported with `repairable=false`.
- `bash`, `zsh`, `ksh` with the old line: not reported, left byte-identical
  (it works, see the matrix).
- `#!/bin/sh` is reported even where `/bin/sh` is bash, because a copy of the home can move to a host where it is dash.

## Shell matrix (`shell-matrix.sh` → `shell-matrix.txt`, macOS, /bin/sh = bash 3.2)

| form | shell | by path | `<shell> shim` | copy, original deleted | via symlink from another dir |
| --- | --- | --- | --- | --- | --- |
| old `[0]` | /bin/sh | ok | ok | ok | fails |
| old `[0]` | /bin/dash | **rc 127, Bad substitution** | **rc 127** | **rc 127** | **rc 127** |
| old `[0]` | /bin/bash | ok | ok | ok | fails |
| old `[0]` | /bin/zsh | ok | ok | ok | fails |
| old `[0]` | /bin/ksh | ok | ok | ok | fails |
| new | /bin/sh | ok | ok | ok | fails |
| new | /bin/dash | ok | ok | ok | fails |
| new | /bin/bash | ok | ok | ok | fails |
| new | /bin/zsh | ok | ok | ok | fails |
| new | /bin/ksh | ok | ok | ok | fails |

The symlink column fails for **both** forms in every shell: `$0` is the link's
path, so `../..` is the link's directory's grandparent. This is unchanged by this fix and out of its scope.

## On Debian, where /bin/sh is dash (`debian-sh.sh` → `debian-sh.txt`, `debian:stable-slim`)

A `#!/bin/sh` shim, run by path from a `cp -R` copy with the original deleted:

- old: `rc=127 | Bad substitution home=/ ... exec: //cache/tool: not found`
- new: `rc=0 | home=/t/copy tool-ran`

## Tests

- `unit-red-prefix.txt`: new test against the pre-fix anchor (stand-ins held
  the old line / empty detector). `ShimAnchorRunsUnderEveryShellTest` 6 passed,
  **3 failed** (dash rewrite rc 127; existing `#!/bin/sh` shim not reported; anchor has a subscript).
- `unit-green-focused.txt`: after the fix. ShimAnchorRunsUnderEveryShellTest 9/9,
  ShimSurvivesACopyTest 14/14, DamagedHomeIsRepairableTest 31/31, SkillScriptBackendTest 7/7.
- `unit-full-runtests.txt`: `jbang RunTests.java`: ALL PASSED, 1606 passed, 0 failed, 0 skipped.
- `graph-home-verdicts.txt`: `run.py home-verdicts`: BUILD SUCCESSFUL in 3m 10s, 18/18 nodes passed
  (validation-reports run 20260914-191610). The `localhost:4318` exceptions are the OTLP exporter failing open.
