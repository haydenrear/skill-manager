There should be the following logic:

- an MCP server can be deployed per session - so if a new session calls it it shows not deployed. 
- then if the server doesn't receive any request from that session for some period, it kills those servers with their initialization values
- Also some servers can be deployed and then be accessed by any client, and un-deployed or redeployed with different arguments -> the initialization for these should stick - i.e. if you deploy with this one, then every time you start virtual server it spins it up, and uses last deployed initialization arguments
