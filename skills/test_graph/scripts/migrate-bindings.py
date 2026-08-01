#!/usr/bin/env python3
"""Explicitly migrate legacy provider symlinks to portable managed bindings.

Migration is all-or-nothing. Everything that can be decided without writing
is decided first - the provider is selected and the manifest document is
validated before the first byte is written - and the writes that remain are
undone as a group if any of them fails. A migration that does not finish
leaves the project exactly as it was found, still on the legacy path, because
a half-migrated project is worse than an unmigrated one: the wrappers see a
manifest, stop falling back to the legacy symlinks, and hard-exit.

For the same reason an explicitly passed ``--workspace-provider`` must resolve
here and now. The manifest this writes is committed and read by every consumer
of the repository, so a provider path that resolves nowhere has to be refused
at authoring time even though falling back to the installed skill is the right
behaviour at run time. See :func:`_require_workspace_provider`.

Why migration normalizes
========================

Migration writes the full canonical :data:`PROVIDER_BINDINGS` set - all three -
even when the legacy project tracked only two symlinks, and it says so on
stdout. That is a decision, taken on measurement rather than on principle, and
the measurement is worth keeping because the next reader will be tempted to
"fix" it into a faithful 1:1.

What was measured, over the eight scaffolded projects migrated in the first
rollout (pre-migration tracked symlinks -> manifest bindings):

===================================  ====  =====
project                              had   wrote
===================================  ====  =====
deploy-helm                          3     3
hyper-experiments                    3     3
spec-double-compiler                 3     3
support-agent-rears                  3     3
commit-diff-context-parent           2     3
hyper-experiments-finance            2     3
meta-orchestrator/stream-lite        2     3
meta-orchestrator/stream-lite-mgr    2     3
===================================  ====  =====

Three arguments decided it:

1. **The set is a property of the provider, not of the consumer.** Every value
   in ``PROVIDER_BINDINGS`` is a path inside *this skill's*
   ``project_sdk_sources/``. Deriving it from whichever links a consumer
   happened to track makes a provider-side fact vary per consumer, and a
   project that later opens a ``standardNode(...)`` would have to hand-edit a
   committed manifest - or re-run a migration it has already completed - to get
   a directory the SDK always shipped.

2. **"What the repo had" was not a statement of intent.** In three of the four
   two-link projects it was a record of the bug being migrated away from:
   ``commit-diff-context-parent`` was missing ``standard-nodes`` entirely, and
   both ``meta-orchestrator`` constituents held one developer's absolute
   ``~/.skill-manager`` path in the tracked blob. A faithful 1:1 migration
   preserves that.

3. **The cost is bounded, and the extra link is conditional rather than
   inert.** ``standard-nodes`` is optional at build time -
   ``ValidationGraphExtension.indexedSourcesDirs`` (``kt:38``) and
   ``GraphAssembler.plan`` (``kt:34``) both guard on ``isDirectory`` - so an
   absent catalog is not an error. But once the directory exists,
   ``plan`` indexes it, and that opens two id-precedence paths which the next
   reader must not assume away:

   * ``GraphAssembler.kt:46-52`` - an explicit node whose id matches a catalog
     node with a *different* runtime becomes a hard
     ``error("node id '...' is reserved by the shipped standard-node catalog")``.
     A project that built before materializing the link can stop building.
   * ``GraphAssembler.kt:63-65`` - ``sourceIndex.putAll(standardIndex)`` runs
     before the consumer directories' ``putIfAbsent``, so a ``dependsOn`` id
     that previously hard-failed as an unresolved dependency now resolves
     *silently* to a provider-owned node, which takes precedence over a
     consumer script of the same id.

   Neither has any effect on the migrated projects today: the shipped catalog is
   two scripts (``monitoring_cluster_ensure``, ``monitoring_cluster_assert_ready``)
   and none of the four two-link projects carries a node of either id. The
   claim is "no shipped effect", measured - not "inert", which would be the
   sentence that stops the next reader checking. It is generated and gitignored, so it is not in any diff.

The real defect behind the complaint was never the extra link. It was that
``provider-bindings.json`` and the repository's ``[[vendored]]`` ``paths`` list
are two records of the same fact that could disagree with nothing detecting it:
``ProjectVendoredResolver.check`` iterates *declared* paths only, so a
generated binding no ``[[vendored]]`` block names is not an error and not a
warning - it is invisible, and the one link that most needs checking is the one
whose disguised relative text (``sdk/../standard-nodes``) resolves through a
sibling into a foreign home.

A fourth argument settled it for good, and #36's "one-line difference either
way" framing is simply wrong because of it: ``validate_provider_bindings_document``
requires a manifest's ``bindings`` to equal ``PROVIDER_BINDINGS`` **exactly**. A
faithful 1:1 migration would have had to relax that from "exactly these three"
to "any subset", deleting the only check that stops a hand-edited manifest from
binding an arbitrary path.

So normalizing is paired with :func:`_require_vendored_agreement`, which
refuses - before the first write - to migrate a project where a manifest already
declares *some* of these bindings and not the rest. After a successful migration
the two records cannot disagree, because a run that would have made them
disagree does not happen. A project no manifest claims at all is reported rather
than refused; that distinction, and why it is not a weakening, is documented on
that function.

(``scaffold.py`` writes the same manifest for a brand-new project and does not
yet run this check; a scaffolded project can still reach the undeclared state.
Adopting :func:`_require_vendored_agreement` there is the obvious next step.)
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import tomllib
from pathlib import Path

from _common import (
    PROVIDER_BINDING_IGNORE_BEGIN,
    PROVIDER_BINDINGS,
    PROVIDER_BINDINGS_MANIFEST,
    ProviderBindingError,
    add_test_graph_root_arg,
    capture_managed_bindings,
    ensure_provider_binding_ignores,
    missing_provider_bindings,
    prepare_provider_bindings,
    provider_bindings_document,
    provider_root_is_complete,
    rollback_managed_bindings,
    select_provider_root,
    target_project_root,
    validate_provider_bindings_document,
    write_provider_bindings_manifest,
)

#: Both names ``SkillProjectParser.findManifest`` accepts, in its order:
#: ``PRIMARY_TOML_FILENAME`` then ``LEGACY_TOML_FILENAME``. Searching for only
#: the first made this check blind to a repository whose sole manifest is the
#: legacy one.
PROJECT_MANIFEST_FILENAMES = ("skill-project.toml", "skill-manager-project.toml")

#: Markers of an integration-repository root: a parent whose working tree holds
#: constituent repositories as ordinary files. Used only as the search ceiling.
INTEGRATION_ROOT_MARKERS = ("integration.toml", "INTEGRATION.md")

#: Prefix of the one-line verdict every run prints for the two-record check.
#: Named so a fan-out loop can grep it; a run that printed nothing would be
#: indistinguishable from a run that could not look.
VENDORED_AGREEMENT_PREFIX = "vendored-agreement:"


def _git_output(root: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        text=True,
        capture_output=True,
        check=False,
    )


def _generated_link_index(root: Path) -> tuple[Path | None, list[str], list[str]]:
    """Read-only survey: (git root, tracked binding paths, their index entries).

    The entries are ``git ls-files -s`` lines, which is exactly the format
    ``git update-index --index-info`` consumes, so :func:`_restore_index`
    can put back the mode and blob the index held rather than approximating
    them from HEAD.
    """

    top_level = _git_output(root, "rev-parse", "--show-toplevel")
    if top_level.returncode != 0:
        return None, [], []
    git_root = Path(top_level.stdout.strip()).resolve()
    # Both sides resolved: git reports a physical path, and a caller-supplied
    # root may still be the /var -> /private/var kind of symlink.
    resolved_root = root.resolve()
    tracked: list[str] = []
    for name in sorted(PROVIDER_BINDINGS):
        relative = (resolved_root / name).relative_to(git_root).as_posix()
        probe = _git_output(git_root, "ls-files", "--error-unmatch", "--", relative)
        if probe.returncode == 0:
            tracked.append(relative)
    if not tracked:
        return git_root, [], []
    listed = _git_output(git_root, "ls-files", "-s", "--", *tracked)
    if listed.returncode != 0:
        raise ProviderBindingError(
            "cannot read index entries for generated provider links: "
            f"{listed.stderr.strip()}"
        )
    return git_root, tracked, listed.stdout.splitlines()


def _physical(path: Path) -> Path:
    """A comparable identity for a path that may not exist yet.

    The directory components are resolved physically and the leaf is kept as a
    name. Resolving the whole path is not an option here - the generated links
    do not exist before a migration, and after one the leaf is itself a symlink
    into the provider, so resolving it would compare provider paths instead of
    project paths.

    Resolving the parent is not decoration. The shape that has defeated three
    checks in this repository is a relative link whose PARENT is an absolute
    link; a lexical comparison of ``test_graph/sdk`` against ``test_graph/sdk``
    says "same" for two different physical files. It is also what makes
    ``/var`` and ``/private/var`` compare equal.
    """

    return path.parent.resolve() / path.name


def _find_manifest(directory: Path) -> Path | None:
    """One directory's project manifest, primary name first, then legacy.

    Both names, in this order, because that is what the reader of this record
    accepts: ``SkillProjectParser.findManifest`` tries ``PRIMARY_TOML_FILENAME``
    and then ``LEGACY_TOML_FILENAME``. Knowing only the primary name made this
    check *vacuous* for a repository whose only manifest is the legacy one - it
    would report "nothing encloses this project" about a manifest that
    ``project resolve`` reads, which is the same class of bug as a search that
    cannot see. Add a name here whenever that Java method gains one.
    """

    for filename in PROJECT_MANIFEST_FILENAMES:
        candidate = directory / filename
        if candidate.is_file():
            return candidate
    return None


def _manifest_search(root: Path) -> tuple[list[Path], str]:
    """Directories to search for project manifests, and the ceiling reached.

    Returns ``(directories nearest-first, a description of the ceiling)``. The
    description is printed in the verdict: a bounded search that does not say
    where it stopped cannot be told apart from one that looked everywhere.

    The ceiling is chosen in two phases, and the order matters:

    1. Scan all the way up for the nearest **integration root** - a directory
       carrying ``integration.toml`` or ``INTEGRATION.md``. If one exists it is
       the ceiling, inclusive, *whatever* ``.git`` directories sit below it.
    2. Otherwise the nearest directory carrying ``.git``, inclusive.
    3. Otherwise the filesystem root.

    Phase 1 must be a global scan rather than a per-level test, and that is the
    fix for a real defect in the first version of this check, which used
    ``git rev-parse --show-toplevel``. In an integration repository a constituent
    has its own ``.git`` in the parent's main working tree and **none** in an
    outer worktree (see the integration repo's ``CLAUDE.md``, "Worktree care").
    Any ``.git``-first rule therefore yields the constituent root in one tree and
    something else in the other: identical bytes, two different ceilings,
    flipping exactly where the fan-out runs. An integration root is always
    *above* every constituent and is a tracked file present in every checkout
    and worktree, so anchoring on it makes the ceiling independent of
    constituent metadata.

    Searching *above* a constituent's own repository is not an accident here, it
    is required: ``meta-orchestrator`` deliberately keeps ONE manifest at its
    root that declares vendored paths reaching into
    ``constituents/<c>/test_graph/``, so for those projects the governing record
    genuinely lives above them.

    The ceiling only bounds how far the search looks. It is deliberately not
    what decides the outcome - see :func:`_require_vendored_agreement`, where a
    manifest counts only if it already declares a path inside this project.
    That is what stops an integration root's manifest, which by design declares
    nothing about a constituent's internals, from producing a refusal no one can
    satisfy.
    """

    start = root.resolve().parent
    ceiling: Path | None = None
    reason = ""
    current = start
    while True:
        if any((current / marker).exists() for marker in INTEGRATION_ROOT_MARKERS):
            ceiling, reason = current, f"integration root {current}"
            break
        if current == current.parent:
            break
        current = current.parent
    if ceiling is None:
        current = start
        while True:
            if (current / ".git").exists():
                ceiling, reason = current, f"repository root {current} (.git)"
                break
            if current == current.parent:
                ceiling, reason = current, f"filesystem root {current}"
                break
            current = current.parent

    directories: list[Path] = []
    current = start
    while True:
        directories.append(current)
        if current == ceiling or current == current.parent:
            return directories, reason
        current = current.parent


def _declared_vendored_paths(manifest: Path) -> dict[Path, str]:
    """Every ``[[vendored]]`` path in one manifest, keyed by physical identity.

    The value is the declaration name, so a refusal can point at the block that
    already exists instead of telling the user to add a second one.
    """

    try:
        document = tomllib.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, tomllib.TOMLDecodeError) as error:
        raise ProviderBindingError(
            f"cannot read {manifest} to check it against the bindings this "
            f"migration would create: {error}"
        ) from error
    project_root = manifest.parent.resolve()
    declared: dict[Path, str] = {}
    blocks = document.get("vendored", [])
    if not isinstance(blocks, list):
        raise ProviderBindingError(f"{manifest}: [[vendored]] must be an array of tables")
    for block in blocks:
        if not isinstance(block, dict):
            continue
        name = str(block.get("name", "<unnamed>"))
        paths = block.get("paths", [])
        if not isinstance(paths, list):
            raise ProviderBindingError(f"{manifest}: vendored {name} has a non-list `paths`")
        for declared_path in paths:
            if not isinstance(declared_path, str) or not declared_path:
                continue
            declared.setdefault(_physical(project_root / declared_path), name)
    return declared


def _require_vendored_agreement(root: Path) -> list[str]:
    """Refuse a migration that would leave the two records disagreeing.

    Returns the verdict lines to print. Read-only, and called before the first
    write for the same reason the provider is resolved there: a refusal that
    costs the user nothing beats a committed record nothing checks.

    Three verdicts, and only one of them refuses. The distinction is the whole
    design, so it is worth stating why:

    ``ok``
        Some manifest declares every binding this run will generate. Nothing to
        do.

    ``partial`` - **refuses**
        A manifest declares *some* of this project's bindings and not the rest.
        That is a genuine disagreement between two records of the same fact, it
        is the shipped ``hyper-experiments-finance`` shape, and it is **always
        satisfiable**: the remedy is to extend a ``[[vendored]]`` block that
        already reaches into this project, which is by construction the right
        record to edit.

    ``unclaimed`` - reports, loudly, and proceeds
        No manifest declares *any* of this project's bindings. Nothing will
        validate them - a real problem, and the state
        ``meta-orchestrator``'s own ``test_graph/`` is in - but it is the
        *absence* of the second record, not a disagreement between two of them,
        and refusing here is what makes the guard unsatisfiable. An integration
        repository's root manifest declares nothing about a constituent's
        internals **by design**, so a refusal in that position cannot be
        cleared: an operator following the remedy would add
        ``constituents/<c>/test_graph/...`` to the integration parent's
        manifest, which is the wrong record entirely.

    That split is also what makes the outcome independent of which tree the
    migration runs in. The first version of this check refused in a worktree and
    passed in the main working tree for identical bytes, because it keyed on
    ``git rev-parse --show-toplevel`` and a constituent's ``.git`` exists only
    in one of them. Now the ceiling anchors on the integration root
    (:func:`_manifest_search`) and, more importantly, a manifest only counts
    when it already declares a path inside this project - so a manifest that
    claims nothing here cannot change the disposition however the search reaches
    it.

    Why the refusing case refuses rather than warns: this script runs once per
    repository, usually inside a fan-out loop over many of them, and a warning
    on stdout there is a warning nobody reads while the disagreement it
    describes is committed and permanent.

    Every verdict names the ceiling the search stopped at and the manifests it
    found. "Nothing above this project" and "the search stopped at the ceiling"
    are different facts, and printing them as one sentence would put a vacuous
    claim inside the line that exists so silence never needs interpreting.
    """

    directories, ceiling = _manifest_search(root)
    manifests = [found for found in map(_find_manifest, directories) if found is not None]
    wanted = {name: _physical(root / name) for name in sorted(PROVIDER_BINDINGS)}

    # Nearest-first: the first manifest that claims ANY of this project's
    # bindings is the record that governs them. A nearer manifest that claims
    # none of them is not a governing record and must not mask one above it.
    for manifest in manifests:
        declared = _declared_vendored_paths(manifest)
        claimed = {name: path for name, path in wanted.items() if path in declared}
        if not claimed:
            continue
        undeclared = sorted(name for name in wanted if name not in claimed)
        owners = sorted({declared[path] for path in claimed.values()})
        if not undeclared:
            return [
                f"{VENDORED_AGREEMENT_PREFIX} ok - all {len(wanted)} managed bindings "
                f"are declared by [[vendored]] {', '.join(owners)} in {manifest}",
                f"    search ceiling: {ceiling}",
            ]
        relative = _relative_binding_paths(root, manifest.parent.resolve())
        raise ProviderBindingError(
            f"{manifest} declares {len(claimed)} of the {len(wanted)} bindings this "
            "migration generates and would not validate the "
            f"{len(undeclared)} remaining: " + ", ".join(undeclared) + ".\n"
            "  `skill-manager project resolve` classifies DECLARED [[vendored]] paths "
            "only, so an undeclared generated link is not a warning - it is never "
            "checked, and a link that resolves into a foreign home reports clean.\n"
            "  Refusing before writing anything so the committed manifest and the "
            "[[vendored]] paths cannot disagree. Add the missing path(s) to the "
            f"existing [[vendored]] block {', '.join(owners)}, so its `paths` reads:\n"
            f"    paths = [{', '.join(repr(entry) for entry in relative)}]\n"
            f"  (search ceiling: {ceiling})"
        )

    # Nothing claims these bindings. Say exactly which of the two shapes it is.
    if manifests:
        nearest = manifests[0]
        found = (
            f"{len(manifests)} project manifest(s) found, none declaring a path "
            f"inside this project; nearest is {nearest}"
        )
        remedy_root = nearest.parent.resolve()
    else:
        found = (
            "no project manifest found in "
            f"{len(directories)} searched director{'y' if len(directories) == 1 else 'ies'} "
            f"({', '.join(name for name in PROJECT_MANIFEST_FILENAMES)})"
        )
        remedy_root = root.resolve().parent
    relative = _relative_binding_paths(root, remedy_root)
    return [
        f"{VENDORED_AGREEMENT_PREFIX} unclaimed - `skill-manager project resolve` "
        f"validates NONE of the {len(wanted)} managed bindings this run generates: "
        + f"{found}",
        f"    search ceiling: {ceiling}",
        "    nothing can disagree, because there is no second record; to get these "
        "bindings validated, declare them in the manifest that owns this project:",
        "      [[vendored]]",
        '      name = "test-graph-sdk"',
        f"      paths = [{', '.join(repr(entry) for entry in relative)}]",
        '      from_unit = "test-graph"',
        '      from_subpath = "project_sdk_sources"',
        '      on_invalid = "error"',
    ]


def _relative_binding_paths(root: Path, project_root: Path) -> list[str]:
    """This project's binding paths as a manifest at ``project_root`` spells them."""

    resolved = root.resolve()
    return [
        (resolved / name).relative_to(project_root).as_posix()
        if project_root in (resolved / name).parents
        else str(resolved / name)
        for name in sorted(PROVIDER_BINDINGS)
    ]


def _require_workspace_provider(root: Path, workspace_provider: str) -> None:
    """An explicitly passed --workspace-provider is an assertion, not a hint.

    Authoring time and run time are deliberately asymmetric here, and the two
    must not be "fixed" to match:

    * At run time - prepare-bindings.py and the wrappers - falling through a
      missing workspace provider to the installed skill is correct. The
      manifest is already committed and shared; the workspace copy can be
      legitimately absent on this machine or this CI runner, and the build
      still has to run.
    * At authoring time - here - the same fall-through is a silent defect. The
      unresolvable path is written into the manifest as the *first* candidate
      and committed, the command exits 0, and from then on every consumer of
      that repository skips the dead candidate and materializes an absolute
      skill-root link: precisely the absolute-path-in-the-tree failure managed
      bindings exist to remove. One mistyped path in a fan-out loop reproduces
      it across every repository at once, with a green exit each time.

    So a provider the caller named explicitly must resolve now. Omitting the
    flag is unchanged: the skill-root candidate stands alone and is legitimate.
    """

    resolved = (root / workspace_provider).resolve()
    if provider_root_is_complete(resolved):
        return
    raise ProviderBindingError(
        f"--workspace-provider {workspace_provider} does not carry a Test Graph "
        f"provider (resolved to {resolved}; missing "
        + ", ".join(missing_provider_bindings(resolved))
        + "). Refusing to fall back to the installed skill: that would commit "
        "this path as the manifest's first candidate and leave every consumer "
        "materializing an absolute link. Fix the path, or omit "
        "--workspace-provider to bind to the installed skill deliberately."
    )


def _untrack_generated_links(git_root: Path, tracked: list[str]) -> None:
    removed = _git_output(git_root, "rm", "--cached", "--quiet", "--", *tracked)
    if removed.returncode != 0:
        raise ProviderBindingError(
            "cannot untrack generated provider links: " f"{removed.stderr.strip()}"
        )


def _restore_index(git_root: Path, entries: list[str]) -> None:
    """Put the captured index entries back after a failed migration."""

    if not entries:
        return
    restored = subprocess.run(
        ["git", "-C", str(git_root), "update-index", "--index-info"],
        input="\n".join(entries) + "\n",
        text=True,
        capture_output=True,
        check=False,
    )
    if restored.returncode != 0:
        print(
            "warning: could not restore the Git index after a failed migration: "
            f"{restored.stderr.strip()}\n"
            "  restore it with:  git -C "
            f"{git_root} checkout HEAD -- " + " ".join(sorted(PROVIDER_BINDINGS)),
            file=sys.stderr,
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_test_graph_root_arg(parser)
    parser.add_argument(
        "--workspace-provider",
        help=(
            "Optional provider root relative to test_graph/, recorded as the "
            "manifest's first candidate. If given it must resolve now: "
            "migration refuses rather than quietly binding to the installed "
            "skill instead. Omit it to bind to the installed skill on purpose. "
            "At run time the installed skill remains the fallback."
        ),
    )
    args = parser.parse_args()
    root = target_project_root(args.test_graph_root)
    real_paths = [
        root / name
        for name in sorted(PROVIDER_BINDINGS)
        if (root / name).exists() and not (root / name).is_symlink()
    ]
    if real_paths:
        parser.error(
            "refusing to replace copied provider paths: "
            + ", ".join(str(path) for path in real_paths)
        )

    # Decide everything that can be decided without writing. A migration that
    # cannot complete must not start: refusing here costs the user nothing,
    # whereas a manifest left behind by a failed one takes the wrappers off
    # the working legacy path.
    try:
        document = provider_bindings_document(
            workspace_provider=args.workspace_provider,
        )
        validate_provider_bindings_document(document)
        if args.workspace_provider is not None:
            _require_workspace_provider(root, args.workspace_provider)
        select_provider_root(root, document)
        agreement = _require_vendored_agreement(root)
        git_root, tracked, index_entries = _generated_link_index(root)
    except (OSError, ValueError, ProviderBindingError) as error:
        parser.error(str(error))

    # The writes that remain can still fail on the filesystem or in Git, so
    # they are applied as one group and undone as one group.
    before = capture_managed_bindings(root)
    untracked = False
    try:
        write_provider_bindings_manifest(
            root,
            workspace_provider=args.workspace_provider,
        )
        ensure_provider_binding_ignores(root)
        if tracked:
            assert git_root is not None
            _untrack_generated_links(git_root, tracked)
            untracked = True
        result = prepare_provider_bindings(root)
    except (OSError, ValueError, ProviderBindingError) as error:
        # The original error explains why the migration failed and is the more
        # useful of the two, so a rollback that fails is reported alongside it,
        # never instead of it.
        try:
            rollback_managed_bindings(root, before)
            # Only when the index really changed: `git rm --cached` writes the
            # index once, under lock, so a failed one left nothing to put back.
            if untracked:
                assert git_root is not None
                _restore_index(git_root, index_entries)
        except OSError as rollback_error:
            parser.error(
                f"{error}\n"
                f"  the rollback then failed too: {rollback_error}\n"
                f"  {root} is partially migrated: delete "
                f"{PROVIDER_BINDINGS_MANIFEST}, drop the "
                f"{PROVIDER_BINDING_IGNORE_BEGIN} block from .gitignore, and "
                "restore the generated links with `git checkout HEAD --`"
            )
        parser.error(f"{error}\n  migration rolled back; the project is unchanged")
    assert result is not None
    print(f"migrated Test Graph provider bindings at {root}")
    if tracked:
        print("  staged generated-link removals: " + ", ".join(tracked))
    # Normalizing up to the canonical set is legitimate (see "Why migration
    # normalizes" above) but it must never be silent: this is the only moment
    # at which anybody can see that the project gained a path it never had.
    # Both outcomes are printed, and they are distinguishable, because a line
    # that only appears when something was added cannot be told apart from a
    # line the script forgot to emit.
    added = sorted(name for name, target in before.links.items() if target is None)
    if added:
        print(
            "  normalized to the canonical binding set; generated links the "
            "project did not have: " + ", ".join(added)
        )
    else:
        print(
            "  normalized to the canonical binding set; generated links the "
            "project did not have: none (1:1 with the links found)"
        )
    for line in agreement:
        print("  " + line)
    print("  review provider-bindings.json, .gitignore, and git status; then commit them")
    return 0


if __name__ == "__main__":
    sys.exit(main())
