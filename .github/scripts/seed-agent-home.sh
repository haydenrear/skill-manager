#!/usr/bin/env bash
# Seed a minimal, REAL-SHAPED agent home under $HOME for the home-tripwire graph.
#
# Why (#345): `home.tripwire.armed` snapshots the operator's real homes
# (~/.skill-manager, ~/.claude, ~/.codex, ~/.gemini) before the graph runs and
# `home.tripwire.checked` re-reads them after. Its vacuity guard is correct: an
# empty baseline compared with an empty re-read is clean and meaningless, so it
# FAILS when none of the four roots exist -- which is every fresh GitHub runner
# ("none of [...] exist under /home/runner").
#
# A runner has no operator, but it does have a $HOME a leaking skill-manager
# write would land in. This gives the tripwire the shape a real install leaves
# behind, so there is something to trip on:
#   ~/.skill-manager/skills/<unit>/SKILL.md          (CONTENT surface: skills)
#   ~/.skill-manager/installed/<unit>.json           (CONTENT surface: installed)
#   ~/.claude/skills/<unit> -> ~/.skill-manager/...  (agent skillsDir projection)
#   ~/.codex/skills/<unit>, ~/.gemini/skills/<unit>  (same, per agent)
#
# It refuses to run anywhere a home already exists, so it can never be pointed
# at a developer's machine and overwrite anything.
set -euo pipefail

home="${HOME:?HOME is not set}"
unit="tripwire-seed"

for root in .skill-manager .claude .codex .gemini; do
  if [ -e "$home/$root" ]; then
    echo "seed-agent-home: $home/$root already exists; refusing to seed over a real home" >&2
    exit 1
  fi
done

store="$home/.skill-manager"
mkdir -p "$store/installed"
for agent in .claude .codex .gemini; do
  mkdir -p "$home/$agent/skills"
done

# SIXTEEN units, not one, and the number is measured rather than decorative.
# ticket.lifecycle.global.home.untouched and onboarding.global.home.untouched
# both assert the_leak_baseline_watched_a_non_trivial_tree: the METADATA
# baseline must hold MORE THAN 100 entries, because a tiny baseline diffs clean
# forever. Counted with TripwireSupport.collectAll itself: one unit gives 15
# (matching run 34791698649 exactly), ten give 109 -- over the floor by 9, too
# thin -- and sixteen clear it with margin. Each unit is a SKILL.md, a
# references/ page and a scripts/ file, plus an installed record and three
# agent links: the size of a small real root home (the operator's has 18).
for i in 01 02 03 04 05 06 07 08 09 10 11 12 13 14 15 16; do
  u="$unit-$i"
  d="$store/skills/$u"
  mkdir -p "$d/references" "$d/scripts"
  cat > "$d/SKILL.md" <<EOF
---
name: $u
description: Seeded by .github/scripts/seed-agent-home.sh so the real-home leak oracles have a home to watch on a CI runner.
---
Nothing here is used. Any change to this file during a graph run is a leak.
EOF
  printf '# %s reference\n\nSeeded; never read by a graph.\n' "$u" > "$d/references/overview.md"
  printf '#!/usr/bin/env bash\necho %s\n' "$u" > "$d/scripts/run.sh"
  chmod +x "$d/scripts/run.sh"
  cat > "$store/installed/$u.json" <<EOF
{
  "name" : "$u",
  "version" : "0.0.0",
  "kind" : "LOCAL",
  "installSource" : "LOCAL",
  "origin" : "$d",
  "errors" : [ ],
  "unitKind" : "SKILL"
}
EOF
  for agent in .claude .codex .gemini; do
    ln -s "$d" "$home/$agent/skills/$u"
  done
done

# The sibling Claude config the leak oracles fingerprint (TripwireSupport
# .ownedConfig reads mcpServers / extraKnownMarketplaces / projects from it).
# Present and empty, so a registration written into the REAL config by a
# leaking command is a change from a real file rather than from ABSENT.
if [ ! -e "$home/.claude.json" ]; then
  printf '{}\n' > "$home/.claude.json"
fi

echo "seed-agent-home: seeded $unit under $home"
find "$store" "$home/.claude" "$home/.codex" "$home/.gemini" -maxdepth 3 | sed "s|$home|~|"
