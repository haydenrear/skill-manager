---
type: llm
weight: 2
---

THIS GRADER READS THE FINAL RESPONSE ONLY -- not the workspace, not tool output.

Score 1 if the response identifies the cause as the CLI belonging to a
DIFFERENT home (/work/other-repo) than the one being bootstrapped (/work/demo),
and proposes using the right home's CLI or naming the home explicitly (e.g. via
PATH, SKILL_MANAGER_CLI or SKILL_MANAGER_HOME). Score 0 if it says the CLI is
outdated or missing, or recommends upgrading or reinstalling skill-manager.
