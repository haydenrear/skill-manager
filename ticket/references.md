Should not just be skill-references - should be able to be any type, such as skill-reference, plugin-reference or doc-repo-reference. It should be same target-like idea.

Moreover, the references should be able to be github repo, file ref, etc. Maybe it falls back to what it's in the skill manager repo, then tries to see if file, github, git, etc, and then tries to find a reference in the skill manager server (to get the metadata). Such as github:haydenrear/skill-manager or git:[host]/..., or file:..., or just [name] where name checks the local and then tries to fallback in particular Coord ways. These abstractions exist, Coord, I think, we should try to be using similar for this instead of what currently exists in skill_references, which is just name and version. It's also not all that important to support version, just assume latest mostly for this, we'll add version as a follow-on ticket (because it would be git tags fallback, however version map to git sha can be kept in the server).

This should be relatively simple seeing as it's just a way to get the metadata for the skill, plugin, or doc. Then it allows for importing from the transitively depended, we then assume we can reference it anywhere.

We can do this before we do imports. The imports also similarly can be referenced by coord or ref.
