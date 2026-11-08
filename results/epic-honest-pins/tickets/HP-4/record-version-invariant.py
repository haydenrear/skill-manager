import json, sys, pathlib, re
home = pathlib.Path(sys.argv[1])
bad = 0; n = 0
for rec in sorted((home/"installed").glob("*.json")):
    if rec.name.endswith(".projections.json"): continue
    r = json.loads(rec.read_text()); name = r.get("name"); kind = (r.get("unitKind") or "SKILL").upper()
    d = home/("plugins" if kind=="PLUGIN" else "skills")/name
    v = None
    pj = d/".claude-plugin"/"plugin.json"
    if kind=="PLUGIN" and pj.exists():
        v = json.loads(pj.read_text()).get("version")
    toml = d/"skill-manager.toml"
    if v is None and toml.exists():
        m = re.search(r'^\s*version\s*=\s*"([^"]+)"', toml.read_text(), re.M); v = m and m.group(1)
    if v is None: continue
    n += 1
    if v != r.get("version"):
        bad += 1; print(f"  MISMATCH {kind.lower()}:{name} record={r.get('version')} manifest={v}")
print(f"{home}: {n} units with a manifest version, {bad} mismatched")
