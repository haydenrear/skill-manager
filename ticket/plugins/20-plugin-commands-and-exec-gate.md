# 20 — Plugin executable commands + EXEC policy gate trigger

**Phase**: F — Verification + ship (forward-looking feature)
**Depends on**: 11 (Projector), 12 (PolicyGate's EXEC category is
already wired but has no current trigger).

## Goal

Plugin layouts can ship a `commands/` directory containing executable
shell scripts that agents invoke. Today the `PluginUnit` data model
doesn't surface these; `PolicyGate.Category.EXECUTABLE_COMMANDS`
exists in code but never fires. This ticket closes the loop:

- Parse `<plugin>/commands/*` at plugin-load time.
- Surface in the `PluginUnit` data shape.
- Render in `skill-manager show <plugin>`.
- Trip the `! EXEC` categorization line when the plan touches
  any plugin with executable commands.
- `PolicyGate` then trips `Category.EXECUTABLE_COMMANDS` (already
  wired in ticket 12); `--yes` is rejected unless
  `policy.install.require_confirmation_for_executable_commands` is
  flipped to false.

## What to do

### Data model

```
src/main/java/dev/skillmanager/model/PluginUnit.java
  Add: List<PluginCommand> commands

src/main/java/dev/skillmanager/model/PluginCommand.java   (new)
  record PluginCommand(String name, Path path, boolean executable)
```

`PluginParser.load` walks `<plugin>/commands/*`, skips files that
aren't executable (or aren't a known shell-script extension), and
builds the list. Empty when the plugin has no `commands/` dir.

### Plan categorization

```
src/main/java/dev/skillmanager/plan/PlanBuilder.java::categorize
```

Walk `units` for plugins with non-empty `commands()`. Emit:

```
! EXEC   <plugin>/<command>, <plugin>/<command>, ...
```

Mirrors the existing `! HOOKS` / `! MCP` / `! CLI` lines.

### `show <plugin>`

```
src/main/java/dev/skillmanager/commands/ShowCommand.java
```

Add a `commands (<n>)` block when the plugin has any:

```
PLUGIN  widget@0.4.2  ...
contained skills (1):
  - widget-impl
commands (2):
  - format
  - lint
effective dependencies (unioned):
  ...
```

### Tests

```
src/test/java/dev/skillmanager/model/PluginParserCommandsTest.java
  - plugin with executable commands/ → PluginUnit.commands() populated
  - plugin without commands/ → empty list
  - non-executable file in commands/ → skipped (with warning)

src/test/java/dev/skillmanager/plan/PolicyGatingTest.java   (extend)
  - plugin with commands + flag on → EXECUTABLE_COMMANDS gate fires
  - plugin with commands + flag off → no violation
  - plugin without commands → no EXEC line in categorization
```

### Default policy commentary

Update the `[install]` table comment in
`Policy.writeDefaultIfMissing` to mention that
`require_confirmation_for_executable_commands` finally has a real
trigger as of this ticket.

## Out of scope

- Schema validation of executable commands (defer — runtime errors
  are fine until we hit a class of bug worth gating against).
- `commands/` projection into agent dirs (plugins are projected as a
  whole; the agent picks what to invoke).

## Acceptance

- A plugin with a `commands/` dir + executable scripts surfaces them
  in `show` and trips the `! EXEC` plan-print line.
- `--yes` is blocked under default policy when an EXEC line is
  present, matching the contract for HOOKS.
- Existing plugin behavior unchanged when the plugin has no commands.
