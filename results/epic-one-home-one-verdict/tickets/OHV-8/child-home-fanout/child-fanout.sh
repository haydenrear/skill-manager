#!/bin/bash
# OHV-8: child-home fan-out. A scratch project home with two child homes (project resolve),
# each child resolving its own copy, and a child install that leaves the parent (and the
# sibling) byte-identical. Scratch only; real homes under a tripwire.
set -u
F=/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/9972f0f0-9e78-40bb-8155-34bfc5133ca5/scratchpad/ohv8/${1:?run dir name}
# Run 1 resolved WITHOUT --skip-gateway against an unreachable gateway and failed "publishing declared
# units, errors=1"; the project-child-home graph resolves with --skip-gateway --json, so run 2 does too.
EPIC=/Users/hayde/IdeaProjects/wt-ohv-8/skill-manager
rm -rf "$F"; mkdir -p "$F/home" "$F/units" "$F/log" "$F/proj/child-a" "$F/proj/child-b"
LOG="$F/log"
REAL_FILES="/Users/hayde/.claude/settings.json /Users/hayde/.claude/plugins/known_marketplaces.json /Users/hayde/.codex/config.toml /Users/hayde/.skill-manager/units.lock.toml /Users/hayde/.skill-manager/artifacts.lock.toml /Users/hayde/.skill-manager/cli-lock.toml"
snap() { { for f in $REAL_FILES; do shasum -a 256 "$f" 2>/dev/null || echo "absent $f"; done; ls -la /Users/hayde/.skill-manager/bin/cli /Users/hayde/.skill-manager/installed /Users/hayde/.skill-manager/child-homes | shasum -a 256; } > "$1"; }
snap "$LOG/tripwire-before.txt"

for u in u-shared u-one u-two u-three u-child-only; do
  mkdir -p "$F/units/$u"
  printf -- '---\nname: %s\ndescription: OHV-8 fan-out fixture\n---\nbody %s rev1\n' "$u" "$u" > "$F/units/$u/SKILL.md"
  printf '[skill]\nname = "%s"\nversion = "0.1.0"\ndescription = "OHV-8 fan-out fixture"\n' "$u" > "$F/units/$u/skill-manager.toml"
done
printf '[project]\nname = "ohv8-child-a"\n\n[skills.one]\nsource = "%s"\n\n[skills.two]\nsource = "%s"\n' "$F/units/u-one" "$F/units/u-two" > "$F/proj/child-a/skill-project.toml"
printf '[project]\nname = "ohv8-child-b"\n\n[skills.one]\nsource = "%s"\n\n[skills.three]\nsource = "%s"\n' "$F/units/u-one" "$F/units/u-three" > "$F/proj/child-b/skill-project.toml"

export HOME="$F/home" JAVA_TOOL_OPTIONS="-Duser.home=$F/home"
export CLAUDE_HOME="$F/home" CLAUDE_CONFIG_DIR="$F/home/.claude" CODEX_HOME="$F/home/.codex" GEMINI_HOME="$F/home/.gemini"
export SKILL_MANAGER_GATEWAY_URL="http://gateway.invalid:1" JBANG_DIR=/Users/hayde/.jbang
unset SKILL_MANAGER_HOME SKILL_MANAGER_INSTALL_DIR
P="$F/proj/.skill-manager"; CA="$F/proj/child-a"; CB="$F/proj/child-b"
n=0
step() { n=$((n+1)); local label="$(printf '%02d' $n)-$1"; shift; ( "$@" ) > "$LOG/$label.out" 2> "$LOG/$label.err"; echo "$label rc=$? cmd: $*" | tee -a "$LOG/steps.txt"; }

step parent-install-shared env SKILL_MANAGER_HOME="$P" "$EPIC" install "$F/units/u-shared" --yes --no-bind-default
step resolve-child-a bash -c "cd '$CA' && SKILL_MANAGER_HOME='$P' '$EPIC' project resolve --skip-gateway --json --project-dir '$CA'"
step resolve-child-b bash -c "cd '$CB' && SKILL_MANAGER_HOME='$P' '$EPIC' project resolve --skip-gateway --json --project-dir '$CB'"
step list-parent env SKILL_MANAGER_HOME="$P" "$EPIC" list
step list-child-a env SKILL_MANAGER_HOME="$CA/.skill-manager" "$EPIC" list
step list-child-b env SKILL_MANAGER_HOME="$CB/.skill-manager" "$EPIC" list
step verify-parent env SKILL_MANAGER_HOME="$P" "$EPIC" home verify --home "$P"
step verify-child-a env SKILL_MANAGER_HOME="$CA/.skill-manager" "$EPIC" home verify --home "$CA/.skill-manager"
step verify-child-b env SKILL_MANAGER_HOME="$CB/.skill-manager" "$EPIC" home verify --home "$CB/.skill-manager"

treehash() { python3.12 - "$1" <<'PY'
import hashlib, os, sys
root = sys.argv[1]
for dp, dns, fns in os.walk(root, followlinks=False):
    dns[:] = sorted(d for d in dns if not os.path.islink(os.path.join(dp, d)))
    for name in sorted(fns + [d for d in os.listdir(dp) if os.path.islink(os.path.join(dp, d)) and os.path.isdir(os.path.join(dp, d))]):
        p = os.path.join(dp, name); rel = os.path.relpath(p, root)
        if rel.startswith("logs/"):
            continue
        if os.path.islink(p):
            print("L", rel, os.readlink(p))
        elif os.path.isfile(p):
            print("F", rel, hashlib.sha256(open(p, "rb").read()).hexdigest())
PY
}
treehash "$P" > "$LOG/parent-before.txt"; treehash "$CB/.skill-manager" > "$LOG/child-b-before.txt"
step child-a-install bash -c "cd '$CA' && SKILL_MANAGER_HOME='$CA/.skill-manager' '$EPIC' install '$F/units/u-child-only' --yes --no-bind-default"
treehash "$P" > "$LOG/parent-after.txt"; treehash "$CB/.skill-manager" > "$LOG/child-b-after.txt"
diff "$LOG/parent-before.txt" "$LOG/parent-after.txt" > "$LOG/parent.diff"; echo "parent byte-identical after child-a install: $([ -s "$LOG/parent.diff" ] && echo NO || echo yes) ($(wc -l < "$LOG/parent-before.txt") entries)" | tee -a "$LOG/steps.txt"
diff "$LOG/child-b-before.txt" "$LOG/child-b-after.txt" > "$LOG/child-b.diff"; echo "sibling child-b byte-identical after child-a install: $([ -s "$LOG/child-b.diff" ] && echo NO || echo yes) ($(wc -l < "$LOG/child-b-before.txt") entries)" | tee -a "$LOG/steps.txt"
step list-child-a-after env SKILL_MANAGER_HOME="$CA/.skill-manager" "$EPIC" list
step verify-child-a-after env SKILL_MANAGER_HOME="$CA/.skill-manager" "$EPIC" home verify --home "$CA/.skill-manager"
step verify-parent-after env SKILL_MANAGER_HOME="$P" "$EPIC" home verify --home "$P"

python3.12 - "$F" > "$LOG/copies.json" <<'PY'
import json, os, sys
F = sys.argv[1]; P = f"{F}/proj/.skill-manager"
out = {}
for c, units in (("child-a", ["u-one", "u-two", "u-child-only"]), ("child-b", ["u-one", "u-three"])):
    h = f"{F}/proj/{c}/.skill-manager"
    rows = {}
    for u in units:
        d = f"{h}/skills/{u}"
        st = os.lstat(d) if os.path.lexists(d) else None
        rows[u] = {"exists": st is not None, "is_symlink": os.path.islink(d),
                   "real_dir": st is not None and not os.path.islink(d) and os.path.isdir(d),
                   "realpath_inside_child": os.path.realpath(d).startswith(os.path.realpath(h) + "/"),
                   "skill_md_inode": os.stat(f"{d}/SKILL.md").st_ino if os.path.exists(f"{d}/SKILL.md") else None}
    out[c] = {"home": h, "units": rows, "child_homes_dir": sorted(os.listdir(h)) if os.path.isdir(h) else None}
out["u-one inodes distinct across children"] = (out["child-a"]["units"]["u-one"]["skill_md_inode"]
                                                 != out["child-b"]["units"]["u-one"]["skill_md_inode"])
out["parent holds u-child-only"] = os.path.lexists(f"{P}/skills/u-child-only")
out["parent child-homes records"] = sorted(os.listdir(f"{P}/child-homes")) if os.path.isdir(f"{P}/child-homes") else None
out["parent skills"] = sorted(os.listdir(f"{P}/skills")) if os.path.isdir(f"{P}/skills") else None
print(json.dumps(out, indent=2))
PY
cat "$LOG/copies.json"
export HOME=/Users/hayde
snap "$LOG/tripwire-after.txt"
if diff "$LOG/tripwire-before.txt" "$LOG/tripwire-after.txt" > "$LOG/tripwire.diff"; then echo "TRIPWIRE: real files unchanged" | tee -a "$LOG/steps.txt"; else echo "TRIPWIRE: CHANGED" | tee -a "$LOG/steps.txt"; fi
