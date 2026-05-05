# 12 — `policy.install` plan-print gates

**Phase**: D — Orthogonal features
**Depends on**: 05
**Spec**: § "Policy".

## Goal

Wire the `policy.install.*` flags from `~/.skill-manager/policy.toml`
into plan-print so `!`-marked lines block `--yes` unless the
corresponding flag is false.

## What to do

### Policy fields

```
src/main/java/dev/skillmanager/policy/Policy.java
```

Add `policy.install` section:

| Flag | Default | Gates |
| --- | --- | --- |
| `require_confirmation_for_hooks` | `true` | plugin with `hooks/` |
| `require_confirmation_for_mcp` | `true` | any MCP dep |
| `require_confirmation_for_cli_deps` | `true` | any CLI dep |
| `require_confirmation_for_executable_commands` | `true` | plugin with executable `commands/` entries |

### Plan-print gating

The categorization output added in ticket 05 (the `! HOOKS` / `! MCP`
/ `! CLI` lines) gains a behavioral effect: if the plan has any `!`
lines, `--yes` is rejected unless every corresponding policy flag is
false. The error tells the user which flag(s) to flip.

Interactive mode (no `--yes`) prompts per-`!`-line group as today.

### Source-trust policy is **not** in this ticket

Marketplace trust gating is deferred until multi-source lands.
`policy.sources` is not introduced here.

## Tests

```
src/test/java/dev/skillmanager/plan/
└── PolicyGatingTest.java       // contract #5: --yes can't bypass !-marked lines under default policy
```

Sweep: `(every flag combination × dep mix that triggers each flag × unit kind)`.
~64 cells; <500ms.

## Out of scope

- Source/marketplace trust (deferred).
- Granular per-dep allowlisting (defer until needed).

## Acceptance

- `policy.install` flags read correctly from `policy.toml`.
- `--yes` is rejected when any `!` line is present and its flag is
  on; error message names the flag(s).
- Sweep test passes for every flag combination.
