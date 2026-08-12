Before/after complexity descriptors are byte-identical apart from the
report paths (see descriptor-before.txt / descriptor-after.txt): the
ISSUE-168 slice adds operators and a guarded reject branch to
ResolveProjectDependencies but no state variable, no action, and no
config change, so every measured dimension is unchanged.
