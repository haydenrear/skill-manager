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

`skill-dev sync <unit>` delegates to `skill-manager sync <unit> --from
skill-dev/<unit> --merge --yes`. If you need to replay an unchanged
`skill-script:` installer from the worktree, run the underlying
skill-manager command directly with `--force-scripts`.

The `skill-dev` CLI itself is installed by a `skill-script:` dependency;
uninstall removes its managed binary and lock row only if no other unit
still claims the same tool.
