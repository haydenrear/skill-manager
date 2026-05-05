Plan should split out each plan action into it's own effect and fact. If one of them fails, for instance say a CLI or MCP register fails, or download of package manager, then it should be noted and played back again. 

Each one can be split into it's own effect, with own fact and actions to the store properly. Then each unit source can track installation of CLI, package manager, etc.
