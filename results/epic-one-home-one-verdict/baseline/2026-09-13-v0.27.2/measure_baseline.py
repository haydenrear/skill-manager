#!/usr/bin/env python3
"""one-home-one-verdict kickoff baseline: five verdicts vs independent observation.

For each real home, run the five verdict commands with the RELEASED 0.27.2 CLI
(and the home's pinned CLI where it differs), then read the same facts off the
disk directly, without the product's rules. Read-only: no --fix, no --record,
no --ack, no sync.

Output: <out>/baseline.json plus raw command logs under <out>/raw/.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

HOME = Path.home()
REPO = Path("/Users/hayde/IdeaProjects/skill-manager")
RELEASED = "/opt/homebrew/bin/skill-manager"
OUT = Path(sys.argv[1])
RAW = OUT / "raw"
RAW.mkdir(parents=True, exist_ok=True)

HOMES = [
    {"label": "root", "home": HOME / ".skill-manager", "project": None, "cwd": HOME,
     "agent_dirs": [HOME / ".claude", HOME / ".codex", HOME / ".gemini"],
     "claude_settings": HOME / ".claude" / "settings.json", "codex": HOME / ".codex" / "config.toml"},
    {"label": "project-skill-manager", "home": REPO / ".skill-manager", "project": REPO, "cwd": REPO,
     "agent_dirs": [REPO / ".claude", REPO / ".codex", REPO / ".gemini"],
     "claude_settings": REPO / ".claude" / "settings.json", "codex": REPO / ".codex" / "config.toml"},
]

# Authored or runtime content: not "generated content" for the own-path scan.
SKIP_TOP = {"skills", "plugins", "docs", "harnesses", "cache", "logs", "tmp", "gateway-data",
            "npm", "tools", "audit.log", "gateway.log", "auth.token"}


def run(label, argv, env_home=None, cwd=None, timeout=900):
    env = dict(os.environ)
    if env_home:
        env["SKILL_MANAGER_HOME"] = str(env_home)
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, env=env, cwd=cwd)
        rc, out, err = p.returncode, p.stdout or "", p.stderr or ""
    except (OSError, subprocess.TimeoutExpired) as exc:
        rc, out, err = 127, "", str(exc)
    (RAW / f"{label}.log").write_text(f"$ {' '.join(map(str, argv))}\n# SKILL_MANAGER_HOME={env_home}\n# rc={rc}\n--- stdout\n{out}\n--- stderr\n{err}")
    return rc, out, err


def jload(text):
    try:
        return json.loads(text[text.index("{"):])
    except (ValueError, json.JSONDecodeError):
        return None


def spellings(h: Path):
    s = {str(h), os.path.realpath(h)}
    for x in list(s):
        if x.startswith("/private/"):
            s.add(x[len("/private"):])
        elif x.startswith(("/var/", "/tmp/")):
            s.add("/private" + x)
    return sorted(s, key=len, reverse=True)


def is_text(p: Path):
    try:
        if p.stat().st_size > 2_000_000:
            return None
        b = p.read_bytes()
    except OSError:
        return None
    return None if b"\0" in b[:4096] else b.decode("utf-8", "replace")


# ---------------------------------------------------------------- verdicts
def verdicts(h):
    home, lab = h["home"], h["label"]
    v = {}
    rc, out, err = run(f"{lab}.released.version", [RELEASED, "--version"])
    v["released_build"] = out.strip().splitlines()[:2]
    pinned = home / "bin" / "cli" / "skill-manager"
    rc, out, err = run(f"{lab}.pinned.version", [str(pinned), "--version"])
    v["pinned_build"] = out.strip().splitlines()[:2]

    rc, out, err = run(f"{lab}.released.home-verify", [RELEASED, "home", "verify", "--home", str(home)], home)
    v["home verify"] = {"rc": rc, "last": (out + err).strip().splitlines()[-3:]}
    rc, out, err = run(f"{lab}.released.home-repair", [RELEASED, "home", "repair", "--home", str(home), "--json"], home)
    d = jload(out)
    v["home repair"] = {"rc": rc, "json": d if d is not None and len(json.dumps(d)) < 4000 else None,
                        "finding_count": (len(d.get("findings", [])) if isinstance(d, dict) and "findings" in d else None),
                        "last": (out + err).strip().splitlines()[-3:]}
    rc, out, err = run(f"{lab}.released.home-drift", [RELEASED, "home", "drift", "--home", str(home), "--json"], home)
    v["home drift"] = {"rc": rc, "json": jload(out), "last": (out + err).strip().splitlines()[-3:]}
    rc, out, err = run(f"{lab}.released.artifacts-list", [RELEASED, "artifacts", "list", "--json"], home)
    art = jload(out)
    v["artifacts list"] = {"rc": rc, "summary": (art or {}).get("summary")}
    skt = home / "bin" / "cli" / "skt"
    rc, out, err = run(f"{lab}.skt-check", [str(skt) if skt.exists() else "skt", "check", "--json"], home, cwd=h["cwd"])
    v["skt check"] = {"rc": rc, "json": jload(out), "last": (out + err).strip().splitlines()[-3:]}
    if pinned.exists() and v["pinned_build"] != v["released_build"]:
        rc, out, err = run(f"{lab}.pinned.home-repair", [str(pinned), "home", "repair", "--home", str(home), "--json"], home)
        v["home repair (pinned build)"] = {"rc": rc, "last": (out + err).strip().splitlines()[-3:]}
        rc, out, err = run(f"{lab}.pinned.home-verify", [str(pinned), "home", "verify", "--home", str(home)], home)
        v["home verify (pinned build)"] = {"rc": rc, "last": (out + err).strip().splitlines()[-3:]}
    return v, art


# ------------------------------------------------------------ observations
def obs_own_path(home):
    """O1: absolute own-home spellings in generated (non-authored) content."""
    sp = spellings(home)
    hits, per_top, scanned = [], {}, 0
    for root, dirs, files in os.walk(home):
        rel = Path(root).relative_to(home)
        top = rel.parts[0] if rel.parts else None
        if top in SKIP_TOP:
            dirs[:] = []
            continue
        dirs[:] = [d for d in dirs if not (rel == Path(".") and d in SKIP_TOP) and d not in {".git", "__pycache__", "node_modules"}]
        for f in files:
            p = Path(root) / f
            if not rel.parts and f in SKIP_TOP:
                continue
            if p.is_symlink():
                continue
            t = is_text(p)
            if t is None:
                continue
            scanned += 1
            if any(s in t for s in sp):
                r = str(p.relative_to(home))
                hits.append(r)
                key = r.split("/")[0] if "/" in r else r
                if key == "venvs":
                    key = "venvs (interpreter shebangs, expected absolute)"
                per_top[key] = per_top.get(key, 0) + 1
    return {"files_scanned": scanned, "files_with_own_absolute_path": len(hits),
            "by_top": per_top, "non_venv_examples": [x for x in hits if not x.startswith("venvs/")][:40]}


def obs_other_home_refs(home):
    """O8: bin/cli entries naming ANOTHER home's .skill-manager."""
    sp = spellings(home)
    bad = []
    pat = re.compile(r"(/[^\s\"'`:]*?/\.skill-manager)(?=/|\b)")
    for p in sorted((home / "bin").rglob("*")):
        if p.is_symlink():
            tgt = os.readlink(p)
            if ".skill-manager" in tgt and not any(tgt.startswith(s) for s in sp) and not tgt.startswith(".."):
                if tgt.startswith("/"):
                    bad.append({"path": str(p.relative_to(home)), "link": tgt})
            continue
        if not p.is_file():
            continue
        t = is_text(p) or ""
        for m in set(pat.findall(t)):
            if m not in sp and os.path.realpath(m) not in sp:
                bad.append({"path": str(p.relative_to(home)), "names": m})
    return {"count": len(bad), "items": bad[:30]}


def obs_census(home, art):
    """O2: stat every home-scope output the census names, independently."""
    if not art:
        return {"measured": False}
    rows = art.get("artifacts", [])
    prod_missing, stat_missing, disagree, ledger_only = [], [], [], []
    for a in rows:
        if a.get("origin") == "ledger" and not a.get("outputs"):
            ledger_only.append(a.get("id"))
        for o in a.get("outputs", []):
            if o.get("scope") != "home":
                continue
            exists = os.path.lexists(home / o["path"])
            if o.get("presence") == "missing":
                prod_missing.append(a["id"])
            if not exists:
                stat_missing.append(f'{a["id"]} -> {o["path"]}')
            if (o.get("presence") == "present") != exists:
                disagree.append({"id": a["id"], "path": o["path"], "product": o.get("presence"), "stat_exists": exists})
    return {"measured": True, "declared": len(rows), "declared_only": [a["id"] for a in rows if a.get("materialization") == "declared-only"],
            "product_missing_outputs": len(prod_missing), "stat_missing_outputs": len(stat_missing),
            "stat_missing_examples": stat_missing[:20], "presence_disagreements": disagree[:20],
            "ledger_only_rows": ledger_only,
            "agreement_disagrees": [a["id"] for a in rows if a.get("agreement") == "disagrees"]}


def obs_agent_links(h):
    """O3: agent-dir symlinks outside the home: dangling, or into another home."""
    home = h["home"]
    sp = spellings(home)
    dangling, foreign, total = [], [], 0
    for ad in h["agent_dirs"]:
        for sub in ("skills", "commands", "agents"):
            d = ad / sub
            if not d.is_dir():
                continue
            for p in d.iterdir():
                if not p.is_symlink():
                    continue
                total += 1
                tgt = os.readlink(p)
                absn = tgt if tgt.startswith("/") else os.path.normpath(p.parent / tgt)
                if not os.path.exists(p):
                    dangling.append(f"{p} -> {tgt}")
                elif ".skill-manager" in absn and not any(absn.startswith(s) or os.path.realpath(absn).startswith(s) for s in sp):
                    foreign.append(f"{p} -> {tgt}")
    return {"links": total, "dangling": dangling, "into_another_home": foreign}


def obs_projection_orphans(home):
    inst = home / "installed"
    return sorted(p.name for p in inst.glob("*.projections.json")
                  if not (inst / (p.name[: -len(".projections.json")] + ".json")).exists())


def obs_pm_unstamped(home):
    out, total = [], 0
    pm = home / "pm"
    if pm.is_dir():
        for tool in pm.iterdir():
            if tool.is_dir():
                for ver in tool.iterdir():
                    if ver.is_dir():
                        total += 1
                        if not (ver / ".platform").is_file():
                            out.append(f"pm/{tool.name}/{ver.name}")
    return {"versions": total, "unstamped": out}


def obs_records(home):
    """O7: installed record vs checkout: hash, and recorded version vs manifest version."""
    hash_dis, ver_dis = [], []
    for rec in sorted((home / "installed").glob("*.json")):
        if rec.name.endswith(".projections.json"):
            continue
        try:
            d = json.loads(rec.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        name = d.get("name", rec.stem)
        store = next((home / k / name for k in ("skills", "plugins", "docs", "harnesses") if (home / k / name).is_dir()), None)
        if store is None:
            continue
        if (store / ".git").exists() and d.get("gitHash"):
            p = subprocess.run(["git", "-C", str(store), "rev-parse", "HEAD"], capture_output=True, text=True)
            if p.returncode == 0 and p.stdout.strip() != d["gitHash"]:
                hash_dis.append({"unit": name, "record": d["gitHash"][:8], "checkout": p.stdout.strip()[:8]})
        mver = None
        for mf in (store / ".claude-plugin" / "plugin.json",):
            if mf.is_file():
                try:
                    mver = json.loads(mf.read_text()).get("version")
                except (OSError, json.JSONDecodeError):
                    pass
        tm = store / "skill-manager.toml"
        if mver is None and tm.is_file():
            m = re.search(r'^\s*version\s*=\s*"([^"]+)"', tm.read_text(), re.M)
            mver = m.group(1) if m else None
        if mver and d.get("version") and mver != d.get("version"):
            ver_dis.append({"unit": name, "record_version": d.get("version"), "checkout_manifest_version": mver})
    return {"hash_disagreements": hash_dis, "version_disagreements": ver_dis}


def toml_marketplaces(path: Path):
    if not path.is_file():
        return None
    t = path.read_text()
    res = {}
    for m in re.finditer(r'^\[marketplaces\.("?)([^\]"]+)\1\]\s*\n((?:(?!^\[).*\n?)*)', t, re.M):
        src = re.search(r'^\s*source\s*=\s*"([^"]+)"', m.group(3), re.M)
        res[m.group(2)] = src.group(1) if src else None
    plugins = re.findall(r'^\[plugins\."([^"]+)"\]', t, re.M)
    return {"marketplaces": res, "plugins": plugins}


def obs_marketplace(h):
    """O5: #352's four shapes, read from the files each agent loads."""
    home = h["home"]
    mp = home / "plugin-marketplace"
    mfile = mp / ".claude-plugin" / "marketplace.json"
    name = json.loads(mfile.read_text()).get("name") if mfile.is_file() else None
    sp = [os.path.join(s, "plugin-marketplace") for s in spellings(home)]
    km = {}
    try:
        km = json.loads((HOME / ".claude" / "plugins" / "known_marketplaces.json").read_text())
    except (OSError, json.JSONDecodeError):
        pass
    claude_names_at_path = sorted(k for k, v in km.items() if isinstance(v, dict) and (v.get("source") or {}).get("path") in sp)
    shape1 = [n for n in claude_names_at_path if n != name]
    shape2_exposed = name is not None and any(k != name and name in k for k in km)
    enabled = {}
    try:
        enabled = json.loads(h["claude_settings"].read_text()).get("enabledPlugins") or {}
    except (OSError, json.JSONDecodeError):
        pass
    sm_enabled = sorted(k for k in enabled if "@skill-manager" in k)
    stale_enabled = [k for k in sm_enabled if k.split("@", 1)[1] != name]
    codex = toml_marketplaces(h["codex"])
    shape4 = []
    if codex:
        for mname, src in codex["marketplaces"].items():
            if not mname.startswith("skill-manager"):
                continue
            if src and os.path.realpath(src) not in [os.path.realpath(s) for s in sp]:
                shape4.append({"name": mname, "source": src})
            elif src is None and mname != name:
                shape4.append({"name": mname, "source": None})
    return {"generated_name": name, "claude_registered_names_at_this_path": claude_names_at_path,
            "shape1_claude_name_differs": shape1, "shape2_substring_exposed": shape2_exposed,
            "claude_enabled_skill_manager_plugins": sm_enabled, "claude_enabled_under_other_name": stale_enabled,
            "codex": codex, "shape4_codex_foreign_or_misnamed": shape4}


def main():
    result = {"measured_at_commit": subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip(),
              "homes": []}
    for h in HOMES:
        v, art = verdicts(h)
        o = {
            "O1_own_absolute_path_in_generated_content": obs_own_path(h["home"]),
            "O2_census_vs_disk": obs_census(h["home"], art),
            "O3_agent_links_outside_home": obs_agent_links(h),
            "O4_orphan_projection_records": obs_projection_orphans(h["home"]),
            "O5_marketplace_identity": obs_marketplace(h),
            "O6_pm_unstamped": obs_pm_unstamped(h["home"]),
            "O7_record_vs_checkout": obs_records(h["home"]),
            "O8_bin_refs_into_another_home": obs_other_home_refs(h["home"]),
        }
        result["homes"].append({"label": h["label"], "home": str(h["home"]), "verdicts": v, "observations": o})
    (OUT / "baseline.json").write_text(json.dumps(result, indent=2, default=str))
    print(json.dumps(result, indent=2, default=str))


if __name__ == "__main__":
    main()
