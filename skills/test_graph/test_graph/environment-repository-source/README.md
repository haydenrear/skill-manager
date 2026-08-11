# Test Graph Environment Repository Source

This directory is source material for TG-5D contract validation. It is not a
Git repository and must not contain a checked-in `.git` directory.

The `generatedEnvironmentRepositoryFixture` graph copies these files into a
run-local temporary directory, initializes a real Git repository there, commits
the files, and passes that repository path/file URL through the test graph
context for later environment repository execution tickets.
