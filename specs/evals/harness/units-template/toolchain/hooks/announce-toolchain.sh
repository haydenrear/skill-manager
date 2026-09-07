#!/usr/bin/env bash
# Discovers tools AT RUNTIME and reports absolute paths. Nothing hardcoded:
# another machine gets its own paths, or gets told a tool is missing, which is
# a truthful input rather than a silent zero.
#
# Written because a run without it spent 9 of its 14 Bash calls hunting for
# git — measuring the search, not the skill.
# EXISTS IS NOT RUNS. The first version of this hook reported
# `git = /usr/bin/git` because `command -v` found it -- and /usr/bin/git on
# macOS is an xcrun shim that FAILS inside the sandbox. The agent then spent
# six calls rediscovering that, which is the same nine-call search this hook
# was written to prevent, one layer down.
#
# So every candidate is EXECUTED, and only a path that answers is reported.
# "What would look identical if I were wrong?" -- an existing shim and a
# working binary look identical to `-x`.
# THE HOOK CANNOT VERIFY ON THE AGENT'S BEHALF. Version 2 executed each
# candidate and still reported /usr/bin/git, because the shim RUNS for the hook
# and FAILS in the agent's Bash sandbox -- two different environments, and the
# hook is in the more permissive one. The agent then spent three calls finding
# that out, exactly as before.
#
# So this stops deciding. It reports EVERY candidate that exists, in preference
# order with the real binaries ahead of the macOS xcrun shims, and says the
# list is a fallback chain. The agent needs no search: the second entry is
# already on screen when the first one fails.
candidates() {
  local name="$1" d out=""
  for d in /opt/homebrew/bin /usr/local/bin \
           /Library/Developer/CommandLineTools/usr/bin \
           /Applications/Xcode.app/Contents/Developer/usr/bin \
           "$HOME/.skill-manager/bin/cli" /usr/bin /bin; do
    [ -x "$d/$name" ] && out="$out${out:+, }$d/$name"
  done
  printf '%s' "$out"
}
out="Toolchain discovered for this session (absolute paths, resolved at runtime):"
for t in git gh jbang python3 skt skill-manager; do
  c="$(candidates "$t")"
  if [ -n "$c" ]; then out="$out"$'\n'"  $t: $c"
  else out="$out"$'\n'"  $t: NOT PRESENT on this machine"; fi
done
out="$out"$'\n'"These are fallback chains, in preference order. Use the first;"
out="$out"$'\n'"if it errors, use the next. DO NOT SEARCH FOR THESE TOOLS --"
out="$out"$'\n'"every path that exists on this machine is already listed above."
printf '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":%s}}\n' \
  "$(printf '%s' "$out" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')"
