There should be initialization parameters provided in the skill.md, and then add an initialize tool. Then, we'll assume that each tool referenced 
in skill can only be initialized once at a time - so if an initialization happens again, the old one is closed.

Moreover, the initialization parameters can be saved and a timeout had. Then, the last initialization parameters provided get used if it's closed.

Then, based on the MCP server, there can be callbacks provided, it can be to load a STDIO or http server with headers, etc.

Then, for the tool search and browse, we'll also include schema for the initialization (if it has any).

This fixes the weird ones, for example git MCP servers (as an example, even though that's not going to be used), or postgresql (even though also
probably not going to be used). Effectively items for which we want to pass a schema that evolves a lot, but it's pinned to a particular path 
or other data. Of course, we can add a path parameter, but if it's a docker container you'd have to mount the entire thing, and it's better
to mount it. 

Or for instance if it passes an unique ID at the start or an environmental variable needs to be passed to start, for auth. If we can add it 
from the environment statically, great, but sometimes you won't be able to, you'll set it during the workflow, and otherwise would have to 
restart all of them.

So this can be thought of as operating a deployable catalog of MCP servers. 

Instead of browse tools, it will be browse active tools (deployed tools) and then there will be a browse MCP servers. Then it will be
describe_mcp_server - this will return the initialization schema. 

The browse MCP servers will have list (deployed) or (not deployed).

If you do describe on deployed, it returns schema and deployed initialization parameters (some initialization parameters are SECRET type, they don't
get shown).

If you do describe on not deployed, it just returns initialization parameters.

Then it will have a deploy option with initialization parameters (will redeploy if possible), returning path.

Then, you can browse tools filtering by MCP server path (you can just show first tool names, then describe each tool in turn).

NOTE: this server is **local only**. Only one person loads it at a time, it runs locally, even though over streamable HTTP.
