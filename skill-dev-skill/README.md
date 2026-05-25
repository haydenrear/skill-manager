# skill-dev-skill

`skill-dev-skill` installs the `skill-dev` CLI for iterating on installed
skill-manager skills and plugins from project-local git worktrees.

Install it with:

```bash
skill-manager install github:haydenrear/skill-dev-skill
```

Use it from a git-backed project:

```bash
skill-dev open reviewer-skill
skill-dev status reviewer-skill
skill-dev git reviewer-skill -- diff
skill-dev sync reviewer-skill
skill-dev close reviewer-skill --merge
```

