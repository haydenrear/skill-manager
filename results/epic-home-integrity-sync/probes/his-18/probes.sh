#!/usr/bin/env bash
# Re-run every HIS-18 vacuity probe and capture the transcript WITH THE
# SUBSTITUTION, so the record is reproducible from the record.
set -uo pipefail
cd /Users/hayde/IdeaProjects/wt-239-his-18
OUT="$1"
: > "$OUT"

probe() {
  local id="$1" file="$2" from="$3" to="$4" what="$5"
  local n
  n=$(grep -cF -- "$from" "$file")
  {
    echo "=== $id  $what"
    echo "    file:  $file"
    echo "    from:  $from"
    echo "    to:    $to"
    echo "    match: $n occurrence(s)  (aborts unless exactly 1)"
  } >> "$OUT"
  if [ "$n" -ne 1 ]; then echo "    ABORTED: bad match count" >> "$OUT"; return 1; fi
  python3 - "$file" "$from" "$to" <<'PY'
import sys
p,f,t=sys.argv[1],sys.argv[2],sys.argv[3]
s=open(p).read(); assert s.count(f)==1
open(p,'w').write(s.replace(f,t))
PY
  jbang RunHis18.java 2>&1 | grep -E '^\s+\[FAIL\]|→' >> "$OUT"
  echo >> "$OUT"
  git checkout -- "$file"
}

CHM=src/main/java/dev/skillmanager/bindings/ChildHomeMaterializer.java
GIR=src/main/java/dev/skillmanager/shared/util/GitIgnoreRules.java
RED=src/main/java/dev/skillmanager/shared/util/Rederivable.java

probe V1 "$CHM" \
 "return Rederivable.isDerived(rel) || rules.ignores(rel, directory);" \
 "return Rederivable.isDerived(rel);" \
 "the pre-HIS-18 world -- the unit's declaration is never consulted"

probe V2 "$GIR" \
 "if (trackedAtOrUnder(rel)) return false;" \
 "if (false) return false;" \
 "the tracked clause dropped -- an ignore rule may hide committed content"

probe V3 "$CHM" \
 "if (isUnowned(rules, childRel, asMaterializedDirectory(child))) {" \
 "if (isUnowned(childRel)) {" \
 "the delivered sibling ALONE -- carry-over back on Rederivable only"

probe V4 "$RED" \
 '"build", "target", "node_modules", ".venv", "venv");' \
 '"build", "target", "node_modules", ".venv", "venv", "sdk", "standard-nodes", "build-logic");' \
 "the rejected design -- a GLOBAL NAME LIST instead of the declaration"

probe V5 "$GIR" \
 "if (!tracked.contains(ancestor) && Boolean.TRUE.equals(decide(ancestor, true))) {" \
 "if (Boolean.TRUE.equals(decide(ancestor, true))) {" \
 "the tracked-ANCESTOR clause dropped -- the bug sync-settles caught"

probe V6 "$CHM" \
 "if (holdsUndeclaredWork(name, kind, dest)) return true;" \
 "if (false) return true;" \
 "REVIEW BLOCKER 1 -- the teardown guard removed"

probe V7 "$CHM" \
 "        return Files.isDirectory(path);" \
 "        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);" \
 "REVIEW BLOCKER 2 -- directory-ness read as git reads it, so the answer moves across the dereference"

probe V8 "$GIR" \
 "        if (!Files.isRegularFile(index)) return null;" \
 "        if (!Files.isRegularFile(index)) return new TreeSet<>();" \
 "REVIEW MED-3 -- a missing index read as 'nothing is tracked'"

git status --short >> "$OUT"
echo "--- restored (nothing above this line means the tree is clean)" >> "$OUT"
