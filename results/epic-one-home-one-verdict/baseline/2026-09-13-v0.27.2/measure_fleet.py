#!/usr/bin/env python3
"""one-home-one-verdict fleet baseline: every home on this machine, read-only.

For each home (root + every <dir>/.skill-manager under ~/IdeaProjects, depth <= 2):
  - verdicts: `home verify` and `home repair --json` with the RELEASED CLI (rc only + finding count)
  - G3: bin/cli entries spelling the home's own absolute path
  - G4 / #352: the four marketplace-identity shapes
      1 Claude registers this home's marketplace path under a different name
      2 generated name is a substring of another registered skill-manager name
      3 generated name equal to another home's generated name (copied identity)
      4 Codex/Claude config enabling or registering another home's marketplace
  - #339: dangling agent links in the checkout's agent dirs, orphaned projection records
No --fix, no sync, no --record. Usage: measure_fleet.py <out.json>
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

HOME = Path.home()
RELEASED = "/opt/homebrew/bin/skill-manager"


def discover():
    homes = [(HOME / ".skill-manager", HOME)]
    base = HOME / "IdeaProjects"
    for d in sorted(base.iterdir()):
        for cand in (d / ".skill-manager",):
            if (cand / "installed").is_dir():
                homes.append((cand, d))
        if d.is_dir():
            try:
                for e in d.iterdir():
                    if e.is_dir() and (e / ".skill-manager" / "installed").is_dir():
                        homes.append((e / ".skill-manager", e))
            except OSError:
                pass
    # The epic's own worktree is created while this runs; it is not part of the
    # population being baselined.
    seen, out = set(), []
    for h, c in homes:
        r = os.path.realpath(h)
        if "wt-epic-one-home-one-verdict" in r:
            continue
        if r not in seen:
            seen.add(r)
            out.append((h, c))
    return out


def spellings(h):
    s = {str(h), os.path.realpath(h)}
    return sorted(s, key=len, reverse=True)


def run(argv, env_home):
    env = dict(os.environ, SKILL_MANAGER_HOME=str(env_home))
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=600, env=env)
        return p.returncode, p.stdout + p.stderr
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 127, str(exc)


def gen_name(h):
    try:
        return json.loads((h / "plugin-marketplace/.claude-plugin/marketplace.json").read_text()).get("name")
    except (OSError, json.JSONDecodeError):
        return None


def codex_marketplaces(path):
    if not path.is_file():
        return {}, []
    t = path.read_text(errors="replace")
    res = {}
    for m in re.finditer(r'^\[marketplaces\.("?)([^\]"]+)\1\]\s*\n((?:(?!^\[).*\n?)*)', t, re.M):
        src = re.search(r'^\s*source\s*=\s*"([^"]+)"', m.group(3), re.M)
        res[m.group(2)] = src.group(1) if src else None
    return res, re.findall(r'^\[plugins\."([^"]+)"\]', t, re.M)


def main():
    out_path = Path(sys.argv[1])
    homes = discover()
    try:
        km = json.loads((HOME / ".claude/plugins/known_marketplaces.json").read_text())
    except (OSError, json.JSONDecodeError):
        km = {}
    km_sm = {k: (v.get("source") or {}).get("path") for k, v in km.items() if k.startswith("skill-manager")}
    names = {str(h): gen_name(h) for h, _ in homes}
    rows = []
    for h, checkout in homes:
        sp = spellings(h)
        mp_paths = {os.path.join(s, "plugin-marketplace") for s in sp}
        name = names[str(h)]
        row = {"home": str(h), "checkout": str(checkout), "generated_name": name}

        rc, o = run([RELEASED, "home", "verify", "--home", str(h)], h)
        row["verify_rc"] = rc
        rc, o = run([RELEASED, "home", "repair", "--home", str(h), "--json"], h)
        try:
            d = json.loads(o[o.index("{"):])
            row["repair_rc"], row["repair_findings"] = rc, len(d.get("findings", []))
        except (ValueError, json.JSONDecodeError):
            row["repair_rc"], row["repair_findings"] = rc, None

        own = []
        cli = h / "bin" / "cli"
        if cli.is_dir():
            for p in cli.iterdir():
                if p.is_file() and not p.is_symlink():
                    try:
                        t = p.read_text(errors="replace")
                    except OSError:
                        continue
                    if any(s in t for s in sp):
                        own.append(p.name)
        row["G3_bin_cli_own_absolute"] = sorted(own)

        s1 = sorted(k for k, v in km_sm.items() if v in mp_paths and k != name)
        s2 = bool(name) and any(k != name and name in k for k in km_sm)
        s3 = sorted(o for o, n in names.items() if o != str(h) and n and n == name)
        foreign = []
        codex_cfg = (HOME / ".codex/config.toml") if checkout == HOME else checkout / ".codex/config.toml"
        mks, plugins = codex_marketplaces(codex_cfg)
        for mn, src in mks.items():
            if mn.startswith("skill-manager") and src and os.path.realpath(src) not in {os.path.realpath(x) for x in mp_paths}:
                foreign.append(f"codex marketplace {mn} -> {src}")
        for pl in plugins:
            if "@skill-manager" in pl and pl.split("@", 1)[1] != name:
                foreign.append(f"codex plugin {pl}")
        settings = (HOME / ".claude/settings.json") if checkout == HOME else checkout / ".claude/settings.json"
        try:
            en = json.loads(settings.read_text()).get("enabledPlugins") or {}
        except (OSError, json.JSONDecodeError):
            en = {}
        for pl in en:
            if "@skill-manager" in pl and pl.split("@", 1)[1] != name:
                foreign.append(f"claude enabled {pl}")
        row["G4"] = {"shape1_claude_other_name_at_path": s1, "shape2_substring_exposed": s2,
                     "shape3_same_name_as": s3, "shape4_other_or_stale_registration": foreign}

        dangling = []
        for ad in (".claude", ".codex", ".gemini"):
            d = checkout / ad / "skills"
            if d.is_dir():
                for p in d.iterdir():
                    if p.is_symlink() and not os.path.exists(p):
                        dangling.append(str(p.relative_to(checkout)))
        inst = h / "installed"
        orphans = sorted(p.name for p in inst.glob("*.projections.json")
                         if not (inst / (p.name[: -len(".projections.json")] + ".json")).exists())
        row["G1_dangling_agent_links"] = dangling
        row["G1_orphan_projection_records"] = orphans
        rows.append(row)
        print(f"{h}: verify={row['verify_rc']} repair={row['repair_rc']}/{row['repair_findings']} own={len(own)} "
              f"s1={len(s1)} s3={len(s3)} s4={len(foreign)} dangling={len(dangling)} orphans={len(orphans)}", flush=True)

    def count(f):
        return sum(1 for r in rows if f(r))
    summary = {
        "homes": len(rows),
        "verify_rc0": count(lambda r: r["verify_rc"] == 0),
        "repair_clean": count(lambda r: r["repair_rc"] == 0 and r["repair_findings"] == 0),
        "G3_homes_with_own_absolute_bin_cli": count(lambda r: r["G3_bin_cli_own_absolute"]),
        "G4_shape1": count(lambda r: r["G4"]["shape1_claude_other_name_at_path"]),
        "G4_shape2_exposed": count(lambda r: r["G4"]["shape2_substring_exposed"]),
        "G4_shape3": count(lambda r: r["G4"]["shape3_same_name_as"]),
        "G4_shape4": count(lambda r: r["G4"]["shape4_other_or_stale_registration"]),
        "G4_any_shape_1_3_4": count(lambda r: r["G4"]["shape1_claude_other_name_at_path"] or r["G4"]["shape3_same_name_as"] or r["G4"]["shape4_other_or_stale_registration"]),
        "G1_homes_with_dangling_agent_links": count(lambda r: r["G1_dangling_agent_links"]),
        "G1_homes_with_orphan_projection_records": count(lambda r: r["G1_orphan_projection_records"]),
        "damaged_but_repair_clean": count(lambda r: r["repair_rc"] == 0 and r["repair_findings"] == 0 and (
            r["G3_bin_cli_own_absolute"] or r["G4"]["shape1_claude_other_name_at_path"] or r["G4"]["shape3_same_name_as"]
            or r["G4"]["shape4_other_or_stale_registration"] or r["G1_dangling_agent_links"] or r["G1_orphan_projection_records"])),
    }
    out_path.write_text(json.dumps({"summary": summary, "homes": rows}, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
