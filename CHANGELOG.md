# Changelog

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
