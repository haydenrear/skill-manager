The coords are not added to skill publisher. So the agent creating the skill has no idea how to reference the coordinates in things like reference.

They need to be told how to reference github, git, file, and server, and how to check with skill-manager if it exists in local, in local how to get the coord, etc.

Moreover, in list, we should probably list the coord so that a skill publisher who's publishing a skill can do a 

```shell
skill-manager list
```

and get the exact coordinate to put in the skill references.

So the skill-publisher-skill should also include this information.

---

**SI-18 (2026-09-21):** this note predates the plugin merge. `skill-publisher-skill`
no longer exists as a repository this project tracks — the skill it means is
`unit-authoring`, a contained skill of the `tla-spec-dev` plugin
(`github:haydenrear/tla-spec-dev-plugin`), and its coords page is
`skills/unit-authoring/references/coords-and-distribution.md`. The ask itself is
unchanged and still open; only where it lands has moved.
