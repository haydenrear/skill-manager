# Validation report — 20260712-180941

**Overall**: PASSED  
**Nodes**: 21 (passed=21, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `cli.agent.context.output` | **PASS** | 5820ms | [context/cli.agent.context.output.input.json](context/cli.agent.context.output.input.json) | [node-logs/cli.agent.context.output.stdout.log](node-logs/cli.agent.context.output.stdout.log) |
| `cli.help.progressive` | **PASS** | 4013ms | [context/cli.help.progressive.input.json](context/cli.help.progressive.input.json) | [node-logs/cli.help.progressive.stdout.log](node-logs/cli.help.progressive.stdout.log) |
| `cli.metadata.catalog.covered` | **PASS** | 797ms | [context/cli.metadata.catalog.covered.input.json](context/cli.metadata.catalog.covered.input.json) | [node-logs/cli.metadata.catalog.covered.stdout.log](node-logs/cli.metadata.catalog.covered.stdout.log) |
| `cli.skill-docs.catalog.covered` | **PASS** | 715ms | [context/cli.skill-docs.catalog.covered.input.json](context/cli.skill-docs.catalog.covered.input.json) | [node-logs/cli.skill-docs.catalog.covered.stdout.log](node-logs/cli.skill-docs.catalog.covered.stdout.log) |
| `doc.bind.two.sources` | **PASS** | 1872ms | [context/doc.bind.two.sources.input.json](context/doc.bind.two.sources.input.json) | [node-logs/doc.bind.two.sources.stdout.log](node-logs/doc.bind.two.sources.stdout.log) |
| `doc.bound.to.project` | **PASS** | 1764ms | [context/doc.bound.to.project.input.json](context/doc.bound.to.project.input.json) | [node-logs/doc.bound.to.project.stdout.log](node-logs/doc.bound.to.project.stdout.log) |
| `doc.command.coverage` | **PASS** | 6560ms | [context/doc.command.coverage.input.json](context/doc.command.coverage.input.json) | [node-logs/doc.command.coverage.stdout.log](node-logs/doc.command.coverage.stdout.log) |
| `doc.markdown.import.targets` | **PASS** | 10952ms | [context/doc.markdown.import.targets.input.json](context/doc.markdown.import.targets.input.json) | [node-logs/doc.markdown.import.targets.stdout.log](node-logs/doc.markdown.import.targets.stdout.log) |
| `doc.rebind.after.all.removed` | **PASS** | 1751ms | [context/doc.rebind.after.all.removed.input.json](context/doc.rebind.after.all.removed.input.json) | [node-logs/doc.rebind.after.all.removed.stdout.log](node-logs/doc.rebind.after.all.removed.stdout.log) |
| `doc.repo.installed` | **PASS** | 3153ms | [context/doc.repo.installed.input.json](context/doc.repo.installed.input.json) | [node-logs/doc.repo.installed.stdout.log](node-logs/doc.repo.installed.stdout.log) |
| `doc.repo.uninstalled` | **PASS** | 2119ms | [context/doc.repo.uninstalled.input.json](context/doc.repo.uninstalled.input.json) | [node-logs/doc.repo.uninstalled.stdout.log](node-logs/doc.repo.uninstalled.stdout.log) |
| `doc.sync.force.clobbers` | **PASS** | 3087ms | [context/doc.sync.force.clobbers.input.json](context/doc.sync.force.clobbers.input.json) | [node-logs/doc.sync.force.clobbers.stdout.log](node-logs/doc.sync.force.clobbers.stdout.log) |
| `doc.sync.local.edit.preserved` | **PASS** | 3080ms | [context/doc.sync.local.edit.preserved.input.json](context/doc.sync.local.edit.preserved.input.json) | [node-logs/doc.sync.local.edit.preserved.stdout.log](node-logs/doc.sync.local.edit.preserved.stdout.log) |
| `doc.sync.upgrade` | **PASS** | 3021ms | [context/doc.sync.upgrade.input.json](context/doc.sync.upgrade.input.json) | [node-logs/doc.sync.upgrade.stdout.log](node-logs/doc.sync.upgrade.stdout.log) |
| `doc.unbind.cleans.up` | **PASS** | 1692ms | [context/doc.unbind.cleans.up.input.json](context/doc.unbind.cleans.up.input.json) | [node-logs/doc.unbind.cleans.up.stdout.log](node-logs/doc.unbind.cleans.up.stdout.log) |
| `doc.unbind.last.section.and.dir.gone` | **PASS** | 1611ms | [context/doc.unbind.last.section.and.dir.gone.input.json](context/doc.unbind.last.section.and.dir.gone.input.json) | [node-logs/doc.unbind.last.section.and.dir.gone.stdout.log](node-logs/doc.unbind.last.section.and.dir.gone.stdout.log) |
| `doc.unbind.one.of.two` | **PASS** | 1608ms | [context/doc.unbind.one.of.two.input.json](context/doc.unbind.one.of.two.input.json) | [node-logs/doc.unbind.one.of.two.stdout.log](node-logs/doc.unbind.one.of.two.stdout.log) |
| `env.prepared` | **PASS** | 589ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `resolver.cycles.verified` | **PASS** | 9430ms | [context/resolver.cycles.verified.input.json](context/resolver.cycles.verified.input.json) | [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log) |
| `skill-manager.env.project.context` | **PASS** | 672ms | [context/skill-manager.env.project.context.input.json](context/skill-manager.env.project.context.input.json) | [node-logs/skill-manager.env.project.context.stdout.log](node-logs/skill-manager.env.project.context.stdout.log) |
| `skill-manager.skill.docs.projected` | **PASS** | 585ms | [context/skill-manager.skill.docs.projected.input.json](context/skill-manager.skill.docs.projected.input.json) | [node-logs/skill-manager.skill.docs.projected.stdout.log](node-logs/skill-manager.skill.docs.projected.stdout.log) |

## `cli.agent.context.output` — **PASS**

executor start: `2026-07-12T18:09:57.317772Z`  
executor end: `2026-07-12T18:10:03.137645Z`  
spawn exit code: 0

**Input context**: [context/cli.agent.context.output.input.json](context/cli.agent.context.output.input.json)

### Assertions

| Name | Status |
|---|---|
| agent_context_all_representatives_pass | **PASS** |

### Metrics

- `representativeCommands`: 6
- `durationMs`: 5240

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install | 0 | 870ms | 87722 | [`node-logs/cli.agent.context.output.install.log`](node-logs/cli.agent.context.output.install.log) |  |
| sync | 0 | 875ms | 87731 | [`node-logs/cli.agent.context.output.sync.log`](node-logs/cli.agent.context.output.sync.log) |  |
| bind | 0 | 886ms | 87740 | [`node-logs/cli.agent.context.output.bind.log`](node-logs/cli.agent.context.output.bind.log) |  |
| env-sync | 0 | 851ms | 87751 | [`node-logs/cli.agent.context.output.env-sync.log`](node-logs/cli.agent.context.output.env-sync.log) |  |
| harness-instantiate | 0 | 877ms | 87760 | [`node-logs/cli.agent.context.output.harness-instantiate.log`](node-logs/cli.agent.context.output.harness-instantiate.log) |  |
| publish | 0 | 866ms | 87769 | [`node-logs/cli.agent.context.output.publish.log`](node-logs/cli.agent.context.output.publish.log) |  |

**Node-process stdout**: [node-logs/cli.agent.context.output.stdout.log](node-logs/cli.agent.context.output.stdout.log)

---

## `cli.help.progressive` — **PASS**

executor start: `2026-07-12T18:09:53.303694Z`  
executor end: `2026-07-12T18:09:57.316869Z`  
spawn exit code: 0

**Input context**: [context/cli.help.progressive.input.json](context/cli.help.progressive.input.json)

### Assertions

| Name | Status |
|---|---|
| root_help_exit_zero | **PASS** |
| root_help_lists_top_level_commands | **PASS** |
| root_help_omits_install_details | **PASS** |
| root_help_omits_sync_details | **PASS** |
| root_help_omits_examples | **PASS** |
| root_help_line_count_bounded | **PASS** |
| install_help_direct_and_complete | **PASS** |
| sync_help_direct_and_complete | **PASS** |
| nested_help_direct | **PASS** |

### Metrics

- `rootHelpLines`: 53
- `durationMs`: 3433

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| root-help | 0 | 867ms | 87655 | [`node-logs/cli.help.progressive.root-help.log`](node-logs/cli.help.progressive.root-help.log) |  |
| install-help | 0 | 846ms | 87666 | [`node-logs/cli.help.progressive.install-help.log`](node-logs/cli.help.progressive.install-help.log) |  |
| sync-help | 0 | 847ms | 87677 | [`node-logs/cli.help.progressive.sync-help.log`](node-logs/cli.help.progressive.sync-help.log) |  |
| profiles-help | 0 | 859ms | 87701 | [`node-logs/cli.help.progressive.profiles-help.log`](node-logs/cli.help.progressive.profiles-help.log) |  |

**Node-process stdout**: [node-logs/cli.help.progressive.stdout.log](node-logs/cli.help.progressive.stdout.log)

---

## `cli.metadata.catalog.covered` — **PASS**

executor start: `2026-07-12T18:09:51.789520Z`  
executor end: `2026-07-12T18:09:52.586874Z`  
spawn exit code: 0

**Input context**: [context/cli.metadata.catalog.covered.input.json](context/cli.metadata.catalog.covered.input.json)

### Assertions

| Name | Status |
|---|---|
| command_paths_match_picocli | **PASS** |
| aliases_match_picocli | **PASS** |
| workflow_commands_exist | **PASS** |
| workflow_examples_exist | **PASS** |
| workflow_docs_exist | **PASS** |
| workflow_context_affordances_exist | **PASS** |

### Metrics

- `commandPaths`: 68
- `workflowIds`: 34
- `durationMs`: 136

**Node-process stdout**: [node-logs/cli.metadata.catalog.covered.stdout.log](node-logs/cli.metadata.catalog.covered.stdout.log)

---

## `cli.skill-docs.catalog.covered` — **PASS**

executor start: `2026-07-12T18:09:52.587564Z`  
executor end: `2026-07-12T18:09:53.302975Z`  
spawn exit code: 0

**Input context**: [context/cli.skill-docs.catalog.covered.input.json](context/cli.skill-docs.catalog.covered.input.json)

### Assertions

| Name | Status |
|---|---|
| workflow_ids_documented | **PASS** |
| workflow_help_routes_documented | **PASS** |

### Metrics

- `workflowIds`: 34
- `durationMs`: 31

**Node-process stdout**: [node-logs/cli.skill-docs.catalog.covered.stdout.log](node-logs/cli.skill-docs.catalog.covered.stdout.log)

---

## `doc.bind.two.sources` — **PASS**

executor start: `2026-07-12T18:10:31.153900Z`  
executor end: `2026-07-12T18:10:33.025382Z`  
spawn exit code: 0

**Input context**: [context/doc.bind.two.sources.input.json](context/doc.bind.two.sources.input.json)

### Assertions

| Name | Status |
|---|---|
| bind_ok | **PASS** |
| review_tracked_copy | **PASS** |
| build_tracked_copy | **PASS** |
| claude_imports_review | **PASS** |
| claude_imports_build | **PASS** |
| agents_imports_review_only | **PASS** |
| gemini_imports_review_only | **PASS** |
| ledger_two_bindings | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1214

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| bind | 0 | 1182ms | 88140 | [`node-logs/doc.bind.two.sources.bind.log`](node-logs/doc.bind.two.sources.bind.log) |  |

### Published context

- `projectRoot`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/project-multi-16846719786483435958`

**Node-process stdout**: [node-logs/doc.bind.two.sources.stdout.log](node-logs/doc.bind.two.sources.stdout.log)

---

## `doc.bound.to.project` — **PASS**

executor start: `2026-07-12T18:10:18.503144Z`  
executor end: `2026-07-12T18:10:20.267322Z`  
spawn exit code: 0

**Input context**: [context/doc.bound.to.project.input.json](context/doc.bound.to.project.input.json)

### Assertions

| Name | Status |
|---|---|
| bind_ok | **PASS** |
| tracked_copy_present | **PASS** |
| tracked_copy_content_matches | **PASS** |
| claude_md_created | **PASS** |
| agents_md_created | **PASS** |
| gemini_md_created | **PASS** |
| import_line_in_claude_md | **PASS** |
| import_line_in_gemini_md | **PASS** |
| managed_section_markers | **PASS** |
| ledger_row_written | **PASS** |
| ledger_source_explicit | **PASS** |
| ledger_kind_managed_copy | **PASS** |
| ledger_carries_bound_hash | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1171

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| bind | 0 | 1139ms | 87980 | [`node-logs/doc.bound.to.project.bind.log`](node-logs/doc.bound.to.project.bind.log) |  |

### Published context

- `projectRoot`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/project-477670030132999647`
- `trackedCopyPath`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/project-477670030132999647/docs/agents/review-stance.md`

**Node-process stdout**: [node-logs/doc.bound.to.project.stdout.log](node-logs/doc.bound.to.project.stdout.log)

---

## `doc.command.coverage` — **PASS**

executor start: `2026-07-12T18:10:37.998853Z`  
executor end: `2026-07-12T18:10:44.558941Z`  
spawn exit code: 0

**Input context**: [context/doc.command.coverage.input.json](context/doc.command.coverage.input.json)

### Assertions

| Name | Status |
|---|---|
| list_shows_doc_kind_and_binding_count | **PASS** |
| show_displays_doc_repo | **PASS** |
| deps_displays_doc_repo | **PASS** |
| lock_status_accounts_for_doc_repo | **PASS** |
| upgrade_rejects_doc_repo_with_sync_hint | **PASS** |

### Metrics

- `listExitCode`: 0
- `showExitCode`: 0
- `depsExitCode`: 0
- `refreshExitCode`: 0
- `lockExitCode`: 0
- `upgradeExitCode`: 5
- `durationMs`: 5965

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| list | 0 | 1037ms | 88204 | [`node-logs/doc.command.coverage.list.log`](node-logs/doc.command.coverage.list.log) |  |
| show | 0 | 999ms | 88215 | [`node-logs/doc.command.coverage.show.log`](node-logs/doc.command.coverage.show.log) |  |
| deps | 0 | 984ms | 88224 | [`node-logs/doc.command.coverage.deps.log`](node-logs/doc.command.coverage.deps.log) |  |
| sync-refresh | 0 | 975ms | 88233 | [`node-logs/doc.command.coverage.sync-refresh.log`](node-logs/doc.command.coverage.sync-refresh.log) |  |
| lock-status | 0 | 985ms | 88242 | [`node-logs/doc.command.coverage.lock-status.log`](node-logs/doc.command.coverage.lock-status.log) |  |
| upgrade | 5 | 970ms | 88251 | [`node-logs/doc.command.coverage.upgrade.log`](node-logs/doc.command.coverage.upgrade.log) |  |

**Node-process stdout**: [node-logs/doc.command.coverage.stdout.log](node-logs/doc.command.coverage.stdout.log)

---

## `doc.markdown.import.targets` — **PASS**

executor start: `2026-07-12T18:10:07.550793Z`  
executor end: `2026-07-12T18:10:18.502403Z`  
spawn exit code: 0

**Input context**: [context/doc.markdown.import.targets.input.json](context/doc.markdown.import.targets.input.json)

### Assertions

| Name | Status |
|---|---|
| all_installs_exit_zero | **PASS** |
| missing_doc_violation_rendered | **PASS** |
| installed_skill_harness_doc_imports_resolved | **PASS** |

### Metrics

- `durationMs`: 10378

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install-skill | 0 | 3055ms | 87861 | [`node-logs/doc.markdown.import.targets.install-skill.log`](node-logs/doc.markdown.import.targets.install-skill.log) |  |
| install-harness | 0 | 2427ms | 87906 | [`node-logs/doc.markdown.import.targets.install-harness.log`](node-logs/doc.markdown.import.targets.install-harness.log) |  |
| install-target-doc | 0 | 2420ms | 87927 | [`node-logs/doc.markdown.import.targets.install-target-doc.log`](node-logs/doc.markdown.import.targets.install-target-doc.log) |  |
| install-source-doc | 0 | 2453ms | 87948 | [`node-logs/doc.markdown.import.targets.install-source-doc.log`](node-logs/doc.markdown.import.targets.install-source-doc.log) |  |

**Node-process stdout**: [node-logs/doc.markdown.import.targets.stdout.log](node-logs/doc.markdown.import.targets.stdout.log)

---

## `doc.rebind.after.all.removed` — **PASS**

executor start: `2026-07-12T18:10:36.247409Z`  
executor end: `2026-07-12T18:10:37.998101Z`  
spawn exit code: 0

**Input context**: [context/doc.rebind.after.all.removed.input.json](context/doc.rebind.after.all.removed.input.json)

### Assertions

| Name | Status |
|---|---|
| rebind_ok | **PASS** |
| docs_agents_dir_recreated | **PASS** |
| tracked_copy_recreated | **PASS** |
| claude_md_recreated | **PASS** |
| import_line_recreated | **PASS** |
| managed_section_recreated | **PASS** |
| ledger_file_recreated | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1186

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| rebind | 0 | 1175ms | 88186 | [`node-logs/doc.rebind.after.all.removed.rebind.log`](node-logs/doc.rebind.after.all.removed.rebind.log) |  |

**Node-process stdout**: [node-logs/doc.rebind.after.all.removed.stdout.log](node-logs/doc.rebind.after.all.removed.stdout.log)

---

## `doc.repo.installed` — **PASS**

executor start: `2026-07-12T18:10:04.397622Z`  
executor end: `2026-07-12T18:10:07.550047Z`  
spawn exit code: 0

**Input context**: [context/doc.repo.installed.input.json](context/doc.repo.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| install_ok | **PASS** |
| store_dir_present | **PASS** |
| manifest_in_store | **PASS** |
| source_in_store | **PASS** |
| no_claude_symlink | **PASS** |
| no_codex_symlink | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 2571

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install | 0 | 2562ms | 87824 | [`node-logs/doc.repo.installed.install.log`](node-logs/doc.repo.installed.install.log) |  |

### Published context

- `repoName`: `hello-doc-repo`
- `storeDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/docs/hello-doc-repo`

**Node-process stdout**: [node-logs/doc.repo.installed.stdout.log](node-logs/doc.repo.installed.stdout.log)

---

## `doc.repo.uninstalled` — **PASS**

executor start: `2026-07-12T18:10:44.559749Z`  
executor end: `2026-07-12T18:10:46.678838Z`  
spawn exit code: 0

**Input context**: [context/doc.repo.uninstalled.input.json](context/doc.repo.uninstalled.input.json)

### Assertions

| Name | Status |
|---|---|
| uninstall_ok | **PASS** |
| store_dir_removed | **PASS** |
| installed_record_removed | **PASS** |
| ledger_file_removed | **PASS** |
| active_binding_torn_down | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1536

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uninstall | 0 | 1527ms | 88272 | [`node-logs/doc.repo.uninstalled.uninstall.log`](node-logs/doc.repo.uninstalled.uninstall.log) |  |

**Node-process stdout**: [node-logs/doc.repo.uninstalled.stdout.log](node-logs/doc.repo.uninstalled.stdout.log)

---

## `doc.sync.force.clobbers` — **PASS**

executor start: `2026-07-12T18:10:26.373699Z`  
executor end: `2026-07-12T18:10:29.460248Z`  
spawn exit code: 0

**Input context**: [context/doc.sync.force.clobbers.input.json](context/doc.sync.force.clobbers.input.json)

### Assertions

| Name | Status |
|---|---|
| dest_matches_source_after_force | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 2460

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| sync-force | 0 | 2450ms | 88074 | [`node-logs/doc.sync.force.clobbers.sync-force.log`](node-logs/doc.sync.force.clobbers.sync-force.log) |  |

**Node-process stdout**: [node-logs/doc.sync.force.clobbers.stdout.log](node-logs/doc.sync.force.clobbers.stdout.log)

---

## `doc.sync.local.edit.preserved` — **PASS**

executor start: `2026-07-12T18:10:23.291496Z`  
executor end: `2026-07-12T18:10:26.371996Z`  
spawn exit code: 0

**Input context**: [context/doc.sync.local.edit.preserved.input.json](context/doc.sync.local.edit.preserved.input.json)

### Assertions

| Name | Status |
|---|---|
| dest_preserved_without_force | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 2467

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| sync | 0 | 2456ms | 88042 | [`node-logs/doc.sync.local.edit.preserved.sync.log`](node-logs/doc.sync.local.edit.preserved.sync.log) |  |

**Node-process stdout**: [node-logs/doc.sync.local.edit.preserved.stdout.log](node-logs/doc.sync.local.edit.preserved.stdout.log)

---

## `doc.sync.upgrade` — **PASS**

executor start: `2026-07-12T18:10:20.269377Z`  
executor end: `2026-07-12T18:10:23.290523Z`  
spawn exit code: 0

**Input context**: [context/doc.sync.upgrade.input.json](context/doc.sync.upgrade.input.json)

### Assertions

| Name | Status |
|---|---|
| sync_ok | **PASS** |
| dest_rewritten_with_new_bytes | **PASS** |
| ledger_re_written | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 2429

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| sync | 0 | 2417ms | 88013 | [`node-logs/doc.sync.upgrade.sync.log`](node-logs/doc.sync.upgrade.sync.log) |  |

**Node-process stdout**: [node-logs/doc.sync.upgrade.stdout.log](node-logs/doc.sync.upgrade.stdout.log)

---

## `doc.unbind.cleans.up` — **PASS**

executor start: `2026-07-12T18:10:29.461050Z`  
executor end: `2026-07-12T18:10:31.153107Z`  
spawn exit code: 0

**Input context**: [context/doc.unbind.cleans.up.input.json](context/doc.unbind.cleans.up.input.json)

### Assertions

| Name | Status |
|---|---|
| unbind_ok | **PASS** |
| tracked_copy_removed | **PASS** |
| import_line_removed | **PASS** |
| ledger_row_removed | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1095

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| unbind | 0 | 1081ms | 88117 | [`node-logs/doc.unbind.cleans.up.unbind.log`](node-logs/doc.unbind.cleans.up.unbind.log) |  |

**Node-process stdout**: [node-logs/doc.unbind.cleans.up.stdout.log](node-logs/doc.unbind.cleans.up.stdout.log)

---

## `doc.unbind.last.section.and.dir.gone` — **PASS**

executor start: `2026-07-12T18:10:34.635036Z`  
executor end: `2026-07-12T18:10:36.246688Z`  
spawn exit code: 0

**Input context**: [context/doc.unbind.last.section.and.dir.gone.input.json](context/doc.unbind.last.section.and.dir.gone.input.json)

### Assertions

| Name | Status |
|---|---|
| unbind_ok | **PASS** |
| build_tracked_removed | **PASS** |
| docs_agents_dir_pruned | **PASS** |
| docs_dir_pruned | **PASS** |
| claude_md_gone_or_clean | **PASS** |
| ledger_file_dropped | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1019

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| unbind-build | 0 | 1005ms | 88170 | [`node-logs/doc.unbind.last.section.and.dir.gone.unbind-build.log`](node-logs/doc.unbind.last.section.and.dir.gone.unbind-build.log) |  |

**Node-process stdout**: [node-logs/doc.unbind.last.section.and.dir.gone.stdout.log](node-logs/doc.unbind.last.section.and.dir.gone.stdout.log)

---

## `doc.unbind.one.of.two` — **PASS**

executor start: `2026-07-12T18:10:33.026160Z`  
executor end: `2026-07-12T18:10:34.634256Z`  
spawn exit code: 0

**Input context**: [context/doc.unbind.one.of.two.input.json](context/doc.unbind.one.of.two.input.json)

### Assertions

| Name | Status |
|---|---|
| unbind_ok | **PASS** |
| review_tracked_removed | **PASS** |
| build_tracked_survives | **PASS** |
| claude_keeps_build_import | **PASS** |
| claude_drops_review_import | **PASS** |
| agents_md_removed_when_empty | **PASS** |
| ledger_only_build_remains | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1027

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| unbind-review | 0 | 1014ms | 88155 | [`node-logs/doc.unbind.one.of.two.unbind-review.log`](node-logs/doc.unbind.one.of.two.unbind-review.log) |  |

**Node-process stdout**: [node-logs/doc.unbind.one.of.two.stdout.log](node-logs/doc.unbind.one.of.two.stdout.log)

---

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:09:41.769640Z`  
executor end: `2026-07-12T18:09:42.358037Z`  
spawn exit code: 0

**Input context**: [context/env.prepared.input.json](context/env.prepared.input.json)

### Assertions

| Name | Status |
|---|---|
| home_created | **PASS** |
| agent_home_created | **PASS** |
| codex_home_created | **PASS** |
| gemini_home_created | **PASS** |
| ports_allocated | **PASS** |

### Metrics

- `registryPort`: 65273
- `gatewayPort`: 65274
- `durationMs`: 30

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/agent-home/.gemini`
- `registryPort`: `65273`
- `gatewayPort`: `65274`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `resolver.cycles.verified` — **PASS**

executor start: `2026-07-12T18:09:42.358813Z`  
executor end: `2026-07-12T18:09:51.788514Z`  
spawn exit code: 0

**Input context**: [context/resolver.cycles.verified.input.json](context/resolver.cycles.verified.input.json)

### Assertions

| Name | Status |
|---|---|
| self-cycle.terminated | **PASS** |
| self-cycle.install_exit_zero | **PASS** |
| self-cycle.cycle_path_reported | **PASS** |
| self-cycle.each_unit_installed_once | **PASS** |
| self-cycle.no_stage_dirs_leaked | **PASS** |
| two-skill-cycle.terminated | **PASS** |
| two-skill-cycle.install_exit_zero | **PASS** |
| two-skill-cycle.cycle_path_reported | **PASS** |
| two-skill-cycle.each_unit_installed_once | **PASS** |
| two-skill-cycle.no_stage_dirs_leaked | **PASS** |
| three-skill-cycle.terminated | **PASS** |
| three-skill-cycle.install_exit_zero | **PASS** |
| three-skill-cycle.cycle_path_reported | **PASS** |
| three-skill-cycle.each_unit_installed_once | **PASS** |
| three-skill-cycle.no_stage_dirs_leaked | **PASS** |
| skill-plugin-cycle.terminated | **PASS** |
| skill-plugin-cycle.install_exit_zero | **PASS** |
| skill-plugin-cycle.cycle_path_reported | **PASS** |
| skill-plugin-cycle.each_unit_installed_once | **PASS** |
| skill-plugin-cycle.no_stage_dirs_leaked | **PASS** |
| plugin-plugin-cycle.terminated | **PASS** |
| plugin-plugin-cycle.install_exit_zero | **PASS** |
| plugin-plugin-cycle.cycle_path_reported | **PASS** |
| plugin-plugin-cycle.each_unit_installed_once | **PASS** |
| plugin-plugin-cycle.no_stage_dirs_leaked | **PASS** |

### Metrics

- `cases`: 5
- `durationMs`: 8859

### Inline logs

```
self-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=gateway pid=87553 log=/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/self-cycle/home/gateway.log | ! reference cycle: semantic-self-unit -> semantic-self-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/self-cycle/repos/coord-self-repository | INSTALLED: semantic-self-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/self-cycle/home/skills/semantic-self-unit | ✓ units.lock.toml: wrote 1 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/self-cycle/home/units.lock.toml
two-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-alpha-unit -> semantic-beta-unit -> semantic-alpha-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/two-skill-cycle/repos/coord-alpha-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/two-skill-cycle/repos/coord-beta-repository | INSTALLED: semantic-alpha-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-alpha-unit | INSTALLED: semantic-beta-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-beta-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/two-skill-cycle/home/units.lock.toml
three-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-first-unit -> semantic-second-unit -> semantic-third-unit -> semantic-first-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/three-skill-cycle/repos/coord-first-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/three-skill-cycle/repos/coord-second-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/three-skill-cycle/repos/coord-third-repository | INSTALLED: semantic-first-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-first-unit | INSTALLED: semantic-second-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-second-unit | INSTALLED: semantic-third-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-third-unit | ✓ units.lock.toml: wrote 3 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/three-skill-cycle/home/units.lock.toml
skill-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-skill-unit -> semantic-plugin-unit -> semantic-skill-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-skill-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-plugin-repository | INSTALLED: semantic-skill-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/skill-plugin-cycle/home/skills/semantic-skill-unit | INSTALLED: semantic-plugin-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/skill-plugin-cycle/home/plugins/semantic-plugin-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/skill-plugin-cycle/home/units.lock.toml
plugin-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-left-plugin -> semantic-right-plugin -> semantic-left-plugin |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-left-plugin-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-right-plugin-repository | INSTALLED: semantic-left-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-left-plugin | INSTALLED: semantic-right-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-right-plugin | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10940586673023840542/resolver-cycle-matrix/plugin-plugin-cycle/home/units.lock.toml
```

**Node-process stdout**: [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log)

---

## `skill-manager.env.project.context` — **PASS**

executor start: `2026-07-12T18:10:03.724468Z`  
executor end: `2026-07-12T18:10:04.396831Z`  
spawn exit code: 0

**Input context**: [context/skill-manager.env.project.context.input.json](context/skill-manager.env.project.context.input.json)

### Assertions

| Name | Status |
|---|---|
| project_context_detected | **PASS** |
| project_name_reported | **PASS** |
| manifest_reported | **PASS** |
| child_home_reported | **PASS** |
| launch_env_reported | **PASS** |
| declared_sections_reported | **PASS** |
| project_env_docs_reported | **PASS** |

### Metrics

- `durationMs`: 192

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| env-project-context | 0 | 61ms | 87812 | [`node-logs/skill-manager.env.project.context.env-project-context.log`](node-logs/skill-manager.env.project.context.env-project-context.log) |  |

**Node-process stdout**: [node-logs/skill-manager.env.project.context.stdout.log](node-logs/skill-manager.env.project.context.stdout.log)

---

## `skill-manager.skill.docs.projected` — **PASS**

executor start: `2026-07-12T18:10:03.138545Z`  
executor end: `2026-07-12T18:10:03.723723Z`  
spawn exit code: 0

**Input context**: [context/skill-manager.skill.docs.projected.input.json](context/skill-manager.skill.docs.projected.input.json)

### Assertions

| Name | Status |
|---|---|
| skill_md_mentions_projects_and_child_homes | **PASS** |
| project_reference_complete | **PASS** |
| workflow_routes_project_resolution | **PASS** |
| env_helper_project_context_documented | **PASS** |
| skill_manifest_mentions_projects | **PASS** |

### Metrics

- `durationMs`: 5

**Node-process stdout**: [node-logs/skill-manager.skill.docs.projected.stdout.log](node-logs/skill-manager.skill.docs.projected.stdout.log)

---

