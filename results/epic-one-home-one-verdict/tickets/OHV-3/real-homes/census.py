#!/usr/bin/env python3
"""OHV-3 real-home census, release_regression.py R1/R3-style, driven by a RAW build.

usage: census.py <home> <label> <outdir> [--prune-dry-run] [--prune]

Read-only except with --prune. Uses the worktree's ./skill-manager with
SKILL_MANAGER_HOME pinned (a home's bin/cli pin would override the home).
"""
import json, os, subprocess, sys
from pathlib import Path

BUILD = "/Users/hayde/IdeaProjects/wt-ohv-3/skill-manager"


def sm(home, *args):
    env = dict(os.environ, SKILL_MANAGER_HOME=str(home))
    p = subprocess.run([BUILD, *args], capture_output=True, text=True, env=env, timeout=1200)
    return p.returncode, p.stdout, p.stderr


def doc_of(out):
    return json.loads(out[out.index("{"):])


def census(home):
    rc, out, err = sm(home, "artifacts", "list", "--json")
    if rc != 0:
        return {"measured": False, "rc": rc, "err": err[-2000:]}
    doc = doc_of(out)
    arts = doc.get("artifacts", [])
    phantom, reported_missing = [], []
    for a in arts:
        for o in a.get("outputs", []):
            if o.get("scope") != "home":
                continue
            p = Path(home) / o.get("path", "")
            # independent stat, not the census's own presence
            if not os.path.lexists(p):
                phantom.append(a.get("id"))
            if o.get("presence") == "missing":
                reported_missing.append(a.get("id"))
    ledger_only = [a.get("id") for a in arts if a.get("origin") == "ledger" and not a.get("outputs")]
    by_origin = {}
    for a in arts:
        by_origin[a.get("origin")] = by_origin.get(a.get("origin"), 0) + 1
    return {"measured": True, "declared": len(arts), "by_origin": by_origin,
            "phantom_outputs": sorted(set(phantom)), "phantom_count": len(set(phantom)),
            "reported_missing_count": len(set(reported_missing)),
            "ledger_only_rows": ledger_only, "ledger_only_count": len(ledger_only)}


def versions(home):
    home = Path(home)
    bad = []
    for rec in sorted((home / "installed").glob("*.json")):
        if rec.name.endswith(".projections.json"):
            continue
        try:
            d = json.loads(rec.read_text())
        except Exception:
            continue
        name, h, v = d.get("name", rec.stem), d.get("gitHash") or "", d.get("version")
        store = next((home / k / name for k in ("skills", "plugins", "docs", "harnesses")
                      if (home / k / name).is_dir()), None)
        if not h or store is None or not (store / ".git").exists():
            continue
        head = subprocess.run(["git", "-C", str(store), "rev-parse", "HEAD"],
                              capture_output=True, text=True).stdout.strip()
        if head != h:
            continue
        mv = None
        pj = store / ".claude-plugin" / "plugin.json"
        tm = store / "skill-manager.toml"
        if pj.is_file():
            try:
                mv = json.loads(pj.read_text()).get("version")
            except Exception:
                pass
        elif tm.is_file():
            import re
            m = re.search(r'^\s*version\s*=\s*"([^"]+)"', tm.read_text(), re.M)
            mv = m.group(1) if m else None
        if mv and mv != v:
            bad.append({"unit": name, "record": v, "manifest": mv})
    return {"version_disagreements": bad, "count": len(bad)}


def main():
    home, label, outdir = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    outdir.mkdir(parents=True, exist_ok=True)
    res = {"home": home, "label": label, "census": census(home), "versions": versions(home)}
    if "--prune-dry-run" in sys.argv:
        rc, out, err = sm(home, "artifacts", "prune", "--dry-run", "--json")
        try:
            d = doc_of(out)
            res["prune_dry_run"] = {"rc": rc, "would_prune": [r["id"] for r in d.get("pruned", [])],
                                    "refused": [(r["id"], r["reason"]) for r in d.get("kept", [])
                                                if r.get("verdict") == "refused"]}
        except Exception as e:
            res["prune_dry_run"] = {"rc": rc, "error": str(e), "err": err[-1500:]}
    if "--prune" in sys.argv:
        rc, out, err = sm(home, "artifacts", "prune", "--json")
        try:
            d = doc_of(out)
            res["prune"] = {"rc": rc, "pruned": [(r["id"], r["paths"]) for r in d.get("pruned", [])],
                            "refused": [(r["id"], r["reason"]) for r in d.get("kept", [])
                                        if r.get("verdict") == "refused"]}
        except Exception as e:
            res["prune"] = {"rc": rc, "error": str(e), "err": err[-1500:]}
    (outdir / f"{label}.json").write_text(json.dumps(res, indent=2))
    c = res["census"]
    print(json.dumps({"label": label, "declared": c.get("declared"), "phantom": c.get("phantom_count"),
                      "ledger_only": c.get("ledger_only_count"), "versions": res["versions"]["count"],
                      "dry": {k: len(v) for k, v in res.get("prune_dry_run", {}).items() if isinstance(v, list)},
                      "prune": {k: len(v) for k, v in res.get("prune", {}).items() if isinstance(v, list)}}))


if __name__ == "__main__":
    main()
