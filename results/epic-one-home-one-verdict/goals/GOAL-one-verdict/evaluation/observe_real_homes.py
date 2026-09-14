#!/usr/bin/env python3
"""GOAL-one-verdict clause (2), OHV-8: independent disk observation of root and this
project home, set against the epic build's `home verify` / `home repair --json`.

Adapted from the kickoff's measure_baseline.py (O1, O3, O4, O5, O6, O8). It reads
files only; the verdicts it compares against were captured beside it in
real-homes/<label>.epic.home-{verify.out,repair.json} by the same session.

A fact counts toward clause (2) when the disk shows it damaged AND it is not the
case that `home repair` names it (kind + subject) AND `home verify` exits non-zero
naming the same subject. Only the shapes verify/repair own are counted: shims
(own path, foreign path), agent links (dangling, into another home), projection
records (orphaned), pm stamps, marketplace registrations.

  python3.12 observe_real_homes.py <real-homes dir>   -> writes observation.json there
"""
import json
import os
import re
import sys
from pathlib import Path

HOME = Path.home()
REPO = Path("/Users/hayde/IdeaProjects/skill-manager")
HOMES = [("root", HOME / ".skill-manager", HOME), ("project", REPO / ".skill-manager", REPO)]


def spellings(h: Path):
    s = {str(h), os.path.realpath(h)}
    for x in list(s):
        if x.startswith("/private/"):
            s.add(x[len("/private"):])
        elif x.startswith(("/var/", "/tmp/")):
            s.add("/private" + x)
    return sorted(s, key=len, reverse=True)


def text(p: Path):
    try:
        if p.stat().st_size > 2_000_000:
            return None
        b = p.read_bytes()
    except OSError:
        return None
    return None if b"\0" in b[:4096] else b.decode("utf-8", "replace")


def shims(home: Path):
    """O1 own-path on running lines; O8 absolute paths into ANOTHER .skill-manager home."""
    sp = spellings(home)
    facts = []
    pat = re.compile(r"(/[^\s\"'`:$]*?/\.skill-manager)(?=/|\b)")
    for sub in ("cli", "mcp"):
        d = home / "bin" / sub
        if not d.is_dir():
            continue
        for p in sorted(d.iterdir()):
            rel = f"bin/{sub}/{p.name}"
            if p.is_symlink():
                tgt = os.readlink(p)
                absn = tgt if tgt.startswith("/") else os.path.normpath(p.parent / tgt)
                real = os.path.realpath(p)
                if ".skill-manager" in absn and not any(absn.startswith(s) or real.startswith(s) for s in sp):
                    facts.append({"shape": "shim-link-into-another-home", "subject": rel, "evidence": tgt})
                continue
            t = text(p) if p.is_file() else None
            if t is None:
                continue
            lines = t.split("\n")
            body = lines[1:] if lines and lines[0].startswith("#!") else lines
            running = [l for l in body if not l.lstrip().startswith("#")]
            if any(s in l for l in running for s in sp):
                facts.append({"shape": "shim-spells-own-home", "subject": rel})
            foreign = sorted({m for l in running for m in pat.findall(l)
                              if m not in sp and os.path.realpath(m) not in sp})
            if foreign:
                facts.append({"shape": "shim-runs-another-home", "subject": rel, "evidence": foreign})
    return facts


def agent_links(home: Path, checkout: Path):
    sp = spellings(home)
    facts, total = [], 0
    for ad in (".claude", ".codex", ".gemini"):
        for sub in ("skills", "commands", "agents"):
            d = checkout / ad / sub
            if not d.is_dir():
                continue
            for p in d.iterdir():
                if not p.is_symlink():
                    continue
                total += 1
                tgt = os.readlink(p)
                absn = tgt if tgt.startswith("/") else os.path.normpath(p.parent / tgt)
                rel = str(p.relative_to(checkout))
                if not os.path.exists(p):
                    facts.append({"shape": "agent-link-dangling", "subject": rel, "evidence": tgt})
                elif ".skill-manager" in absn and not any(
                        absn.startswith(s) or os.path.realpath(absn).startswith(s) for s in sp):
                    facts.append({"shape": "agent-link-into-another-home", "subject": rel, "evidence": tgt})
    return facts, total


def orphan_records(home: Path):
    inst = home / "installed"
    return [{"shape": "orphaned-projection-record", "subject": f"installed/{p.name}"}
            for p in sorted(inst.glob("*.projections.json"))
            if not (inst / (p.name[: -len(".projections.json")] + ".json")).exists()]


def pm_unstamped(home: Path):
    out = []
    pm = home / "pm"
    if pm.is_dir():
        for tool in sorted(pm.iterdir()):
            if tool.is_dir():
                for ver in sorted(tool.iterdir()):
                    if ver.is_dir() and not (ver / ".platform").is_file():
                        out.append({"shape": "pm-unstamped", "subject": f"pm/{tool.name}/{ver.name}"})
    return out


def marketplace(home: Path, checkout: Path):
    """Registrations in THIS home's own agent files (the root's are ~/.claude, ~/.codex)."""
    facts = []
    try:
        name = json.loads((home / "plugin-marketplace/.claude-plugin/marketplace.json").read_text()).get("name")
    except (OSError, json.JSONDecodeError):
        name = None
    mp = {os.path.realpath(os.path.join(s, "plugin-marketplace")) for s in spellings(home)}
    reg = {}
    try:
        for k, v in json.loads((checkout / ".claude/plugins/known_marketplaces.json").read_text()).items():
            reg[k] = ((v or {}).get("source") or {}).get("path")
    except (OSError, json.JSONDecodeError, AttributeError):
        pass
    try:
        st = json.loads((checkout / ".claude/settings.json").read_text())
    except (OSError, json.JSONDecodeError):
        st = {}
    for k, v in (st.get("extraKnownMarketplaces") or {}).items():
        reg.setdefault(k, ((v or {}).get("source") or {}).get("path"))
    for k, p in reg.items():
        if p and os.path.realpath(p) in mp and k != name:
            facts.append({"shape": "marketplace-1-this-path-under-another-name", "subject": k})
    for pl in (st.get("enabledPlugins") or {}):
        if "@" not in pl:
            continue
        m = pl.split("@", 1)[1]
        if not m.startswith("skill-manager") or m == name:
            continue
        p = reg.get(m)
        if p is None or os.path.realpath(p) not in mp:
            facts.append({"shape": "marketplace-4-claude-enables-another-homes", "subject": pl, "evidence": p})
    cfg = checkout / ".codex/config.toml"
    if cfg.is_file():
        t = cfg.read_text(errors="replace")
        for mm in re.finditer(r'^\[marketplaces\.("?)([^\]"]+)\1\]\s*\n((?:(?!^\[).*\n?)*)', t, re.M):
            src = re.search(r'^\s*source\s*=\s*"([^"]+)"', mm.group(3), re.M)
            if mm.group(2).startswith("skill-manager") and src and os.path.realpath(src.group(1)) not in mp:
                facts.append({"shape": "marketplace-4-codex-registers-another-homes",
                              "subject": mm.group(2), "evidence": src.group(1)})
    return facts, name


def main():
    d = Path(sys.argv[1])
    out = {"homes": {}}
    for label, home, checkout in HOMES:
        facts = shims(home)
        links, nlinks = agent_links(home, checkout)
        facts += links + orphan_records(home) + pm_unstamped(home)
        mfacts, gen = marketplace(home, checkout)
        facts += mfacts
        rj = (d / f"{label}.epic.home-repair.json").read_text()
        repair = json.loads(rj[rj.index("{"):])
        vtext = (d / f"{label}.epic.home-verify.out").read_text() + (d / f"{label}.epic.home-verify.err").read_text()
        vrc = int((d / f"{label}.epic.home-verify.rc").read_text().split("rc=")[1])
        rrc = int((d / f"{label}.epic.home-repair.rc").read_text().split("rc=")[1])
        subjects = [(f.get("kind"), f.get("subject") or "") for f in repair.get("findings", [])]
        unreported = []
        for f in facts:
            s = f["subject"]
            by_repair = [k for k, subj in subjects if subj == s or subj.endswith("/" + s) or s in subj]
            f["repair_kinds"] = sorted(set(by_repair))
            f["verify_names_it"] = s in vtext
            f["reported_by_both"] = bool(by_repair) and rrc != 0 and vrc != 0 and f["verify_names_it"]
            if not f["reported_by_both"]:
                unreported.append(f)
        matched = {(k, subj) for k, subj in subjects
                   if any(f["subject"] == subj or subj.endswith("/" + f["subject"]) or f["subject"] in subj
                          for f in facts)}
        out["homes"][label] = {
            "home": str(home), "checkout": str(checkout), "generated_marketplace": gen,
            "agent_links_examined": nlinks,
            "verify_rc": vrc, "repair_rc": rrc, "repair_build": repair.get("build"),
            "repair_findings": len(subjects),
            "disk_facts_damaged": facts,
            "clause2_facts_damaged_while_not_reported_by_both": len(unreported),
            "repair_findings_no_observation_matched": sorted(set(subjects) - matched),
        }
    (d / "observation.json").write_text(json.dumps(out, indent=2))
    for label, h in out["homes"].items():
        print(label, "facts", len(h["disk_facts_damaged"]), "unreported-by-both",
              h["clause2_facts_damaged_while_not_reported_by_both"], "verify", h["verify_rc"], "repair", h["repair_rc"],
              "findings", h["repair_findings"], "unmatched findings", h["repair_findings_no_observation_matched"])
        for f in h["disk_facts_damaged"]:
            print("   ", f["shape"], f["subject"], f["repair_kinds"], "verify-names", f["verify_names_it"])


if __name__ == "__main__":
    main()
