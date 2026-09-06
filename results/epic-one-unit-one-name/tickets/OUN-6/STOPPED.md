# OUN-6 stops — the coordinate half of the addressing is unfixed

**The ticket's own constraint told me to.**

> Not one importing file may be rewritten to make this pass. If an import needs
> editing, the addressing is wrong and OUN-1 is incomplete; that is the signal,
> and it must fail rather than be papered over.

An import needs editing. So: the signal, reported.

## What was built, and what it did

The move itself is done in a scratch clone of the `skt` repository —
`skill-manager-skill`'s `SKILL.md`, `skill-manager.toml`, `references/` and
`scripts/` copied to `skt/skills/skill-manager/`, committed, nothing pushed.
The only edit inside the moved unit was its own stale self-reference,
`<skill-manager-skill>/scripts/env.sh` → `<skill-manager>/scripts/env.sh`, in 7
places. Repository names were deliberately left alone; the rename is OUN-7.

Then a clone of the project home was synced against that modified plugin:

```
retired skill-manager — the skill-manager skill now ships inside the skt
                        plugin; the name still resolves, to the copy skt carries
✓ migration: retired skill-manager
→ cloning https://github.com/haydenrear/skill-manager-skill
✓ resolve: 1 unit(s)
✓ installed skill-manager
```

**The migration retires the standalone and the same operation puts it back.**
Exit 0. Both lines are true, they are four lines apart, and nothing reports the
contradiction.

## Why

`git-epic-workflow/skill-manager.toml` line 24:

```toml
skill_references = [
	"github:haydenrear/skill-manager-skill",
]
```

That is the **coordinate** mechanism, and it names a repository. OUN-1 made a
contained skill addressable **by name**. A contained skill carries no
coordinate at all — its `skill-manager.toml` has `name`, `version`,
`description` and nothing that says where it came from.

So after the retirement the coordinate is unmet, `BuildResolveGraphFromUnmetReferences`
resolves it the only way it can — by cloning the repository the coordinate names —
and the standalone comes back. The join that would have prevented this
(coordinate → installed name) lives in `installed/<name>.json`'s `origin`, which
the retirement had just deleted.

**This is the epic's own thesis biting.** OUN-0 baselined two forward-edge
mechanisms and said the join between them exists in exactly one place. OUN-1
fixed the by-name half. Nothing fixed the by-coordinate half, and OUN-6 is the
first ticket whose success depends on it.

## The one-line fix that is forbidden

Repointing that coordinate at `skt`'s repository makes OUN-6 pass today and
leaves the mechanism broken for the next unit that does this. The ticket
forbids it by name, and it is right to: the importer set is the metric, and
editing an importer to move the metric is the definition of papering over.

## What it would actually take

**A contained skill needs to carry its upstream coordinate**, and a coordinate
reference needs to resolve against it. Then:

- the retirement is safe, because the contained copy satisfies the coordinate;
- `deps --who-imports` can join both mechanisms without reading `origin` from a
  record that a retirement may have deleted;
- and — the part worth noticing — **it is the same field the multi-plugin
  vendoring design needs.** That design (two plugins carrying one nested skill,
  with change propagation between the copies) is blocked on exactly the same
  missing datum: which upstream repository is this copy of.

One field, three payoffs. That is a strong argument for doing it properly
rather than working around it here.

## Recommendation

An amendment ticket in this epic — contained skills carry an origin, coordinate
references resolve against it — ordered before OUN-6, which then proceeds
unchanged. It is small in surface (a manifest field, a resolver branch, the
gate's origin comparison) and it unblocks the vendoring epic as a side effect.

The alternative is to defer OUN-6 and OUN-7 to that epic entirely, and finish
this one at OUN-8 with the migration mechanism proved and the move not yet made.
Both are defensible; the first keeps this epic's goal honest, the second is
faster.

## State

Nothing pushed. The `skt` commit is local to a scratch clone
(`bd87b25`), the two unit repositories are untouched, and the home used for the
measurement is a throwaway clone.
