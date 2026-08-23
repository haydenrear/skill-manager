#!/usr/bin/env bash
# HIS-13 GRAPH vacuity probes.
#
# The unit probes (probes.py) redden the unit suite. These redden the NODE, and
# they are separate because the node asserts one thing the unit suite cannot:
# that detection and repair are separate PROCESSES. A mutation that only the
# unit suite catches would leave the node's own colour unexplained.
#
# Restore is `cp` from the saved copy. Never `git checkout --` (DEF-035).
set -uo pipefail
cd "$(dirname "$0")/../../../.." || exit 1
ROOT=$PWD
SRC=$ROOT/src/main/java/dev/skillmanager/store/HomeRepair.java
OUT=$ROOT/results/epic-home-integrity-sync/probes/his-13
export GRADLE_OPTS="-Dorg.gradle.daemon=false"
export COMPOSE_PROJECT_NAME=skill-manager

probe() {  # $1=id  $2=python-mutation-expr  $3=what branch
  local id=$1 mut=$2 branch=$3
  cp "$SRC" "$SRC.probe-backup"
  python3 - "$SRC" <<PY || { cp "$SRC.probe-backup" "$SRC"; rm -f "$SRC.probe-backup"; return 1; }
import sys, pathlib
p = pathlib.Path(sys.argv[1]); s = p.read_text()
$mut
PY
  python skills/test_graph/scripts/run.py home-integrity > "$OUT/$id.graph.log" 2>&1
  local run
  run=$(ls -t test_graph/build/validation-reports/ | head -1)
  cp "$SRC.probe-backup" "$SRC"; rm -f "$SRC.probe-backup"
  {
    echo "# $id -- branch moved: $branch"
    echo "# runId: $run"
    python3 - "test_graph/build/validation-reports/$run/envelope/home.integrity.damaged.home.is.repairable.json" <<'PY'
import json, sys, pathlib
f = pathlib.Path(sys.argv[1])
if not f.is_file():
    print("(no envelope: the node did not run)"); raise SystemExit
d = json.loads(f.read_text())
print("node status:", d.get("status"))
for a in d.get("assertions", []):
    print(("  RED  " if a.get("status") != "passed" else "  pass "), a.get("name"))
print("message:", str(d.get("message"))[:400])
print("metrics:", d.get("metrics"))
PY
  } > "$OUT/$id.out"
  echo "$id done -> $OUT/$id.out"
}

probe G1 's = s.replace("""                    apply(root, finding);""", """                    if (false) apply(root, finding);""", 1); assert s.count("if (false) apply") == 1; p.write_text(s)' \
      "the repair itself -- every action suppressed, detection unchanged"

probe G2 's = s.replace("""public final class HomeRepair {""", """public final class HomeRepair {\n\n    private static final ThreadLocal<Boolean> R = ThreadLocal.withInitial(() -> false);""", 1);
s = s.replace("""    public static Report detect(Path store, Path cliPin) {\n        Path root = store.toAbsolutePath().normalize();""", """    public static Report detect(Path store, Path cliPin) {\n        Path root = store.toAbsolutePath().normalize();\n        try { if (!R.get()) { R.set(true); try { repair(root, cliPin); } finally { R.set(false); } } } catch (Exception ignored) {}""", 1);
assert s.count("if (!R.get())") == 1; p.write_text(s)' \
      "DEF-067's hazard PLANTED at the process boundary: detection repairs."
