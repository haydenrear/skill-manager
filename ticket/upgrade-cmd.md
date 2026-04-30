When a new version of a skill is available it should ask whether to add it. There should be an upgrade path for a skill.

Skill manager should have an upgrade command that does the brew command with tap. And it should have a (a new version of skill manager is available thing).

So both of these can be in a single upgrade command. upgrade --skill, upgrade --skill-manager, etc. And we can also do a cool downgrade deployment channel, rollbacks here (TBD) - i.e. it will talk to the github runner effectively (public)? Or potentially the server endpoint? We'll look into that eventually for rollbacks.

The cool thing about skill-manager upgrade --skill-manager is that can't we just call brew ... ? Make it that simple.

Then we can add a skill-manager upgrade --all --skills which upgrades all skills. And skill-manager upgrade --all which upgrades all skills and upgrades the skill-manager CLI.
