There needs to be instructions to, instead of continuing to run test graph over and over again to get to a failing node (sometimes this takes a long time and it fails in the same place for different reasons over and over), when a node fails, try to fix it and then rerun just that node with the context.

This is one of the things that's great about test graph - each node is independently runnable as a script. So you can fix it right then and there, rerun it to validate that it's finished. Then rerun it from the beginning.

More clearly, weigh the time cost of rerunning from the beginning. Don't always rerun from the beginning, you can always rerun just that node.
