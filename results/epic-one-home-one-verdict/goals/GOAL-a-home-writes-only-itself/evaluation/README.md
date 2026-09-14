# GOAL-a-home-writes-only-itself — OHV-8 evaluation

Decided by OHV-8 on `85d0d451` (epic tip `57b96b9d` code). The full table is in `tickets/OHV-8/README.md`.

| clause | measured | verdict |
| --- | --- | --- |
| (1) the child install leaves the parent byte-identical, and the child owns a token-form shim | `home.verdicts.child.install.writes.only.itself` 9/9 in local run `20260914-153435` (`../../GOAL-one-verdict/evaluation/home-verdicts-local-20260914-153435/envelope/`) | met |
| (2) a foreign write is caught and names the other home | `SkillScriptWriteThroughTest` 7/7 (`ohv9-unit-cases-run.txt`, runner `RunOhv9Tests.java`) | met |
| (3) every other bin/cli writer | `BinCliWritersDoNotFollowLinksTest` 7/7, and the table below checked against the code on the tip | met |

## OHV-9's backend table, checked against the tip

| writer | claim | what the tip's code shows |
| --- | --- | --- |
| `SkillScriptBackend` | detach, restore, stat guard | `ForeignBinLinks.detach` at `SkillScriptBackend.java:227`; `foreign.restore()` in a `finally` at 232–233; `requireNoForeignWrite()` at 237 |
| `PipBackend` | same guard around `uv tool install` | `detach` at `PipBackend.java:58`; `restore()` in a `finally` at 61–62; `requireNoForeignWrite()` at 64 |
| `NpmBackend` / `BrewBackend` | `placeLink` (delete, then link) | `ForeignBinLinks.placeLink` at `NpmBackend.java:71` and `BrewBackend.java:70` |
| `TarBackend` | `placeCopy` | `ForeignBinLinks.placeCopy` at `TarBackend.java:79` |
| `InstallerRegistry.takeOwnershipOfShim` / `restoreForeignShim` | delete the link, restore only where absent | present at `InstallerRegistry.java:331` / `:378`, called at 175/179 |
| `HomeLinks.relativizeShims` | skips a foreign link | the `Files.isSymbolicLink(entry)` branch at `HomeLinks.java:87`; unit case "relativizeShims leaves a link into another home alone" passes |
| `SkillScriptBackend.reportFrozenShims` | skips links | `if (Files.isSymbolicLink(shim) \|\| !Files.isRegularFile(shim)) continue;` |
| `LauncherShims` | unlink, then write | `writeOwnFile` at `LauncherShims.java:228/244/261`; unit case passes |

**On this machine the goal does not yet hold.**
- The operator's root `bin/cli` was rewritten through links at 11:31 EDT by a commit-diff-context-parent
  graph whose skill-manager lacks OHV-9 (DEF-OHV-180).
- OHV-9 protects only homes run by the epic build until the fix is released, and until CDC#262 stops test
  homes parenting on the real root.
