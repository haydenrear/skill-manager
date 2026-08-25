#!/usr/bin/env bash
# Populate the eval config dir's skills/ with exactly the units the tier under
# test projects. This IS the first rung of progressive disclosure: a real agent
# gets the home's units as loaded skills, and nothing else.
set -u
CFG="$1"; TIER="$2"
rm -rf "$CFG/skills"; mkdir -p "$CFG/skills"
n=0
for u in "$TIER"/skills/*/; do
  [ -d "$u" ] || continue; ln -s "$u" "$CFG/skills/$(basename "$u")"; n=$((n+1))
done
for p in "$TIER"/plugins/*/skills/*/; do
  [ -d "$p" ] || continue; ln -s "$p" "$CFG/skills/$(basename "$p")" 2>/dev/null && n=$((n+1))
done
echo "projected $n skill(s) from $(basename "$TIER")"
