The question - how to do the envs?

What about env overlays? Recursive overlays of envs. Lean into recursive parent-child relationship with env overlay. Like how pip virtual environments interact with the path, deactivate and activate. And then taking this to the next level by intercepting any CLI call and performing actions. And then those actions are visible to the agent.
 
Thinking of skill-manager as a package manager of package managers.

If a skill-manager toml file is found, then it has an env - what it does is override the current env with this env by finding the parent skill-manager. This is where I'd like to lean into pip venv where pip and python use the bin from the project. So the envs can then be nested. Then when an agent deploys another agent, it first prepares the harness, which is using skill-manager - as a part of this it prepares the environment, which includes specific instructions on intercepting interactions also with file system, CLI, MCP, etc. Just adding these as options so we can move more towards a push model instead of a pull. The pull being much more efficient. 

So this is really leaning into the "virtual" of virtual environment from pip, conda, etc.
 
Similarly, the skill-manager bin will be overriden but keep prev on. 

That's the primitive - and then how can this be extended?

I've also been thinking of how we'd like to introduce an abstraction for the path and for the repos.

When an agent reads a skill repo it can use the symbolic link. But the first time it writes a worktree should be made in the same spot, with a message that it's happening. This allows the symbolic link to work but development to be able to happen, and the cost of the abstraction to be paid at the moment write happens. This is mostly for skill-project.toml, but probably child skill managers should be introduced also for skill-manager.toml.
 
Similarly, it would be nice to add this for the file system in general so that agents can share safely.
