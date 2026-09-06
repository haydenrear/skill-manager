# OUN-2 — refuse a plugin whose contained skill name is already claimed

`GOAL-one-name-one-copy`: **UNMEASURED → MET, non-vacuously.**

```
0 live, 0 latent over 36 homes; gate refuses a planted collision
gate: exit 3, plugin_landed_anyway false,
      "refusing to install plugin 'planted-plugin': the skill it contains as
       'deploy-helm' is already installed at …"
```

Zero pairs was already true before this ticket, and meant nothing: it said
nothing is wrong *right now*. Only a planted collision shows the product would
not let one happen. The harness now plants one — a plugin carrying a skill
named after a unit that is already in a scratch clone — and reads the verdict,
so the goal's own wording ("0, **non-vacuously**, with the gate refusing a
planted collision") is answered in both halves.

## The gate

A new effect, `RejectContainedNameCollision`, scheduled beside the existing
`RejectIfTopLevelInstalled`. That one has always refused a collision it can
*see*; a plugin's contained skills were in no inventory at all, which is why
this gate could not exist before OUN-1 and why the ordering was forced.

```
refusing to install plugin 'acme-plugin': the skill it contains as 'acme-tool'
is already installed at <home>/skills/acme-tool.
  a unit name resolves to exactly one copy in a home, and installing this
  would give 'acme-tool' two.
  either remove the existing one (skill-manager remove acme-tool),
  or rename the skill inside the plugin.
```

Both claimants and both ways out, because the operator has to *choose* one —
not be asked to confirm.

## Three things that are deliberately not collisions

| | why |
| --- | --- |
| the plugin's own entry skill | `skt` carries `plugins/skt/skills/skt` in **21** of this machine's homes. A gate without this refuses skt everywhere. |
| the plugin's own previous installation | upgrading a plugin means its contained skills are already on disk under it; counting those makes the second install of any plugin impossible |
| a name claimed by a unit in the *same* operation | still a collision, but the resolver already reports it — this gate is about what is already in the home |

## Hard, with nothing to override it

No flag, and `--yes` is not one either: `--yes` answers policy prompts, and
this is not a prompt. A case asserts that directly.

The reason is not strictness for its own sake. Two copies of one name is not a
version conflict to be reconciled by preference; it is an ambiguity the
five-branch search resolves by directory order — silently, and differently
depending on which branch matches first.

This is also what makes migration sticky **on purpose**: retiring a standalone
unit in favour of a plugin-contained one of the same name has to happen in one
operation, because both existing at once is exactly the state this refuses.
OUN-5 has to satisfy the gate, not weaken it.

## Six cases

| case | asserts |
| --- | --- |
| a plugin carrying an already-claimed name is refused | nothing committed, the existing unit untouched, non-zero exit |
| the refusal names both claimants and both ways out | the plugin, the existing path, and two commands |
| `--yes` does not get past it | the ticket's stated constraint |
| a plugin's entry skill of its own name is NOT a collision | the skt shape |
| a plugin does not collide with its own previous installation | upgrades still possible |
| CONTROL: a free name installs | the gate is not refusing everything |
