---
type: tool_used
tool: Bash
max: 2
weight: 1
---

The prompt hands over one block and says not to work around failures. More than
two Bash calls means the agent started improvising -- which is a signal about
the PROMPT, not the sandbox: the two graders above already answer the sandbox
question by looking at the filesystem, and they answer it whether the agent
narrated the result honestly or not.
