The coords are not added to skill publisher. So the agent creating the skill has no idea how to reference the coordinates in things like reference.

They need to be told how to reference github, git, file, and server, and how to check with skill-manager if it exists in local, in local how to get the coord, etc.

Moreover, in list, we should probably list the coord so that a skill publisher who's publishing a skill can do a 

```shell
skill-manager list
```

and get the exact coordinate to put in the skill references.

So the skill-publisher-skill should also include this information.
