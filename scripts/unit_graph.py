#!/usr/bin/env python3
"""The unit graph of a skill-manager home, recomputed. READ ONLY.

Shared by the OUN baseline harnesses. It is deliberately NOT a cache and
NOT an index: nothing here is written to disk, because a second record of
the edges is a second thing that can disagree with the units it describes.

TWO MECHANISMS, TWO KEYS -- this is the fact the epic turns on:

  skill-imports    markdown frontmatter, addresses a unit BY NAME, resolved
                   against THIS home's store. Read by MarkdownImportValidator.
  skill_references unit TOML, addresses a unit BY COORDINATE (github:owner/repo,
                   a path, a registry ref), resolved by Resolver at install
                   time. The name it lands under is decided by the fetch, not
                   by the string.

Neither is recorded in installed/*.json, which holds name, version, gitHash,
kind, origin, installSource, errors and installedAt -- so the reverse edge
("what imports X") exists nowhere on disk and must be recomputed from the
unit trees every time. That is what this module does.
"""
import json
import os
import re
from pathlib import Path

# The four directories MarkdownImportValidator.installedRoot searches, in its
# order. A plugin's contained skills (plugins/<p>/skills/<s>) are NOT among
# them -- that absence is GOAL-a-contained-skill-is-addressable.
STANDALONE_DIRS = (("plugins", "plugin"), ("harnesses", "harness"),
                   ("docs", "doc"), ("skills", "skill"))

FRONTMATTER = re.compile(r"\A---\r?\n(.*?)\r?\n---\s*?(\r?\n|\Z)", re.S)
IMPORT_UNIT = re.compile(r"^\s*(?:-\s*)?(?:unit|skill)\s*:\s*(.+?)\s*$", re.M)
SKILL_IMPORTS_KEY = re.compile(r"^skill-imports\s*:\s*(.*)$")
TOML_REFS = re.compile(r"^\s*(?:skill_)?references\s*=\s*\[(.*?)\]", re.S | re.M)
TOML_STR = re.compile(r"""["']([^"']+)["']""")

# A DELIBERATE DIVERGENCE from the product, which validates every markdown
# file under a unit root with no exclusions. Vendored trees and build output
# would otherwise be read as authored edges. Verified against both homes on
# 2026-09-05: including these directories changes neither the 8 nor the 6.
# If that ever stops being true the divergence has started mattering.
SKIP_DIRS = {".git", "node_modules", "__pycache__", ".venv", "venvs",
             "build", "target", ".gradle", "dist"}


def _unquote(s: str) -> str:
    s = s.strip()
    if len(s) >= 2 and s[0] == s[-1] and s[0] in "\"'":
        s = s[1:-1]
    return s.strip()


class Unit:
    """One addressable-or-not unit root inside one home."""

    def __init__(self, name, kind, root, contained_in=None):
        self.name = name
        self.kind = kind
        self.root = root
        self.contained_in = contained_in   # plugin name, when contained
        self.imports = []                  # [(target_name, source_file)]
        self.references = []               # [(coordinate, source_file)]

    @property
    def addressable(self):
        """Can a `unit:` skill-import name this root TODAY?

        installedRoot has four branches and none descends into a plugin, so a
        contained skill is not addressable no matter what its SKILL.md says.
        """
        return self.contained_in is None

    def __repr__(self):
        return f"<Unit {self.name} {self.kind}{' in ' + self.contained_in if self.contained_in else ''}>"


def _walk_markdown(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn.endswith(".md"):
                yield Path(dirpath) / fn


def _walk_toml(root: Path):
    """The unit's OWN manifests -- root level only.

    Not a recursive walk. BundleMetadata says "top-level only"; skt ships
    example manifests under examples/ and tests/ that name coordinates like
    `github:owner/base-skill`, and a recursive walk reads those as real edges.
    Measured: it inflated the reference edges across this machine's homes to
    626, nearly all of them documentation.
    """
    for fn in sorted(root.glob("skill-*.toml")):
        if fn.is_file():
            yield fn


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _imports_block(front: str):
    """The lines belonging to a top-level `skill-imports:` key.

    Line-based, not one regex. The regex form terminated the block at the
    first line starting with a non-space character, which swallowed the
    perfectly ordinary YAML that puts sequence items at zero indent:

        skill-imports:
        - unit: alpha        <- `-` is non-space, so the block ended here
          path: a.md

    and reported the file as importing nothing. No unit in any home on this
    machine is written that way today, so no measured number changes -- which
    is exactly why it would have gone unnoticed.
    """
    lines = front.splitlines()
    out = []
    for i, line in enumerate(lines):
        m = SKILL_IMPORTS_KEY.match(line)
        if not m:
            continue
        inline = m.group(1).strip()
        if inline and inline not in ("|", ">"):
            out.append(inline)          # `skill-imports: []` and friends
        for rest in lines[i + 1:]:
            if not rest.strip():
                out.append(rest)
                continue
            # A sibling top-level key ends the block. A sequence item (`-`)
            # and any indented line do not.
            if not rest[0].isspace() and not rest.lstrip().startswith("-"):
                break
            out.append(rest)
        break
    return "\n".join(out)


def parse_imports(text: str):
    """Unit names named by a `skill-imports:` frontmatter block.

    Frontmatter only -- inline import syntax is not supported by the product,
    so a harness that accepted it would over-report.
    """
    m = FRONTMATTER.match(text)
    if not m:
        return []
    block = _imports_block(m.group(1))
    if not block:
        return []
    names = []
    for raw in IMPORT_UNIT.finditer(block):
        name = _unquote(raw.group(1))
        # `unit:` inside a nested reason/description line would be a false
        # positive; a unit name has no whitespace.
        if name and not name.startswith("[") and " " not in name:
            names.append(name)
    return names


def parse_references(text: str):
    """Coordinates named by `skill_references`/`references` in a unit TOML."""
    out = []
    for block in TOML_REFS.finditer(text):
        for s in TOML_STR.finditer(block.group(1)):
            out.append(s.group(1))
    return out


def repo_key(s: str) -> str:
    """A coordinate or an origin URL reduced to `owner/repo`.

    `github:haydenrear/skill-manager-skill` and
    `https://github.com/haydenrear/skill-manager-skill` are the same unit;
    one is how a manifest spells it, the other is how installed/*.json does.
    """
    c = str(s).strip().rstrip("/")
    for sep in ("#", "?"):
        if sep in c:
            c = c.split(sep, 1)[0]
    c = re.sub(r"^(github|gitlab|git\+ssh|git\+https|https|http|ssh|git|file|path|registry):", "", c)
    c = c.lstrip("/").removeprefix("//")
    if "@" in c and ":" in c:                      # git@github.com:owner/repo
        c = c.split(":", 1)[1]
    for host in ("github.com/", "gitlab.com/"):
        if c.startswith(host):
            c = c[len(host):]
    c = c.removesuffix(".git")
    parts = [p for p in c.split("/") if p]
    return "/".join(parts[-2:]) if len(parts) >= 2 else c


def installed_index(home: Path):
    """origin repo -> installed unit NAME, read from installed/*.json.

    THE JOIN NOBODY DOES. A manifest names `github:haydenrear/skill-manager-skill`;
    the unit it installs is named `skill-manager`. Nothing in the product joins
    the coordinate to the installed name, so "what references skill-manager"
    cannot be answered from the manifests alone -- and answering it by taking
    the tail of the coordinate gets `skill-manager-skill`, a unit that does not
    exist in any home.
    """
    out = {}
    d = Path(home) / "installed"
    if not d.is_dir():
        return out
    for f in sorted(d.glob("*.json")):
        if f.name.endswith(".projections.json"):
            continue
        try:
            rec = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        name, origin = rec.get("name"), rec.get("origin")
        if name and origin:
            out[repo_key(origin)] = name
    return out


def coordinate_name(coord: str, index=None) -> str:
    """The unit name a coordinate resolves to.

    With an `installed_index`, this is a real answer. Without one it is a
    GUESS -- the tail of the coordinate -- and the guess is wrong for every
    unit in this repository whose repo name is not its unit name.
    """
    key = repo_key(coord)
    if index and key in index:
        return index[key]
    tail = key.rsplit("/", 1)[-1]
    return tail


def load_home(home: Path):
    """Every unit root in one home, with its outgoing edges. No writes."""
    home = Path(home)
    units = {}
    order = []
    for dirname, kind in STANDALONE_DIRS:
        d = home / dirname
        if not d.is_dir():
            continue
        for child in sorted(d.iterdir()):
            if not child.is_dir() or child.name.startswith("."):
                continue
            u = Unit(child.name, kind, child)
            order.append(u)
            units.setdefault(child.name, []).append(u)
            if kind == "plugin":
                contained = child / "skills"
                if contained.is_dir():
                    for c in sorted(contained.iterdir()):
                        if not c.is_dir() or c.name.startswith("."):
                            continue
                        cu = Unit(c.name, "contained-skill", c,
                                  contained_in=child.name)
                        order.append(cu)
                        units.setdefault(c.name, []).append(cu)
    # Edges. A contained skill's own files belong to the contained unit, not
    # to the plugin that carries it -- otherwise every plugin inherits its
    # skills' imports and the reverse edge names the wrong importer.
    contained_roots = [u.root for u in order if u.contained_in]
    for u in order:
        for md in _walk_markdown(u.root):
            if not u.contained_in and any(
                    md.is_relative_to(r) for r in contained_roots):
                continue
            for name in parse_imports(_read(md)):
                u.imports.append((name, md))
        for toml in _walk_toml(u.root):
            if not u.contained_in and any(
                    toml.is_relative_to(r) for r in contained_roots):
                continue
            for coord in parse_references(_read(toml)):
                u.references.append((coord, toml))
    return order, units


def importers(order, target, mechanisms=("imports", "references"), index=None):
    """Direct importers of `target`, per mechanism. Names, not roots."""
    direct = {}
    for u in order:
        hits = []
        if "imports" in mechanisms:
            hits += [(n, f, "skill-imports") for n, f in u.imports if n == target]
        if "references" in mechanisms:
            hits += [(coordinate_name(c, index), f, "skill_references")
                     for c, f in u.references
                     if coordinate_name(c, index) == target]
        if hits:
            direct.setdefault(u.name, []).extend(hits)
    return direct


def transitive_importers(order, target, mechanisms=("imports", "references"),
                         index=None):
    """Everything that reaches `target`, directly or through another unit.

    CYCLE-SAFE BY CONSTRUCTION: a name is added to `seen` before it is
    expanded, so a cycle (a imports b, b imports a) is walked once and the
    walk returns. The epic asked for the whole chain BECAUSE of those loops,
    not despite them.
    """
    seen = set()
    frontier = [target]
    while frontier:
        cur = frontier.pop()
        for name in importers(order, cur, mechanisms, index):
            if name in seen or name == target:
                continue
            seen.add(name)
            frontier.append(name)
    return sorted(seen)


def default_homes():
    """Every home on this machine worth walking, root first.

    The root home, then every sibling checkout's project home and every
    worktree home beside this one. "Every home" is the goal's own wording:
    a name that resolves to two copies is a defect wherever it happens, and
    the counts differ per home precisely because homes are COPIES that do not
    update each other.
    """
    out, seen = [], set()

    def add(p):
        try:
            r = p.resolve()
        except OSError:
            return
        if p.is_dir() and r not in seen:
            seen.add(r)
            out.append(p)

    add(Path(os.environ.get("HOME", "~")).expanduser() / ".skill-manager")
    here = Path(__file__).resolve().parent.parent
    add(here / ".skill-manager")
    for sibling in sorted(here.parent.iterdir()):
        if sibling.is_dir():
            add(sibling / ".skill-manager")
    return out


def home_label(home: Path) -> str:
    p = Path(home).resolve()
    if p.parent == Path(os.environ.get("HOME", "~")).expanduser():
        return "root"
    return p.parent.name
