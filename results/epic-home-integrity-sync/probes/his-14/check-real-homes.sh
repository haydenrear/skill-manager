#!/bin/bash
# Did anything in this session touch the operator's real agent homes?
#
# HASHES ONLY. An earlier version of this script COPIED the four config files
# into the probe directory, and those copies were committed to a PUBLIC
# repository — no credentials, but the operator's machine layout: JAVA_HOME,
# every project path with its trust level, MCP endpoints, hook command paths.
# Not catastrophic and not ours to publish. Caught in review of #234.
#
# A hash is strictly better evidence anyway, because same-or-different is the
# only thing the assertion ever claimed. The same applies to the agent skills/
# directories: what is hashed is the sorted list of entry names and symlink
# targets, which proves "no link appeared, none was repointed, none vanished"
# without publishing which skills the operator has installed.
#
#   ./check-real-homes.sh            print the digests
#   ./check-real-homes.sh <baseline> compare against a saved digest file,
#                                    exit 0 when identical, 1 when not
set -u
H="${HOME}"

digests() {
  for f in "$H/.claude/settings.json" \
           "$H/.claude/plugins/known_marketplaces.json" \
           "$H/.codex/config.toml" \
           "$H/.gemini/settings.json" \
           "$H/.claude.json"; do
    if [ -f "$f" ]; then
      printf '%s  %s\n' "$(shasum -a 256 "$f" | cut -d' ' -f1)" "${f/#$H/~}"
    else
      printf '%-64s  %s\n' ABSENT "${f/#$H/~}"
    fi
  done
  for d in "$H/.claude/skills" "$H/.codex/skills" "$H/.gemini/skills"; do
    if [ -d "$d" ]; then
      # name + symlink target, sorted. Not sizes or timestamps: a projection
      # appearing, being repointed, or vanishing is what this has to see, and
      # a mtime would make an unrelated touch read as damage.
      printf '%s  %s (names+targets)\n' \
        "$(ls -la "$d" | tail -n +2 \
            | awk '{ $1=$2=$3=$4=$5=$6=$7=$8=""; sub(/^ +/,""); print }' \
            | grep -vE '^(\.|\.\.)$' | sort | shasum -a 256 | cut -d' ' -f1)" \
        "${d/#$H/~}"
    else
      printf '%-64s  %s\n' ABSENT "${d/#$H/~}"
    fi
  done
}

if [ $# -eq 0 ]; then digests; exit 0; fi

if diff -u "$1" <(digests); then
  echo "UNCHANGED — every digest matches $1"
  exit 0
fi
echo "CHANGED — see the diff above"
exit 1
