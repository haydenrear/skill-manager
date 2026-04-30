# Changelog

## [0.6.0](https://github.com/haydenrear/skill-manager/compare/v0.5.1...v0.6.0) (2026-04-30)


### Features

* **cli:** add uninstall, sync, upgrade commands + stdio MCP smoke coverage ([4e74673](https://github.com/haydenrear/skill-manager/commit/4e74673ab679ed97ff5d3dc5e6bd1780c4bbae3c))


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
