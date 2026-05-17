# Changelog

## [0.13.1](https://github.com/haydenrear/skill-manager/compare/v0.13.0...v0.13.1) (2026-05-17)


### Bug Fixes

* **merge-clean:** fix(propagate fm err): feat(unit-ref): ([3aca33c](https://github.com/haydenrear/skill-manager/commit/3aca33cd08ad2a2bafab52d1bda288171cf1b2bd))
* **merge-clean:** fix(propagate fm err): feat(unit-ref): ([7e39967](https://github.com/haydenrear/skill-manager/commit/7e39967831caee46f24c6e871957c9ef0e9b03f2))
* **propagate fm err:** feat(unit-ref): ([9a34f11](https://github.com/haydenrear/skill-manager/commit/9a34f116b8ad95de2b69b145704d591f01c1c76f))

## [0.13.0](https://github.com/haydenrear/skill-manager/compare/v0.12.2...v0.13.0) (2026-05-14)


### Features

* **skill-references:** added ability to add validatable references to other markdowns in your skill manager-enabled units (docs, plugins, harnesses, skills) ([#68](https://github.com/haydenrear/skill-manager/issues/68)) ([480fe02](https://github.com/haydenrear/skill-manager/commit/480fe02779155f841a422ac83f83c014c4dc9857))

## [0.12.2](https://github.com/haydenrear/skill-manager/compare/v0.12.1...v0.12.2) (2026-05-14)


### Bug Fixes

* show and manage non-skill units ([#65](https://github.com/haydenrear/skill-manager/issues/65)) ([a97cc86](https://github.com/haydenrear/skill-manager/commit/a97cc861c813d389866d50e51696b4a0325c59be))

## [0.12.1](https://github.com/haydenrear/skill-manager/compare/v0.12.0...v0.12.1) (2026-05-14)


### Bug Fixes

* **toml:** document scoped npm CLI ref parsing ([bcae334](https://github.com/haydenrear/skill-manager/commit/bcae334d3e48b6700f6abc36cab4d6016d408629))

## [0.12.0](https://github.com/haydenrear/skill-manager/compare/v0.11.1...v0.12.0) (2026-05-13)


### Features

* **bindings:** split install from bind via a per-unit projection ledger ([#49](https://github.com/haydenrear/skill-manager/issues/49)) ([e59f5b1](https://github.com/haydenrear/skill-manager/commit/e59f5b10d5338bfa806892ea7926de0e9e1c3adc))
* **doc-repos:** bind markdown files via tracked copies + @-imports ([#48](https://github.com/haydenrear/skill-manager/issues/48)) ([1207667](https://github.com/haydenrear/skill-manager/commit/1207667abe18361df4a68496593d665d7a63860b))
* **harness:** harness templates compose skills + plugins + doc-repos ([#47](https://github.com/haydenrear/skill-manager/issues/47)) ([50c45a9](https://github.com/haydenrear/skill-manager/commit/50c45a917f734190d3fdbda7403cf8955d4a29a0))
* **harness:** instantiate writes to CLAUDE_CONFIG_DIR / CODEX_HOME / --project-dir ([b2393dd](https://github.com/haydenrear/skill-manager/commit/b2393dd79be9bfc3acfe9c54fd33717227f5aa1e))

## [0.11.1](https://github.com/haydenrear/skill-manager/compare/v0.11.0...v0.11.1) (2026-05-12)


### Bug Fixes

* **fetcher:** SSH + private-repo support via host git, with typed exceptions for the CLI banner ([890b96f](https://github.com/haydenrear/skill-manager/commit/890b96fb460843e1b395813ea8d43a1e2e949bb1))
* **resolve:** accumulate per-coord failures, propagate via ContextFact / addError instead of exceptions ([843854d](https://github.com/haydenrear/skill-manager/commit/843854d7f8c180ca68bce9dbae9dc9f40c65ceca))
* **test:** sandbox CLAUDE_HOME / CODEX_HOME so unit tests can't pollute real harness config ([0a02cbf](https://github.com/haydenrear/skill-manager/commit/0a02cbf18e8f97bca4ff3ce4a1d81a3769129297))

## [0.11.0](https://github.com/haydenrear/skill-manager/compare/v0.10.0...v0.11.0) (2026-05-10)


### Features

* **cli:** skill-script backend — run install scripts bundled in a skill ([29058a6](https://github.com/haydenrear/skill-manager/commit/29058a636ff793eeb9f9268fbc40be342da70ac3))
* **cli:** skill-script content-fingerprint rerun gate ([e640409](https://github.com/haydenrear/skill-manager/commit/e640409a9bd3dd7a307759e6478603443d4fc871))


### Bug Fixes

* **onboard:** install bundled skills from github by default ([a2b0145](https://github.com/haydenrear/skill-manager/commit/a2b0145e2528dfd2c7cc9f4be7bd7e7463e4e69d))

## [0.10.0](https://github.com/haydenrear/skill-manager/compare/v0.9.0...v0.10.0) (2026-05-08)


### Features

* added fix for codex ([#53](https://github.com/haydenrear/skill-manager/issues/53)) ([ef8b44d](https://github.com/haydenrear/skill-manager/commit/ef8b44d926a4255cfd3ef365418bb325a8967da3))

## [0.9.0](https://github.com/haydenrear/skill-manager/compare/v0.8.0...v0.9.0) (2026-05-07)


### Features

* try to fix parallel jobs ([#51](https://github.com/haydenrear/skill-manager/issues/51)) ([268d928](https://github.com/haydenrear/skill-manager/commit/268d9289bdb91a802d1a41ff9211bdc645f1e386))

## [0.8.0](https://github.com/haydenrear/skill-manager/compare/v0.7.0...v0.8.0) (2026-05-06)


### Features

* **lifecycle:** per-skill state machine, install-source routing, reconciler-on-every-command ([890e2c9](https://github.com/haydenrear/skill-manager/commit/890e2c9fb84c31d371489dd8bd6970d3d2e21164))
* **plan:** ticket 05 — planner widening + heterogeneous DAG + cycle detection ([47a4576](https://github.com/haydenrear/skill-manager/commit/47a4576d19b563000a51a856c5e606df2a1c0e1f))
* **source:** per-skill git provenance tracking + sync --merge for upstream pulls ([f83a165](https://github.com/haydenrear/skill-manager/commit/f83a165ec91157a4305aa32f94f5dafa213c9802))
* **source:** track install-time git ref so --git-latest follows the right branch ([a495790](https://github.com/haydenrear/skill-manager/commit/a4957909f7001e98fbd524921bf94baf9d5219e0))
* **sync:** aggregate git pulls across all installed skills + summary ([73811e5](https://github.com/haydenrear/skill-manager/commit/73811e53c943f35eaf933d9e6c1dc82976a7817b))
* **sync:** implicit-origin pull + restructure hyper/source-tracking coverage ([45c19e7](https://github.com/haydenrear/skill-manager/commit/45c19e798c8d9974e4ad211d083de70c7c8ae828))
* **sync:** server-published git_sha is the default merge target; --git-latest opts into HEAD ([ce08cad](https://github.com/haydenrear/skill-manager/commit/ce08cadbe329ee6cd56c1e9d97b7fa111ad10e52))


### Bug Fixes

* **effects:** correctness fixes from full test-graph run ([0de898b](https://github.com/haydenrear/skill-manager/commit/0de898b4a05743df136465bc967477057dc9870c))
* **effects:** restore --wait-seconds + sync-refused banner after Phase 11 ([5955892](https://github.com/haydenrear/skill-manager/commit/5955892d5661c828776e0b6cf3cf895960a249cd))
* **effects:** surface real failures swallowed by tail-effect handlers ([001925e](https://github.com/haydenrear/skill-manager/commit/001925e4f105e1168ddfc2fc78d221c8f9a58406))
* **plugin-smoke:** unblock test_graph after tickets 12 + 13 + 15 ([1226ec5](https://github.com/haydenrear/skill-manager/commit/1226ec5eebd96fc117a44eb87eaf21247e545b9e))
* **reconcile:** suppress NEEDS_GIT_MIGRATION for bundled skills ([512ea07](https://github.com/haydenrear/skill-manager/commit/512ea077ca37791b2a77937fce604d2b8b9b53c7))
* **sync:** reload targets after all-skills git sync so MCP register sees the post-merge TOML ([9ac50d5](https://github.com/haydenrear/skill-manager/commit/9ac50d5e8bc3e114221bb8c8533fd509c4cf3212))
* **sync:** supply git identity for merge commits + widen CI matrix ([aa08020](https://github.com/haydenrear/skill-manager/commit/aa0802070549521369a9f7eb2cfc7ec051328621))

## [0.7.0](https://github.com/haydenrear/skill-manager/compare/v0.6.0...v0.7.0) (2026-04-30)


### Features

* add release-please pipeline, brew + GHCR publish, self-host compose ([#10](https://github.com/haydenrear/skill-manager/issues/10)) ([5ca7382](https://github.com/haydenrear/skill-manager/commit/5ca73825c4d449379002b04bc65922f5df0b9e17))
* **cli:** add uninstall, sync, upgrade commands + stdio MCP smoke coverage ([4e74673](https://github.com/haydenrear/skill-manager/commit/4e74673ab679ed97ff5d3dc5e6bd1780c4bbae3c))
* **cli:** sync --from &lt;dir&gt; for local skill iteration without publish ([9fb8c54](https://github.com/haydenrear/skill-manager/commit/9fb8c54c49c4c4b0f3f6c784fc2455afc227191b))
* Feature/default port virtual mcp 51717 ([#29](https://github.com/haydenrear/skill-manager/issues/29)) ([9eab98f](https://github.com/haydenrear/skill-manager/commit/9eab98f65d1dcf663da0585600a924cfaa522295))
* Feature/skill sym ([#27](https://github.com/haydenrear/skill-manager/issues/27)) ([e579ae4](https://github.com/haydenrear/skill-manager/commit/e579ae43a6d5c67a4833e5558183187e9a17d79b))
* **onboard:** bundle skill-manager + skill-publisher with the server ([860ca0d](https://github.com/haydenrear/skill-manager/commit/860ca0da7e27c7bc024ec2b0ae0f5bdb779b57d7))


### Bug Fixes

* drop component-in-tag so brew formula bump comparator works ([#22](https://github.com/haydenrear/skill-manager/issues/22)) ([09ddaea](https://github.com/haydenrear/skill-manager/commit/09ddaea60ce87b425a649acb4ae101f4e06035fc))
* **gateway,test_graph:** restore jbang compile + capture subprocess logs ([7373aec](https://github.com/haydenrear/skill-manager/commit/7373aecb220ec5ddab1922aabe9a2e5fb87977b3))
* **gateway:** reject invalid default_scope and match Python digest bytes ([72395a0](https://github.com/haydenrear/skill-manager/commit/72395a01c1f7dde1f72424e292977f84d87650d3))
* **gateway:** self-heal broken MCP downstream sessions ([0292a2f](https://github.com/haydenrear/skill-manager/commit/0292a2fcb48f04ef2036f2978e338b3ca64e5d9c))
* **gateway:** sync agent MCP configs as part of `gateway up` ([#31](https://github.com/haydenrear/skill-manager/issues/31)) ([f9cf724](https://github.com/haydenrear/skill-manager/commit/f9cf724954d3090d23c0e38f8d4eda07eb987883))
* pass GITHUB_TOKEN as `token` input to bump-homebrew-formula-action ([#16](https://github.com/haydenrear/skill-manager/issues/16)) ([97bd130](https://github.com/haydenrear/skill-manager/commit/97bd1306eaff8c4c80d1c27a333b1e70256aee4c))
* pass GITHUB_TOKEN as env to bump-homebrew-formula-action ([#18](https://github.com/haydenrear/skill-manager/issues/18)) ([06c9d60](https://github.com/haydenrear/skill-manager/commit/06c9d60141bf3f915ef1d2179a242643edb14091))
* pass tag-name to bump-homebrew-formula-action ([#14](https://github.com/haydenrear/skill-manager/issues/14)) ([5d47890](https://github.com/haydenrear/skill-manager/commit/5d478902496a662bd0ac8b2d2d2fc1160594b714))
* **release:** drop release-please version-only bumps from change detection ([72bf149](https://github.com/haydenrear/skill-manager/commit/72bf1490aaaceb7cb3848bd14363913b0ad51ecc))
* rename homebrew-tap secret to fix HOMBREW typo ([#20](https://github.com/haydenrear/skill-manager/issues/20)) ([5812051](https://github.com/haydenrear/skill-manager/commit/5812051bcbfa46201355834af81cbd2eef426e8d))
* skip CI test-graph for non-tested file changes ([#25](https://github.com/haydenrear/skill-manager/issues/25)) ([d767dea](https://github.com/haydenrear/skill-manager/commit/d767dea8131534d3bbff8b43b57ebd128ae4465a))
* use toml extra-file updater for pyproject.toml, not python ([#12](https://github.com/haydenrear/skill-manager/issues/12)) ([98c6f46](https://github.com/haydenrear/skill-manager/commit/98c6f465eeac511b2076f8efb64fb9b1bf65ad2a))

## [0.6.0](https://github.com/haydenrear/skill-manager/compare/v0.5.1...v0.6.0) (2026-04-30)


### Features

* **cli:** add uninstall, sync, upgrade commands + stdio MCP smoke coverage ([4e74673](https://github.com/haydenrear/skill-manager/commit/4e74673ab679ed97ff5d3dc5e6bd1780c4bbae3c))
* **cli:** sync --from &lt;dir&gt; for local skill iteration without publish ([9fb8c54](https://github.com/haydenrear/skill-manager/commit/9fb8c54c49c4c4b0f3f6c784fc2455afc227191b))


### Bug Fixes

* **gateway:** self-heal broken MCP downstream sessions ([0292a2f](https://github.com/haydenrear/skill-manager/commit/0292a2fcb48f04ef2036f2978e338b3ca64e5d9c))
* **release:** drop release-please version-only bumps from change detection ([72bf149](https://github.com/haydenrear/skill-manager/commit/72bf1490aaaceb7cb3848bd14363913b0ad51ecc))

## [0.5.1](https://github.com/haydenrear/skill-manager/compare/v0.5.0...v0.5.1) (2026-04-29)


### Bug Fixes

* **gateway:** sync agent MCP configs as part of `gateway up` ([#31](https://github.com/haydenrear/skill-manager/issues/31)) ([f9cf724](https://github.com/haydenrear/skill-manager/commit/f9cf724954d3090d23c0e38f8d4eda07eb987883))

## [0.5.0](https://github.com/haydenrear/skill-manager/compare/v0.4.0...v0.5.0) (2026-04-29)


### Features

* Feature/default port virtual mcp 51717 ([#29](https://github.com/haydenrear/skill-manager/issues/29)) ([9eab98f](https://github.com/haydenrear/skill-manager/commit/9eab98f65d1dcf663da0585600a924cfaa522295))

## [0.4.0](https://github.com/haydenrear/skill-manager/compare/v0.3.1...v0.4.0) (2026-04-29)


### Features

* Feature/skill sym ([#27](https://github.com/haydenrear/skill-manager/issues/27)) ([e579ae4](https://github.com/haydenrear/skill-manager/commit/e579ae43a6d5c67a4833e5558183187e9a17d79b))

## [0.3.1](https://github.com/haydenrear/skill-manager/compare/v0.3.0...v0.3.1) (2026-04-29)


### Bug Fixes

* skip CI test-graph for non-tested file changes ([#25](https://github.com/haydenrear/skill-manager/issues/25)) ([d767dea](https://github.com/haydenrear/skill-manager/commit/d767dea8131534d3bbff8b43b57ebd128ae4465a))

## [0.3.0](https://github.com/haydenrear/skill-manager/compare/v0.2.4...v0.3.0) (2026-04-29)


### Features

* add release-please pipeline, brew + GHCR publish, self-host compose ([#10](https://github.com/haydenrear/skill-manager/issues/10)) ([5ca7382](https://github.com/haydenrear/skill-manager/commit/5ca73825c4d449379002b04bc65922f5df0b9e17))
* **onboard:** bundle skill-manager + skill-publisher with the server ([860ca0d](https://github.com/haydenrear/skill-manager/commit/860ca0da7e27c7bc024ec2b0ae0f5bdb779b57d7))


### Bug Fixes

* drop component-in-tag so brew formula bump comparator works ([#22](https://github.com/haydenrear/skill-manager/issues/22)) ([09ddaea](https://github.com/haydenrear/skill-manager/commit/09ddaea60ce87b425a649acb4ae101f4e06035fc))
* **gateway,test_graph:** restore jbang compile + capture subprocess logs ([7373aec](https://github.com/haydenrear/skill-manager/commit/7373aecb220ec5ddab1922aabe9a2e5fb87977b3))
* **gateway:** reject invalid default_scope and match Python digest bytes ([72395a0](https://github.com/haydenrear/skill-manager/commit/72395a01c1f7dde1f72424e292977f84d87650d3))
* pass GITHUB_TOKEN as `token` input to bump-homebrew-formula-action ([#16](https://github.com/haydenrear/skill-manager/issues/16)) ([97bd130](https://github.com/haydenrear/skill-manager/commit/97bd1306eaff8c4c80d1c27a333b1e70256aee4c))
* pass GITHUB_TOKEN as env to bump-homebrew-formula-action ([#18](https://github.com/haydenrear/skill-manager/issues/18)) ([06c9d60](https://github.com/haydenrear/skill-manager/commit/06c9d60141bf3f915ef1d2179a242643edb14091))
* pass tag-name to bump-homebrew-formula-action ([#14](https://github.com/haydenrear/skill-manager/issues/14)) ([5d47890](https://github.com/haydenrear/skill-manager/commit/5d478902496a662bd0ac8b2d2d2fc1160594b714))
* rename homebrew-tap secret to fix HOMBREW typo ([#20](https://github.com/haydenrear/skill-manager/issues/20)) ([5812051](https://github.com/haydenrear/skill-manager/commit/5812051bcbfa46201355834af81cbd2eef426e8d))
* use toml extra-file updater for pyproject.toml, not python ([#12](https://github.com/haydenrear/skill-manager/issues/12)) ([98c6f46](https://github.com/haydenrear/skill-manager/commit/98c6f465eeac511b2076f8efb64fb9b1bf65ad2a))

## [0.2.4](https://github.com/haydenrear/skill-manager/compare/skill-manager-v0.2.3...skill-manager-v0.2.4) (2026-04-29)


### Bug Fixes

* rename homebrew-tap secret to fix HOMBREW typo ([#20](https://github.com/haydenrear/skill-manager/issues/20)) ([5812051](https://github.com/haydenrear/skill-manager/commit/5812051bcbfa46201355834af81cbd2eef426e8d))

## [0.2.3](https://github.com/haydenrear/skill-manager/compare/skill-manager-v0.2.2...skill-manager-v0.2.3) (2026-04-29)


### Bug Fixes

* pass GITHUB_TOKEN as env to bump-homebrew-formula-action ([#18](https://github.com/haydenrear/skill-manager/issues/18)) ([06c9d60](https://github.com/haydenrear/skill-manager/commit/06c9d60141bf3f915ef1d2179a242643edb14091))

## [0.2.2](https://github.com/haydenrear/skill-manager/compare/skill-manager-v0.2.1...skill-manager-v0.2.2) (2026-04-29)


### Bug Fixes

* pass GITHUB_TOKEN as `token` input to bump-homebrew-formula-action ([#16](https://github.com/haydenrear/skill-manager/issues/16)) ([97bd130](https://github.com/haydenrear/skill-manager/commit/97bd1306eaff8c4c80d1c27a333b1e70256aee4c))

## [0.2.1](https://github.com/haydenrear/skill-manager/compare/skill-manager-v0.2.0...skill-manager-v0.2.1) (2026-04-29)


### Bug Fixes

* pass tag-name to bump-homebrew-formula-action ([#14](https://github.com/haydenrear/skill-manager/issues/14)) ([5d47890](https://github.com/haydenrear/skill-manager/commit/5d478902496a662bd0ac8b2d2d2fc1160594b714))

## [0.2.0](https://github.com/haydenrear/skill-manager/compare/skill-manager-v0.1.0...skill-manager-v0.2.0) (2026-04-28)


### Features

* add release-please pipeline, brew + GHCR publish, self-host compose ([#10](https://github.com/haydenrear/skill-manager/issues/10)) ([5ca7382](https://github.com/haydenrear/skill-manager/commit/5ca73825c4d449379002b04bc65922f5df0b9e17))
* **onboard:** bundle skill-manager + skill-publisher with the server ([860ca0d](https://github.com/haydenrear/skill-manager/commit/860ca0da7e27c7bc024ec2b0ae0f5bdb779b57d7))


### Bug Fixes

* **gateway,test_graph:** restore jbang compile + capture subprocess logs ([7373aec](https://github.com/haydenrear/skill-manager/commit/7373aecb220ec5ddab1922aabe9a2e5fb87977b3))
* **gateway:** reject invalid default_scope and match Python digest bytes ([72395a0](https://github.com/haydenrear/skill-manager/commit/72395a01c1f7dde1f72424e292977f84d87650d3))
* use toml extra-file updater for pyproject.toml, not python ([#12](https://github.com/haydenrear/skill-manager/issues/12)) ([98c6f46](https://github.com/haydenrear/skill-manager/commit/98c6f465eeac511b2076f8efb64fb9b1bf65ad2a))
