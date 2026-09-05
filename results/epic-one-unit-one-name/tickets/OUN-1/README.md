# OUN-1 — a plugin-contained skill is addressable by name

`GOAL-a-contained-skill-is-addressable`: **no → yes.** OUN-0's red case is
green, and the harness's verdict comes from the product: the same install
that reported ``references missing unit `unit-authoring` `` now exits 0.

## The change

One branch, added LAST in `MarkdownImportValidator.installedRoot`, backed by
`SkillStore.containedSkillDirs(name)`.

The position is the whole design decision. Last means:

1. **nothing that resolved before resolves differently now.** Earlier, a
   contained skill could shadow a standalone unit of the same name silently,
   by directory order — the ambiguity the rule forbids, not a strategy.
2. **a plugin's entry skill stays the plugin.** `plugins/` is searched first,
   so `skt` resolves to the plugin, never to `plugins/skt/skills/skt`. One
   unit, one name. This is `DEF-OUN-002` answered: 21 homes were in that
   shape, and a naive fifth branch would have turned all 21 into collisions.
3. **a contained name resolves only when nothing standalone claims it** —
   the state OUN-2's gate makes mandatory rather than merely usual.

`containedSkillDirs` returns a **list**, not an `Optional`. Two plugins may
contain the same skill name; a predicate that returned the first match would
resolve an ambiguity silently by directory order. The caller gets both roots,
`installedRoot` takes the first in plugin-name order so behaviour is testable,
and OUN-2 refuses the install outright.

## Four cases, and one of them is the order

| case | asserts |
| --- | --- |
| a plugin-contained skill IS addressable by name | the branch resolves; a bad path inside it is still a PATH violation; an absent name is still missing |
| a standalone unit wins over a contained skill of the same name | the two roots hold **different files**, so the violation says which root answered — a bare "it resolves" cannot tell a correct order from a lucky one |
| a plugin's entry skill of the plugin's own name resolves to the PLUGIN | `skt`-shaped, the case that matters in 21 homes |
| two plugins containing one name resolve deterministically, by plugin name | and the store reports **both** roots |

## Two instrument defects found on the way, and they mattered more than the code

**The harness was measuring the previous build.** jbang keys its cache on the
hash of the entry script; `SkillManager.java` did not change, so
`./skill-manager` kept running the pre-fix jar while `RunTests.java` — a
different entry script — rebuilt and passed. Every unit case green, the
harness still red. The harness now compares source mtimes against the newest
cached jar and rebuilds when stale, and reports `cli_rebuilt_before_probing`
so a reader can see which build answered. `DEF-OUN-004`.

**The harness was deleting the thing it measured.** Wave 1's review added
`CLAUDE_HOME`/`CODEX_HOME`/`GEMINI_HOME` pinning on the theory that more
pinning is safer. `CODEX_HOME` and `GEMINI_HOME` are the config *directory*,
not its parent, and pointing either at a Skill Manager home makes
`skill-manager install` **delete `<home>/plugins/skt`** — exit 0, silently.
So the probe emptied the home's plugins and then reported that a contained
skill was not addressable. Both true, unrelated, and the second caused by the
first. Reverted to `SKILL_MANAGER_HOME` alone, which was already correct — a
clone carries its own `.claude`, `.codex` and `.gemini`.

The harness now asserts the plugin is **still present after the probe
install**, because "not addressable" and "not there any more" produce the
same message and only one of them is a measurement. Issue #311,
`DEF-OUN-003`.

## The collision goal now reads UNMEASURED, on purpose

With contained skills addressable and the entry-skill rule applied, the
walker reports **0 live, 0 latent over 33 homes**. That is not MET: the goal's
target is "0, non-vacuously, **with the gate refusing a planted collision**",
and no gate exists until OUN-2. Reporting MET here would repeat exactly the
mistake OUN-0 was written to catch. UNMEASURED is loud — the ledger prints it
and exits 2 — so OUN-2 replacing it with a real probe is a visible task
rather than a hardcoded `False` somebody has to remember to flip.

## Ledger after this ticket

```
UNMEASURED  GOAL-one-name-one-copy                 0 live, 0 latent over 33 homes
MET         GOAL-a-contained-skill-is-addressable  yes
NOT MET     GOAL-migration-lands-on-one-skt        root (1, 1, 1)
NOT MET     GOAL-who-imports-this                  no such command
```
