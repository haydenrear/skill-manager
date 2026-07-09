We need versioning of the skills through git hashes and git commit tags.

This is really as easy as just adding # or $...

However, it's a bit of an issue unless you spin up the harness pointing to the skill-project.toml.

Skill-project.toml is a no brainer - you just support one version per and then you load that one on the SKILL_MANAGER_HOME in that project's .skill-manager.

But when you're pointing to a "shared" or a "global" config that's when it gets hairy. We could save only the latest in the SKILL_MANAGER_HOME - and then you can pin per project. But then if you spin up an agent in a repo you can't use the global. 

That can fit into our venv idea. Whereby if you call "claude" locally when you have a skill-manager venv, it reroutes with SKILL_MANAGER_HOME, CLAUDE_DIR, etc, fixing this issue.
