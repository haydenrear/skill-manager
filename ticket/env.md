The env.sh script is a bit weird. Why not just put that in the CLI? Specifically the skill-manager list command is doubled there.

Instead, could just put this in the CLI - and env command, or add the metadata to the list with options such as --cli, --skill, etc. It's a bit more ergonomic - the AI is likely to just use the --help. And uv is a transitive dependency so we know that can work.

However, env.sh could be nice for seeing how the env is decided - you can just read the script - it often does that - and idiot proof. So a cool thing to do potentially is to keep skill-manager list - add CLI, MCP, transitive skills, and able to narrow down by skill and keep env.sh, which can mutate path if we decide to do that.
