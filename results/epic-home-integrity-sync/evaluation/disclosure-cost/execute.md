Your session has SKILL_MANAGER_HOME set, and SKILL_MANAGER_CLI names the
skill-manager binary to use. Using the product's OWN commands — find them, do not
invent them — do the following. Do not modify anything.

1. Report which units are installed in this home, and whether any of them is stale
   relative to its source.
2. Report which derived artifacts in this home are stale, and the exact command that
   would rebuild ONE of them. DO NOT actually rebuild.

If a command fails, report it with its exact error text rather than working around it
— a failure is a finding, not an obstacle.

Output ONLY a JSON object, no prose around it:
{"installed":{"command":"...","exit":0,"units":0,"stale":0},
 "artifacts":{"command":"...","exit":0,"stale":0,"rebuild_command":"..."},
 "failures":["..."]}
