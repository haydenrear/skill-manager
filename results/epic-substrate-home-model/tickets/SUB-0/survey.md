# SUB-0 — Survey: every machine-local assumption in the home model

Ticket: [#274](https://github.com/haydenrear/skill-manager/issues/274) ·
Epic: `substrate-home-model` · Goal: `GOAL-every-machine-local-assumption-is-named`
Surveyed at `4423b80` (epic base) / `9c156fe` (epic tip at branch time), 2026-08-29.

## What this is, and what it is not

This epic **deploys nothing** to Kubernetes or Substrate, and neither skill-manager
nor `skt` gains any ability to drive Substrate. This document answers a question
about *our* model: skill-manager has been built end to end on the assumption that
every home is on one machine and one filesystem, and that assumption has never been
enumerated. Here it is enumerated.

Nothing in this ticket changes production code. Reading and recording only.

## How to read a disposition

| mark | meaning |
| --- | --- |
| **unaffected** | The surface already works when the home is a different filesystem on a different machine, and there is positive evidence that it does. Nothing is owed. |
| **needs-contract** | The mechanism survives, but only under a precondition nobody has written down. Someone must state the precondition; no code has to change if the precondition is met. |
| **needs-change** | The surface has a behaviour today that is wrong, silently wrong, or unavailable in a container. Code or design must change. |

Every row carries `file:line` evidence. Where a claim is a *prediction* from a
measured mechanism rather than an observation of a container, it says so — SUB-5
is the ticket that observes, and this survey must not launder its reasoning as
measurement.

## The container this survey assumes

Stated up front so a later ticket can correct it rather than argue with an
unstated premise. Claims marked *(assumed)* are precisely the ones SUB-5 exists to
check.

1. The agent process runs in a container with its own filesystem namespace. *(assumed)*
2. The container carries a checkout and may carry a home; it does **not** carry the
   operator's `~/.skill-manager`, and `$HOME` is a container path, not the
   operator's. *(assumed)*
3. The container may be suspended and snapshot-restored, so any open file handle,
   advisory lock, or loopback port may not survive. *(assumed)*
4. Nothing else on the host is reachable by path. Host toolchains
   (`/opt/homebrew/...`) exist in the container only if the image put them there.
   *(assumed)*

There is **one** existing pre-drawn boundary that makes this tractable, and it is a
deliberate design rule rather than an accident —
`src/main/java/dev/skillmanager/store/HomePaths.java:13-19`:

> A path that points *into* the home is a self-reference and is stored as
> `$SKILL_MANAGER_HOME/<relative>`. A path that points anywhere else is genuinely
> external … and is stored verbatim, because rewriting it would break the thing it
> points at.

So **every absolute path a home carries points outside that home**. The survey's
job reduces to: enumerate what those outside paths are, and decide each one.

---

## Disposition summary — 9 of 9, none `unknown`

| # | surface | disposition | one-line reason |
| --- | --- | --- | --- |
| 1 | home discovery | **unaffected** | One rule, `$SKILL_MANAGER_HOME` else `$HOME/.skill-manager`, with no walk and no stat in Java; the shim derives the home from its own location. |
| 2 | tier derivation | **needs-change** | The only three-way classifier is `skt`'s, and it is git-plumbing + one-disk containment. **Measured:** a container-shaped checkout silently classifies `worktree` → `project`, and then `skt publish` has no destination. |
| 3 | `bin/cli` / `bin/launch` shims | **needs-change** | `bin/launch` is clean; `bin/cli` is not. **Measured:** a real home's own CLI pin is `/opt/homebrew/bin/skill-manager`, and 14–18 links per home resolve to host toolchains. |
| 4 | home sync | **needs-contract** | Both ends are required absolute-path flags and the transfer is `Files.copy` + `ATOMIC_MOVE`. It works across a shared mount and is undefined across two containers; `HomeLock` cannot arbitrate. |
| 5 | child materialization | **needs-change** | `LINK` mode writes absolute symlinks into the parent store; `CHECKOUT` mode `git clone`s a local path; shim mirroring is *always* an absolute symlink into the parent, whatever the mode. |
| 6 | CLI pins | **needs-change** | `cli-lock.toml` is clean, but the CLI pin is one absolute host path to a versioned Homebrew keg, and it travels through a clone unrewritten. **Measured** in the clone fixture. |
| 7 | agent projections | **needs-contract** | Projections are absolute symlinks and the ledger stores `destPath` verbatim — by the `HomePaths` rule, since agent dirs are *siblings* of `.skill-manager`. `HomeCloner.remapPath` already repairs both on clone. |
| 8 | credentials | **needs-change** | **Settled by demonstration:** `auth.token` is copied verbatim into every clone, 0600 preserved, while `home clone`'s isolation oracle prints ✓. Issue #281 answered. |
| 9 | the drift digest | **unaffected** | Content-addressed over sorted relative paths; no mtime, uid, inode or absolute path in the hash. Two narrow caveats recorded, neither fatal. |

**Score: 9 of 9 dispositioned. 2 unaffected, 3 needs-contract, 4 needs-change.**

---

## 1. Home discovery — **unaffected**

### What it is

Deciding *which* Skill Manager home this process operates on.

### Dependence on one machine or one filesystem

There is exactly one resolver and it is a two-step rule with no search —
`src/main/java/dev/skillmanager/store/SkillStore.java:92-98`:

```java
public static SkillStore defaultStore() {
    Path root = AgentHomes.resolve(HOME_ENV);
    if (root == null) root = AgentHomes.userHome().resolve(".skill-manager");
    return new SkillStore(root);
}
```

Called from 71 sites. The lookup chain behind it is env-or-override only —
`src/main/java/dev/skillmanager/agent/AgentHomes.java:114-121` — and the `$HOME`
fallback at `AgentHomes.java:190-193` reads the `HOME` *variable* first and only
then `System.getProperty("user.home")`, deliberately, so a redirected home is
honoured.

`--home` / `--home-root` are implemented as thread-local overrides on that same
chain — `src/main/java/dev/skillmanager/cli/SkillManagerCli.java:330-386` — so they
are not a second mechanism.

**Java performs no upward directory walk to find its own home, and does not stat
the filesystem to discover one.** Every `getParent()` loop in `src/main/java` is a
*foreign-home detector* or a path canonicalizer, not a discovery walk
(`launch/LaunchEnv.java:283-287`, `store/HomeCloner.java:2641-2646`,
`cli/installer/CliArtifact.java:170-174`). The one `.skill-manager` ancestor walk in
Java is scoped to `[[vendored]]` project-source resolution and is package-private —
`src/main/java/dev/skillmanager/project/ProjectVendoredResolver.java:421-427`.

Existence is a *separate, later* question: `SkillStore.isHome()`
(`store/SkillStore.java:155-157`) → `LaunchEnv.looksLikeStoreRoot`
(`launch/LaunchEnv.java:326-330`), which is a layout test (descriptor file, or the
`installed/` + `skills/` pair) rather than a name test — chosen precisely because a
home is routinely cloned to a differently named directory.

The shim derives the home from **its own location**, relatively —
`src/main/java/dev/skillmanager/launch/LauncherShims.java:579-580`:

```sh
self_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
home="$(cd -- "$self_dir/../.." && pwd -P)"
```

then `export SKILL_MANAGER_HOME="$home"` at `LauncherShims.java:663`. A fixed
`../..`, not a search. This is the property that makes a home relocatable at all.

There *is* an upward walk, and it is Python —
`skill-publisher-skill/src/skt/homes.py:49-60` (`find_home`): env, then an ancestor
probe for `.skill-manager/installed` or `home.runtime.json`, then `root_home()`.
It stats, and it returns `None` when `~/.skill-manager` is absent. That divergence
is real but it belongs to tier derivation, which is where it does damage; see §2.

### What happens in a container

Nothing. `$SKILL_MANAGER_HOME` is an env var, `$HOME` is whatever the container
sets, the shim computes its home from `BASH_SOURCE`. There is **no** network
discovery path — refuted by grep: `HttpClient` / `URI.create` in `store/`, `agent/`,
`sandbox/`, `policy/` returns only `store/Fetcher.java:61,66` (expanding
`github:owner/repo` into a clone URL, which is unit fetching, not home discovery)
and a commented-out example at `policy/Policy.java:152`.

### Disposition: **unaffected**

Positive evidence, not absence of evidence: the resolver is two lines, the shim
derivation is relative by construction and was measured to contain no absolute path
at all (§3), and the "is this a home" test is structural rather than positional.

---

## 2. Tier derivation — **needs-change**

### What it is

Deciding whether a home is the operator **root**, a **project** home, or a
**worktree** home.

### The lead, verified

**Confirmed: there is no Java three-way tier classifier.** `grep -rn "enum .*Tier"`
over `src/main/java` → zero hits. `grep -rn '"worktree"'` over `src/main/java` →
zero hits. `grep -rn "git-common-dir|worktree list"` over `src/main/java` → zero
hits: **Java cannot tell a linked worktree from a main checkout.** Every occurrence
of the word "tier" in `src/main/java` is javadoc prose.

Java has exactly the two binary tests the lead named.

**(a) `HomePolicy.isRootHome`** — `src/main/java/dev/skillmanager/policy/HomePolicy.java:195-201`:

```java
public static boolean isRootHome(SkillStore store) {
    if (store == null) return false;
    Path root = AgentHomes.userHome().resolve(".skill-manager");
    return normalize(store.root()).equals(normalize(root));
}
```

A normalized equality against `~/.skill-manager` — not a prefix test. `normalize`
(`:204-211`) tries `toRealPath()` and falls back to the lexical path for a home that
does not exist yet. Its sole consumer is `lazyArtifactsDefault` (`:190-193`). The
class comment at `HomePolicy.java:177-184` says outright that this is deliberately
*skt's* `classify_tier` first comparison and not a second notion of tier.

**(b) `Confinement.perCheckoutHomeRoot`** — `src/main/java/dev/skillmanager/sandbox/Confinement.java:193-206`.
Also **not** a prefix comparison: the store's basename must literally be
`.skill-manager`, take its parent, and reject when that parent equals `$HOME`. It
answers "is this home inside a checkout", lumping project and worktree together —
the complement of `isRootHome`, computed by a different route.

**The only three-way classifier is Python** —
`skill-publisher-skill/src/skt/context.py:53-61`:

```python
def classify_tier(home: Path, root: Path) -> str:
    try:
        if home.resolve() == root_home().resolve():
            return "root"
    except OSError:
        pass
    if is_linked_worktree(root) and _inside(home, root):
        return "worktree"
    return "project"
```

All three inputs are machine-local: `root_home()` is `$HOME/.skill-manager`
(`homes.py:63-66`); `is_linked_worktree` shells out to `git rev-parse --git-dir` vs
`--git-common-dir` (`context.py:37-40`); `_inside` is one-disk path containment
(`context.py:64-69`).

And the parent-finder is Python too —
`skill-publisher-skill/src/skt/publish.py:38-67` (`_parent_home`): for `worktree` it
reads `git worktree list --porcelain` and requires `<main tree>/.skill-manager` to
`is_dir()`; for `project` it requires `~/.skill-manager` to `is_dir()`. Java by
contrast never derives a parent — `home close-out` requires the operator to name
both (`commands/HomeCommand.java:1378-1383`, both `required = true`).

### What happens in a container — MEASURED, not predicted

A container gets a checkout by `git clone`, not by `git worktree add`, and does not
carry `~/.skill-manager`. Both halves of that were run against the real code:

```
$ python3 -c 'from skt import context, publish; ...'   # SKT_ROOT_HOME=/nonexistent-root-home/.skill-manager
checkout_root      : .../scratchpad/sub0/container
is_linked_worktree : False
classify_tier      : project
_parent_home       : (None, 'operator root home not found at /nonexistent-root-home/.skill-manager')
```

Two distinct defects fall out:

1. **The `worktree` tier is unreachable in a container that clones.** It is not
   reported as absent; the home is classified `project`, which is a *different tier
   with different rules*, silently.
2. **The `project` tier then has no parent.** `skt publish`'s "sync one tier up"
   step has no destination and must fail loudly — which is the correct behaviour of
   `_parent_home` and the wrong outcome for the epic's premise, because publishing
   is the one propagation path that *is* machine-independent.

A third, sharper case: a container that snapshots a *linked worktree* without its
main repository. Its `.git` file points at a gitdir that is not there, so
`is_linked_worktree` returns `False` — the `_git` helper swallows failure and
returns `""` (`context.py:17-30`, `timeout=10`). Measured:

```
is_linked_worktree : False
classify_tier      : project      # was 'worktree' on the host
_parent_home       : (None, 'operator root home not found at ...')
```

**An orphaned worktree home silently downgrades to `project`.** A silent
misclassification is worse than a failure, because `HomePolicy.lazyArtifactsDefault`
and `Confinement` both read the tier and neither is told it changed.

### Disposition: **needs-change**

The tiers are defined by containment on one disk and by git plumbing that needs the
main repository present. Neither survives. SUB-2 owns the answer to *what* the
tiers should mean; this row records that they currently mean nothing usable in a
container, and that the failure mode is silent for the worktree tier.

---

## 3. `bin/cli` and `bin/launch` shims — **needs-change**

### `bin/launch` is clean

Three files only — `AGENTS = List.of("claude", "codex", "gemini")`,
`src/main/java/dev/skillmanager/launch/LauncherShims.java:113` — written as bash
from a template at `LauncherShims.java:274-310`. **They embed no absolute path at
all.** Home from `BASH_SOURCE` (`:290-291`), CLI as `$home/bin/cli/skill-manager`
(`:295-296`), then `exec "$cli" exec --home "$home" -- <agent> "$@"` (`:309`).
Measured on the operator's real home: `~/.skill-manager/bin/launch/claude` contains
exactly one line matching `/Users/|/opt/|/usr/`, and it is the shebang.

The launch environment is assembled at exec time in Java, never baked:
`LaunchEnv.binPrefix` (`launch/LaunchEnv.java:180-185`), `effectivePath` (`:196-212`),
`exportedEnv` (`:107-111`), with `PATH` inherited from the caller at
`commands/ExecCommand.java:100`.

### `bin/cli` is not

`bin/cli/skill-manager` carries **exactly one embedded absolute path, written twice**
— the CLI pin. `LauncherShims.java:557` normalizes it to absolute; the template
consumes it at `:678-681` and exits 127 if it is not executable (`:683-691`).
Measured on the operator's real home: `~/.skill-manager/bin/cli/skill-manager:180`
reads

```sh
cli="${SKILL_MANAGER_CLI:-/opt/homebrew/bin/skill-manager}"
```

That is a Homebrew prefix on the host. See §6 for the pin's own analysis.

Everything else under `bin/cli/` is written by installer backends, in mixed physical
forms, and **the out-of-home ones stay absolute by design**:

| backend | form | evidence |
| --- | --- | --- |
| brew | symlink into `$(brew --prefix)`; copy fallback | `cli/installer/BrewBackend.java:63-78` |
| npm | symlink into `<home>/npm/...`; copy fallback | `cli/installer/NpmBackend.java:66-79` |
| pip | whatever `uv tool install` writes, later relativized | `cli/installer/PipBackend.java:52-55` |
| tar | copy of the extracted binary | `cli/installer/TarBackend.java:76-78` |
| skill-script | generated bash wrapper with absolute `exec` targets | `cli/installer/SkillScriptBackend.java:184-195` |

`InstallerRegistry.java:186` calls `HomeLinks.relativizeShims(store)` after every
install, and `store/HomeLinks.java:106-108` rewrites **only** links whose target
`isInsideHome`. `store/HomeLinks.java:23-26` states the policy: links that leave the
home stay absolute. Measured in the operator's real home:
`bin/cli/skill-dev -> ../../cache/uv-tools/skill-dev/bin/skill-dev` (relative,
in-home) beside `bin/cli/tofu -> /opt/homebrew/opt/opentofu/bin/tofu` (absolute,
outside).

The quantity is recorded in-tree, and it is the single most important number in this
survey — `store/HomeCloner.java:2610-2615`:

> Measured across the seven onboarded project homes, **14–18 links per home resolve
> outside it and every one of them is a toolchain binary.**

**A home is not self-contained.** It depends on 14–18 host paths that must exist at
the same absolute location, and the code says so on purpose, because a rule that
fires on all of those "is a rule nobody can keep, and a rule nobody keeps is
switched off."

A clone does re-anchor *wrapper bodies* by raw byte substitution of the home root
(`store/HomeCloner.java:1391-1435`, with a `SHEBANG_TOO_LONG` refusal at `:1418-1425`)
— but that rewrites `<srcHome>/...` to `<dstHome>/...`. It does nothing for
`/opt/homebrew/...`, which is the class that breaks in a container.

Platform conditionality sits in the installers, not the shims. Shim generators emit
`#!/usr/bin/env bash` unconditionally (`LauncherShims.java:275`, `:561`); no
`.bat`/`.cmd` path exists; `Platform.Os.WINDOWS` (`util/Platform.java:17`) is defined
and never consumed by shim generation. The backends *are* platform-keyed:
`BrewBackend.java:29-34`, `TarBackend.java:241-253`, `SkillScriptBackend.java:393-402`,
`pm/PackageManager.java:53-56` (throws on Windows).

### Disposition: **needs-change**

`bin/launch` is unaffected and should be recorded as a success of the relocatability
design. `bin/cli` is not: a home moved into a Linux container keeps a macOS Homebrew
pin and 14–18 absolute toolchain links, and the existing repair (`sync --force-scripts`
re-provisioning from `cli-lock.toml`) is the right shape but has never been asserted
to work when the *platform key changes* — `util/Platform.currentKey()` is `os-arch`
and `TarBackend.java:136-141` says two homes on different architectures deliberately
do not agree.

---

## 4. Home sync — **needs-contract**

### How it finds the parent: it does not

Both ends are required flags — `src/main/java/dev/skillmanager/commands/HomeCommand.java:1281-1285`:

```java
@Option(names = "--from", required = true, description = "Home to read from. Never written.")
Path from;
@Option(names = "--to", required = true, description = "Home to reconcile. Written unless --dry-run.")
Path to;
```

and they are absolutized against the **process CWD** at `HomeCommand.java:1311-1312`.
There is no upward walk, no env fallback, and — checked across every
`HomeProvenance` consumer in `src/main/java` — **`clonedFrom` is never used to pick a
sync target**. `HomeCloseOut.inspect` likewise takes both stores as arguments
(`store/HomeCloseOut.java:140,154`) and echoes the pair back into the remedy string
it prints (`:329-330`).

So every home-to-home relation on this surface is a machine-local absolute path,
either typed by an operator or echoed from one.

### The lead, verified with a line correction

`clonedFrom` **must be absolute; a relative spelling is refused, not resolved.** The
validation is `HomeProvenance.recordedSource`,
`src/main/java/dev/skillmanager/store/HomeProvenance.java:230-240`, and the refusal is
one line — **`HomeProvenance.java:239`**, not `:246` as the lead had it:

```java
return path.isAbsolute() ? path.normalize() : null;
```

with the reason at `:224-228` (a relative path would resolve against the process
working directory, so one record would name different homes depending on where the
command was typed).

Two things the lead's phrasing understates and this survey should not:

- The refusal is **silent**. `recordedSource` returns `null`; `rederive` (`:207-217`)
  then returns `false`. A malformed descent does not raise — it simply sanctions
  nothing.
- The record is written verbatim absolute — `HomeProvenance.java:280,284`. Measured
  in the clone fixture: `home.provenance.json` holds
  `"clonedFrom": "/private/tmp/.../srchome/.skill-manager"`. **A home carries the
  absolute path of the machine it was cloned on.**

### The transfer is local filesystem copy

`HomeSync.run` delegates every byte to `ChildHomeMaterializer`
(`store/HomeSync.java:261-266`); the write is
`Files.copy(..., REPLACE_EXISTING, COPY_ATTRIBUTES)`
(`bindings/ChildHomeMaterializer.java:3195-3196`) and the swap is `ATOMIC_MOVE`
(`:2832-2846`) — a **same-filesystem primitive**. The staging directory it moves from
lives inside the destination home (`STAGING_DIR`, `:220`), so atomicity holds while
`<dest>/.materialization/tmp` and `<dest>/skills/...` are one filesystem, and is
undefined if any subtree is a separate mount.

`home clone` is the same shape — `Files.walkFileTree` + `COPY_ATTRIBUTES`
(`store/HomeCloner.java:766-820`), where `COPY_ATTRIBUTES` is explicitly the APFS
`clonefile(2)` block-sharing path (`:804-816`) — i.e. a same-volume optimization by
design. Git is invoked only for `CHECKOUT`-mode units, never for the reconcile.

The nesting refusal is a lexical prefix test in both directions —
`store/HomeCloner.java:579`.

### `HomeLock` cannot arbitrate two containers

Two mechanisms and **nothing written to the lock file**: a per-JVM `ReentrantLock`
keyed by the path *string* (`store/HomeLock.java:70,134,165`) and an advisory
`FileChannel.tryLock` (`:186-193`). No pid, no hostname, no boot id — the javadoc
confirms a zero-byte artifact (`:105-107`). The class states its intended scope as
"writers in other processes" (`:47-49`), which is a same-host claim.

Two consequences for a shared volume:

- Only the `FileLock` arm applies across containers, and `fcntl` locking over
  NFS/overlayfs/bind-mounts is unreliable or a no-op. Nothing in the code probes it,
  and nothing degrades if `tryLock` succeeds on both sides.
- The dry-run fast path treats the lock file's *absence* as positive evidence that
  "no process **anywhere** holds this home's lock" (`:111-117,133`). That sentence is
  true for one coherent filesystem view and false for two.
- The in-process key is only `toAbsolutePath().normalize()`, never `toRealPath()`
  (`:132,160`), so two spellings of one bind-mounted home produce two distinct locks.

### Disposition: **needs-contract**

Nothing here is *broken* by a container — a sync between two homes on one mounted
volume works exactly as it does today. What is missing is a written precondition:
**home sync is defined only between two homes visible in one filesystem namespace
with coherent advisory locking, and both paths must be named explicitly.** State
that, and no code changes. Leave it unstated and someone will run two containers
against one volume and get an interleaved write with no error.

---

## 5. Child materialization — **needs-change**

### The three modes, and what each writes

`bindings/MaterializationMode.java` — `LINK`, `COPY`, `CHECKOUT`, per unit, recorded
by name. The default is `COPY`
(`project/ProjectChildHomeScaffolder.java:38`: `DEFAULT_MODE = MaterializationMode.COPY`).

- **`LINK`** — `Files.createSymbolicLink(dest, source)` at
  `bindings/ChildHomeMaterializer.java:2141`, where `source` is an **absolute** path
  into the parent store. Every write through the child home lands in the parent.
- **`COPY`** — `Files.copy(..., COPY_ATTRIBUTES)` at `:3195-3196`. The comment at
  `:2146-2153` is explicit that this is the APFS clone path and that the number is
  load-bearing: *"0.01 MB with the flag, 67.11 MB without, for one 64 MB file …
  a copy-per-worktree model is only affordable because these copies share blocks."*
  Block sharing requires one volume.
- **`CHECKOUT`** — `GitOps.clone(dest, source.toString(), null)` at `:2209`, cloning
  **the parent store's own local checkout**, with the javadoc at `:2159-2166` saying
  so: *"The clone source is the parent store's own checkout, not the unit's remote
  URL … cloning it needs no network."* Origin is then re-pointed at the real remote
  when the parent has one.

### Shim mirroring is always an absolute symlink, whatever the mode

`ChildHomeMaterializer.mirrorExistingShim` (`:2068-2085`) — *"Always a symlink,
independent of the unit materialization mode."* — takes
`Path from = source.toAbsolutePath().normalize()` (`:2070`) and calls `linkPath`
(`:2084`). Driven for every CLI and MCP dep of every unit the child holds:
`project/ProjectChildHomeScaffolder.java:320-336`.

So **a child home's `bin/cli/<dep>` is an absolute symlink into the parent home**,
and the parent's entry is itself frequently an absolute symlink into a host
toolchain (§3). Two hops, both absolute, both outside the child.

`ChildHomeRegistry` records the relationship asymmetrically and correctly by the
`HomePaths` rule — `parentHome` encoded, `childHome` **verbatim absolute**
(`bindings/ChildHomeRegistry.java:74-83`, with the rationale at `:19-27`). The parent
home therefore holds the absolute host path of every child home on the machine.

### The `ChildHomeMaterializer:2774` lead — verified, and reframed

**Confirmed factually.** `bindings/ChildHomeMaterializer.java:2774` writes

```java
source == null ? null : source.toString(),
```

through `BindingJson.MAPPER`, which `bindings/BindingJson.java:26-33` documents as
the *verbatim* mapper "kept for records that live outside a home". `HomePaths` is
referenced nowhere in `ChildHomeMaterializer.java` — grep returns zero hits — and
`MaterializationRecord.source` is a `String` field (`:409`), so the mapper's `Path`
serializer would not fire even if the anchored mapper were used.

**But the framing "bypasses the encoding every other record uses" is wrong, and the
real finding is more interesting.** Under the `HomePaths` rule, a source pointing
into the *parent* store is outside the *child* home and would stay verbatim anyway.
Tokenizing it would be incorrect.

The finding is what that verbatim string is **used for**.
`bindings/ChildHomeLink.childWasMaterializedFrom` (`:139-162`) reads
`record.source()` and asks `realized(Path.of(source)).startsWith(parentReal)`
(`:154`). That is the **only** half of `isChildOf` that works without the parent home
being present — the other half, `parentClaims` (`:100-122`), opens
`<parent>/child-homes/` and returns `false` if the directory is not there.

So: **an absolute host path string, written raw, is the load-bearing evidence for a
security-relevant isolation decision.** In a container where the parent home is
mounted at a different path, `startsWith(parentReal)` fails, `isChildOf` returns
false, `HomeProvenance.sanctions` returns false, and every inherited shim reads as an
unsanctioned foreign-home reference.

### That failure mode is already measured — on this machine, not in a container

`store/HomeProvenance.java:27-44` records it, from HIS-10 / issue #227, on one cloned
home carrying five inherited `bin/cli` shims:

```
home clone                        clean — "no path in it reaches another home"
home verify --home <clone>         exit 1 — 5x FOREIGN_HOME
home verify --home … --against …  exit 0 — "5 sanctioned parent-store shim(s)"
sync (CliShimPruner)              PRUNES all five, then re-provisions ~90s
```

The container case is the *same* case: `rederive` (`:207-217`) reads the parent home
at the recorded absolute path, and `read(home)` returning `null` for an absent parent
yields `false` at `:210`. **Predicted, not observed:** a worktree home whose parent is
not in the container has its inherited shims pruned and re-provisioned on the next
sync — which is the ~90s number above, per home, per launch. SUB-5 should check it.

I did attempt the direct observation and it was inconclusive rather than confirming,
which is worth saying plainly: cloning a scratch home and then removing the source
left `home verify` at exit 0 — but that fixture had no inherited shims, so it tests
nothing about the shim case. Recorded so nobody reads it as support.

### Disposition: **needs-change**

`COPY` degrades gracefully (a real copy instead of a reflink — slower, correct).
`LINK` and `CHECKOUT` do not: both name the parent store by absolute local path, and
shim mirroring names it unconditionally. The isolation *verdict* about those links
also depends on an absolute path string surviving relocation, which it does not.

---

## 6. CLI pins — **needs-change**

Two different things share the word. Keep them apart.

### (a) `cli-lock.toml` — no absolute paths, but machine-local fingerprints

`lock/CliLock.java:41` (`FILENAME`), record shape at `:70-80`:
`backend, tool, version, spec, sha256, requestedBy, installedAt, binary, fingerprint`.
`binary` is a **basename**, not a path (`lock/CliInstallRecorder.java:121-127`);
`requested_by` are unit names; `installed_at` is an ISO instant. **No field holds an
absolute path.** This is the mechanism that makes re-provisioning after a clone
possible at all, and it is well-shaped for a container.

The fingerprint *values*, however, are digests over things read off this host's disk:
brew reads `<prefix>/opt/<formula>` with the prefix derived from whoever's `PATH`
(`cli/installer/BrewBackend.java:112-139`); pip walks `<home>/venvs/.../dist-info`
(`PipBackend.java:112-129`); npm reads `<home>/npm/.../package.json`
(`NpmBackend.java:125-136`); tar hashes the installed bytes *plus the platform key*
(`TarBackend.java:157-173`, with `:136-141` stating that two architectures
deliberately disagree); skill-script hashes the whole `skill-scripts/` tree
(`SkillScriptBackend.java:271-287`).

So the same `cli-lock.toml` in another container re-fingerprints differently, and
rows recorded `resolved` on one host degrade to `declared` on another. That is
**correct behaviour** — the lock is a declaration and the fingerprint is an
observation — but it means a drifted-fingerprint report after relocation is expected
noise, and nothing says so.

### (b) The CLI pin — one absolute host path, and it travels

Not a version, not a hash: one line's shape, and that shape is a cross-tool contract.
`launch/LauncherShims.java:708` (`PIN_PREFIX = "cli=\"${SKILL_MANAGER_CLI:-"`),
`:504` (`PIN_MARKER = "skill-manager:cli-pin"`), reader at `:731-782`. It is written
absolute at `:557` and the class concedes the consequence at `:56-61`: copying a home
to another **machine** needs one command to re-pin.

`DurableCliPin` exists to make it survive a *package-manager upgrade*, not a machine
move. "Durable" means the spelling of the same file carrying no version segment —
`launch/DurableCliPin.java:272-274` (`forPin`), `:289-344` (`choose`), version regex
at `:228-236`, Homebrew keg decomposition at `:400-412`. Its decisive gate is
`Path#toRealPath` identity with the located build (`:318`, `:454`), and its rule is
*"prefer no substitution over an unsafe one"* (`:118-121`). Every gate stats the
local filesystem. The failure it was built for is quoted at `DurableCliPin.java:24-31`:
`/opt/homebrew/Cellar/skill-manager/0.23.0/libexec/bin/skill-manager` deleted by
`brew upgrade`, with `home verify` still exit 0.

**Measured in the clone fixture.** A freshly cloned home's `home.runtime.json` reads:

```json
"cli" : { "skillManager" : "/opt/homebrew/Cellar/skill-manager/0.25.1/libexec/bin/skill-manager" }
```

A versioned Homebrew keg path, carried into the clone unrewritten. The clone's
success line printed ✓ — the isolation oracle does not consider it a leak, correctly,
because it is not a reference back to the *source home*. It is a reference to the
*host*, which is a category the oracle does not model.

### Disposition: **needs-change**

The pin is the one thing in a home that names a build outside it, it is required for
the home to run at all (`LauncherShims.java:683-691` exits 127 when it is not
executable), and it survives every relocation mechanism the codebase has. A container
needs either an image-provided CLI at a known path or an explicit re-pin step, and
neither is currently a defined part of moving a home.

---

## 7. Agent projections — **needs-contract**

### The lead, verified

**Confirmed: agent dirs are siblings of `.skill-manager`, at exactly the cited line.**
`src/main/java/dev/skillmanager/agent/AgentHomes.java:288-292`:

```java
public static java.util.List<Path> agentDirsUnder(Path homeRoot) {
    Path root = homeRoot.toAbsolutePath().normalize();
    return java.util.List.of(
            root.resolve(CLAUDE_DIR_NAME), root.resolve(".codex"), root.resolve(".gemini"));
}
```

with `homeRootFor` (`:272-280`) stripping the trailing `.skill-manager` segment. So
`<X>/.skill-manager` → `<X>/.claude`, `<X>/.codex`, `<X>/.gemini`.

A **second** layout exists for named project profiles —
`project/ProjectChildHomeScaffolder.java:305-307` puts them under
`<profileRoot>/agents/{claude,codex,gemini}` — and `agentDirsUnder` does not cover it
(flagged as an exception at `AgentHomes.java:268-270`).

### Projections are absolute symlinks

`project/Projector.java:67-79` — `Files.createSymbolicLink(target, source)` with a
`Fs.copyRecursive` fallback; same shape in the effects path at
`effects/LiveInterpreter.java:2048-2064` and `:2454-2456`. The source is
`store.unitDir(...)` (`project/ClaudeProjector.java:56-58`) — an **absolute** path,
not relativized at creation. `bindings/ProjectionKind.java:10` names it as the
default for skills and plugins. There are 21 `createSymbolicLink` sites in
`src/main/java` and **no `Files.createLink`** anywhere: no hardlinks.

### The ledger stores `destPath` verbatim — correctly

`HomePaths` encodes only paths under the **store** root
(`store/HomePaths.java:164-168`, a lexical `startsWith`). Because agent dirs are
siblings of `.skill-manager` and not inside it, `<X>/.claude/skills/<unit>` does not
match, so `destPath` is written verbatim absolute — even for the home's *own* agent
tree. `HomePaths.java:22-26` states this as intended.

`HomePaths` deliberately does **not** canonicalize candidates (`:143-162`), and the
reason is a real data-loss defect: resolving a projection symlink classified
`~/.claude/skills/<unit>` as a self-reference, and `unbind` then deleted the store
copy.

### The repair already exists, and it is clone-time

`store/HomeCloner.remapPath` (`:2829-2853`) re-roots both the store span **and** the
three sibling agent dirs, reading `AgentHomes.agentDirsUnder` at `:2845-2851`.
Bindings that still name anything outside are dropped whole (`:1297-1324`). Its
javadoc at `:2802-2804` says plainly that the `HomePaths` rule *"is right for a home
being written in place and wrong for a home being copied, and issue #145 is the
difference."*

Measured in the clone fixture — `home.runtime.json` in the clone:

```json
"homeRoot" : "$SKILL_MANAGER_HOME/..",
"env" : { "CLAUDE_CONFIG_DIR" : "$SKILL_MANAGER_HOME/../.claude", ... }
```

The descriptor's own travelling encoding (`store/HomeDescriptor.java:326-343`) covers
exactly the store and its parent, and bounds itself there on purpose (`:275-281`):
walking further up would silently repoint an unrelated path.

### Projections write outside the home, and confinement does not gate them

`store/WriteConfinement.java:92-98` — roughly a dozen of the 58 `SkillEffect`s write
outside the home *as their entire purpose*, `MaterializeProjection` among them.
`:118-130` names all four enforcement sites and no projection site is one of them;
the default is `UNCONFINED` (`:190-192`). `ProjectionOwnership.java:147` says it
outright: *"nothing is refused for being outside the home."*

### Disposition: **needs-contract**

The mechanism is sound and the clone-time repair covers the sibling layout. What is
owed is a stated precondition rather than a code change: **a home's projections are
valid only where the home root and its three sibling agent dirs sit at the paths the
descriptor names, and a home that is moved rather than cloned has had no repair
pass run.** Two specific gaps to write into the contract:

- The `agents/<name>` profile layout (`ProjectChildHomeScaffolder.java:305-307`) is
  not covered by `agentDirsUnder`, so `remapPath` (`HomeCloner.java:2845`) does not
  re-root it. I could not determine which way that resolves —
  `insideNewHome` (`HomeCloner.java:1191-1192`) accepts anything under
  `homeRootFor(dstRoot)` and `agents/` sits under the *profile* root; recorded as
  DEF-SUB0-003 rather than guessed.
- `rsync`ing or `docker cp`ing a home instead of cloning it skips `remapPath`
  entirely. `home verify` is the documented oracle for exactly that
  (`commands/HomeCommand.java:166-168`: *"Useful against a home cloned by other means
  (an `rsync`, a container image build) before trusting it"*), and nothing enforces
  running it.

---

## 8. Credentials — **needs-change** (issue #281 settled)

### Where the token lives

`registry/AuthStore.java:40` — `FILENAME = "auth.token"`; `:107` —
`store.root().resolve(FILENAME)`. So `<store>/auth.token`, where `<store>` is the
`.skill-manager` directory itself. Contents `access_token`, `refresh_token`,
`expires_at` (`:92-96`). Written by `commands/LoginCommand.java:92-93` and, silently
on refresh, by `registry/RegistryClient.java:469-471`.

Permissions are 0600 **after** creation, best effort — `AuthStore.java:96-100` writes
first at the process umask, then narrows, and swallows
`UnsupportedOperationException` on a non-POSIX filesystem with no warning.

### The lead was right about the mechanism; the answer is now measured

`store/HomeCloner.java:165-166` — `SKIPPED_ROOT_FILES = Set.of("audit.log",
"gateway.log", "gateway.pid")`, three entries, one-line javadoc, no security framing.
(The issue cites `:170`; on this branch it is `:165-166`.) The deciding predicate is
`isSkipped` at `:2777-2790`, and for `rel = "auth.token"` every branch is false.

**Settled by demonstration, not by reading.** A scratch home holding a fake
`auth.token` (0600) and an `audit.log` was cloned with the released 0.25.1 CLI:

```
$ skill-manager home clone --from <src>/.skill-manager --to <dst>/.skill-manager
  copied: 8 dirs, 1 files, 0 links (110 bytes; cache, logs, npm, tmp, tools, venvs skipped)
✓ cloned home to <dst>/.skill-manager — checked: nothing in it resolves back to <src>…

$ ls -la <dst>/.skill-manager/auth.token
-rw-------  1 hayde  wheel  110  auth.token
$ cat  <dst>/.skill-manager/auth.token
{"access_token":"FAKE.ACCESS.TOKEN","refresh_token":"FAKE.REFRESH.TOKEN",...}
$ ls    <dst>/.skill-manager/audit.log
ls: ...: No such file or directory
```

**`auth.token` is copied verbatim into every clone, with 0600 preserved by
`COPY_ATTRIBUTES`.** `audit.log` is the control: it *is* in `SKIPPED_ROOT_FILES` and
it did not travel, which confirms the skip list is the operative mechanism and that
`auth.token`'s absence from it is the whole reason.

The sharper half of the finding is the line above the token: **the isolation oracle
printed ✓ while the credential travelled.** `home clone`'s check is "does anything in
the copy reach back into the source home or into another Skill Manager home"; a
copied secret is not a reference, so the instrument that exists to say a copy is
independent is silent about it by construction.

Two follow-ons the issue does not mention:

- `auth.token` classifies as `Surface.STATE` (`HomeCloner.java:2732-2733`: any
  root-level file is skill-manager's own configuration), so it is also fed to
  `reanchorRemainingState`'s byte-substitution pass (`:1228-1261`). Harmless for a
  JWT, but the credential file is read whole and rewritten.
- `RegistryClient.java:471` refreshes the token **in place**, so a cloned home holds
  a live refresh token that keeps working after the two homes diverge.

`ChildHomeMaterializer` does not touch it either way: it is per-unit, walking only the
four unit-kind roots (`:899-919`), so root-level home files are never in scope.

No test in `src/test` references `auth.token` or `AuthStore` — so #281's "no test
asserting either way" still holds, and closing it should land one.

### Other credential-adjacent material a clone carries

- `gateway-data/` — `mcp/GatewayRuntime.java:38`. It is in `PROVISIONED_ROOTS`
  (`HomeCloner.java:248`) but **not** in `SKIPPED_DIRS`, so it is copied. It holds
  `dynamic-servers.json`, and MCP server records carry `env` maps whose fields may be
  declared `secret = true` (`model/McpDependency.java:216`, `model/SkillParser.java:272`,
  `mcp/InstallResult.java:37`). `mcp/McpRegistrationLock.java:72-75` says a plaintext
  secret never enters the *digest*, which implies plaintext secrets exist somewhere in
  the payload. **Could not determine** whether they are on disk — that file is written
  by the Python gateway, outside `src/main/java`. Recorded as DEF-SUB0-002.
- `registry.properties` (`registry/RegistryConfig.java:21,47-53`) — not secret, but it
  pins the clone at the same registry the copied token authenticates to.
- Not carried: git credentials, `~/.ssh`, `~/.netrc` are delegated to the system
  (`store/Fetcher.java:153-154`), and no `GH_TOKEN` / `GITHUB_TOKEN` /
  `ANTHROPIC_API_KEY` is read or persisted anywhere in `src/main/java`.
- Not carried: agent login state. The `.claude`/`.codex`/`.gemini` dirs are siblings
  of the store and outside `home clone`'s source tree entirely
  (`commands/HomeCommand.java:96`). That matches #263's framing as a separate axis.

### Disposition: **needs-change**

Every project home and every worktree home is a clone. A registry credential in every
one of them is a blast radius nobody decided on — `HomeCloner.java:164`'s javadoc has
no security reasoning at all, so this is a decision that was **never made**, not one
made and documented. It becomes materially different in a container, because a home
baked into an image or snapshot-restored carries the token wherever the image goes.

The decision is the owner's and this ticket does not make it. Both answers are
defensible: a child home arguably *should* inherit registry auth. What is not
defensible is that the answer is currently an omission from a three-element set.

---

## 9. The drift digest — **unaffected**

### What is digested

`store/HomeDigest.compute` (`:130-148`) walks only units that *parse*
(`store.listInstalledUnits()`), and the digest input is built by
`bindings/ChildHomeMaterializer.fingerprintOf` (`:3324-3366`) with the framing helper
at `:3373-3378`. The inputs are: entry kind (`D`/`L`/`F`/`X`), the **relative** path,
the byte length, and either file contents or the symlink target string.

**No mtime, no ctime, no inode, no device, no uid/gid, no permission mode, no OS
name, no hostname.** (Grep confirms: `getHostName` / `InetAddress` / `machine-id` /
`boot_id` return zero hits across `src/main/java` — a home carries no host identity
token at all, which is why relocating one is even plausible.)

Ordering is deterministic at three levels: entries sorted before hashing
(`ChildHomeMaterializer.java:3327-3328`), a `TreeSet` in the per-unit rollup
(`HomeDigest.java:168-175`), and units sorted before the home rollup (`:147`). The
directory listing itself is `listSorted` (`:3123,3146`). Sorting is UTF-16 code-unit
order, locale-independent.

The record lives at `<store>/home.digest.json` (`HomeDigest.java:59,209-211`) and the
gate at `<store>/home.drift.json` (`store/DriftGate.java:65,82-84`) — both inside the
home, so both travel with it, and `HomeCloner.rebaselineDrift` (`:754-757`) deletes
the gate and recomputes the digest against the copy's own content. Measured: the
clone fixture's `home.digest.json` was written fresh, and the console said *"a clone
is not drifted."*

### The two caveats, recorded rather than rounded away

1. **The executable bit is in the hash** — `ChildHomeMaterializer.java:3349`
   (`entry.executable() ? "X" : "F"`), from `Files.isExecutable` at `:3135,3157`.
   That is an *effective-access* check: it depends on the calling uid/gid and on
   mount options. The same bytes on a `noexec` mount, on a filesystem without a
   permission bit, or read as a different user, digest differently. No test or
   comment acknowledges this as a cross-host hazard.
2. **Absolute symlink targets are hashed verbatim** — `:3142` reads the raw
   `readSymbolicLink` and `:3340` hashes it as-is, with no relativization on the
   digest path. `HomeLinks.relativizeShims` only touches `<home>/bin/`, which the
   digest does not cover. So an absolute link *inside a unit directory* puts a
   machine-absolute path into the hash. By policy such links stay absolute
   (`HomeLinks.java:23-26`).

A third, minor: `HomeDigest.java:147` orders units with `compareToIgnoreCase` and I
found no tie-breaker, so two units differing only by case would order differently on
a case-sensitive vs case-insensitive filesystem. Unreachable in practice — unit names
are the installed-record keys — but recorded.

### Disposition: **unaffected**

The digest is content-addressed over sorted relative paths and is reproducible on
another machine for any unit that holds no absolute in-unit symlink and is read with
the same effective exec bits. Both caveats are *consequences* of surfaces 3 and 5
rather than defects of the digest: fix the absolute links and the digest is exactly
reproducible. It is the one instrument in this survey that was already built for a
world where the home moves.

One gap worth stating, though it is a coverage gap rather than a machine-local one:
`DriftGate.recordSince` is invoked from exactly two places —
`project/ProjectSyncUseCase.java:156` and `commands/HomeCommand.java:1191`.
**`home sync` does not record drift at all** (no `DriftGate` reference in
`HomeSync.java`), and no `HomeLock` is taken on any `DriftGate` path, so two
processes sharing a volume can interleave writes to `home.drift.json`. Recorded as
DEF-SUB0-001.

---

## Leads: what was confirmed, corrected, and refuted

| lead | verdict |
| --- | --- |
| No Java three-way tier classifier; `skt`'s is the only one; Java has `HomePolicy.isRootHome` and `Confinement.perCheckoutHomeRoot` | **Confirmed**, with the added facts that neither is a prefix test and that Java cannot detect a linked worktree at all. The classifier is at `skill-publisher-skill/src/skt/context.py:53` — in *this* repo, not only in the external checkout. |
| `HomeProvenance.java:246` requires `clonedFrom` absolute, refuses relative | **Confirmed, line corrected to `:239`** (method at `:230-240`). Refined: the refusal is **silent** — `recordedSource` returns `null` and `rederive` returns `false`. Nothing is raised. |
| `ChildHomeMaterializer.java:2774` writes `source` with the raw mapper, bypassing the `$SKILL_MANAGER_HOME` encoding every other record uses | **Fact confirmed, characterisation refuted.** The raw write is real (and `HomePaths` appears nowhere in that file). But a parent-store source is *outside* the child home, so the `HomePaths` rule says verbatim is correct and tokenizing would be wrong. The real finding is that this verbatim absolute string is the load-bearing evidence for `ChildHomeLink.childWasMaterializedFrom` (`:154`) — the only isolation check that works without the parent present. |
| `HomePaths.java:14` states the tokenize-vs-verbatim rule; every absolute path a home carries points outside it | **Confirmed** (passage at `:13-19`; `:14` is the `<h2>` heading line). Used as this survey's organising boundary. |
| `AgentHomes.java:288` — agent dirs are siblings of `.skill-manager` | **Confirmed at exactly that line.** Added: a second, uncovered layout exists for named profiles (`ProjectChildHomeScaffolder.java:305-307`). |
| **Unverified:** `registry/AuthStore.java:40` keeps `<store>/auth.token` and it is not in `HomeCloner.SKIPPED_ROOT_FILES` (issue #281) | **Confirmed and settled by demonstration.** Token location and skip list both as described (skip list at `:165-166`, not `:170`). A clone was run: the token travels, 0600 preserved; `audit.log` as a control did not. The isolation oracle printed ✓ regardless. |

Nothing in the lead set was wrong on its facts. Two were wrong on line numbers and
one was wrong on what its fact *means*, which is the useful correction.

---

## Cross-cutting findings the nine surfaces do not cover

Recorded here rather than silently folded into a row, because the denominator is
fixed at 9 and these are not among them.

1. **The gateway URL is a loopback port, and it travels.** Measured in the clone
   fixture: the clone's `gateway.properties` and `home.runtime.json` both hold
   `http://127.0.0.1:51717` with `owned=false`, i.e. the clone points at the *source*
   home's gateway process. In a container, `127.0.0.1` is the container's own
   loopback: the clone attaches to nothing, or to an unrelated process on that port.
   → DEF-SUB0-004.
2. **`util/Platform.currentKey()` is `os-arch`, and a home does not record it.**
   Nothing in a home says which platform provisioned it. A macOS-authored home moved
   into a Linux container re-provisions from `cli-lock.toml` under a different
   platform key, and `TarBackend.java:136-141` says two architectures deliberately
   disagree about fingerprints. Whether re-provisioning *succeeds* across platforms
   has not been demonstrated. → DEF-SUB0-005.
3. **There is no container story for a home in-tree today.** `Dockerfile.server`
   builds the *registry server* fatjar, not an agent image; it never creates a home.
   The only two places the codebase reasons about containers are
   `pm/PackageCaches.java:66` (a mounted cache on a different filesystem forces
   `copy` link mode — a graceful degradation) and `commands/HomeCommand.java:166-168`
   (`home verify` as the oracle for "a home cloned by other means (an `rsync`, a
   container image build)"). Both are *supportive* evidence for the epic's premise
   that most of this does not change.

---

## What I could not determine

Written down as findings, not rounded to guesses.

1. **Whether `dynamic-servers.json` holds plaintext secrets on disk.** `gateway-data/`
   is definitely copied by a clone; its contents are written by the Python gateway,
   outside `src/main/java`. (§8, DEF-SUB0-002)
2. **Whether the `agents/<name>` profile layout survives a clone.** `remapPath` re-roots
   only `agentDirsUnder`'s three sibling dirs; `insideNewHome` accepts anything under
   `homeRootFor(dstRoot)` and `agents/` sits under the profile root. I did not trace far
   enough to say which way it resolves. (§7, DEF-SUB0-003)
3. **Whether `FileLock` actually excludes across the volume drivers this would run on.**
   That is a runtime property of the filesystem. Nothing in the code probes it and
   nothing degrades if `tryLock` silently succeeds on both sides. (§4)
4. **Whether `Files.isExecutable` divergence has ever been observed.** The mechanism is
   in the code; no test or comment acknowledges it. (§9)
5. **Whether re-provisioning from `cli-lock.toml` works across a platform change.** The
   lock is platform-agnostic by shape but the backends are platform-keyed. Not
   demonstrated either way. (§3, §6, DEF-SUB0-005)
6. **Whether `bin/mcp` ever receives shims.** `HomeLinks.java:130-137` says no writer
   populates it today, but `SkillStore.init()` creates it and `LaunchEnv.binPrefix` puts
   it on `PATH`. The gateway's own binary provisioning was not traced.
7. **Whether an external wrapper derives `home sync --to` from provenance.** The Java
   side is unambiguous (both ends required flags) and no `home sync` invocation exists
   under `skills/`, but the `git-issue-workflow` shell scripts (`bootstrap-home.sh`,
   `close-change.sh`) are not in this repo and could not be read. Java tests reference
   their behaviour as a contract (`src/test/java/dev/skillmanager/launch/LauncherShimsTest.java:513,527,536,629`).
8. **Every container-lifecycle claim marked *(assumed)* above.** SUB-5 exists to
   replace them with observation; where it cannot, this survey's claims must be
   re-labelled as documentation-based rather than left as though they were measured.

---

## Reproducing the two demonstrations

Both write only into a scratch directory. Neither touches a real Skill Manager home.

**Credentials (§8), settling issue #281:**

```sh
SP=$(mktemp -d)
mkdir -p "$SP/srchome/.skill-manager/installed" "$SP/srchome/.skill-manager/skills"
printf '{"access_token":"FAKE","refresh_token":"FAKE","expires_at":"2099-01-01T00:00:00Z"}\n' \
  > "$SP/srchome/.skill-manager/auth.token"
chmod 600 "$SP/srchome/.skill-manager/auth.token"
printf 'audit\n' > "$SP/srchome/.skill-manager/audit.log"

export SKILL_MANAGER_HOME="$SP/srchome/.skill-manager"
/opt/homebrew/bin/skill-manager home clone \
  --from "$SP/srchome/.skill-manager" --to "$SP/dsthome/.skill-manager"

ls -la "$SP/dsthome/.skill-manager/auth.token"   # present, -rw-------
ls     "$SP/dsthome/.skill-manager/audit.log"    # absent (the control)
cat    "$SP/dsthome/.skill-manager/home.runtime.json"  # the CLI pin and gateway URL
```

**Tier derivation (§2):**

```sh
SP=$(mktemp -d); mkdir -p "$SP/container/.skill-manager/installed" "$SP/container/.skill-manager/skills"
git -C "$SP/container" init -q && git -C "$SP/container" commit -q --allow-empty -m init

PYTHONPATH=skill-publisher-skill/src SKT_ROOT_HOME=/nonexistent-root-home/.skill-manager \
python3 -c '
import os; from pathlib import Path
from skt import context, publish
c = Path(os.environ["C"]); home = c / ".skill-manager"; root = context.checkout_root(c)
print("is_linked_worktree:", context.is_linked_worktree(root))
print("classify_tier     :", context.classify_tier(home, root))
print("_parent_home      :", publish._parent_home(home, c))
' C="$SP/container"
# is_linked_worktree: False
# classify_tier     : project
# _parent_home      : (None, 'operator root home not found at /nonexistent-root-home/.skill-manager')
```

Repeat the second with a `.git` *file* naming an absent gitdir
(`printf 'gitdir: /gone/.git/worktrees/x\n' > "$SP/orphan/.git"`) to see a home that
is `worktree` on the host classify as `project` with no error.

---

## Goal contribution

`GOAL-every-machine-local-assumption-is-named` — baseline **0 of 9**, target
**9 of 9 dispositioned, none 'unknown'**.

This ticket's `local_signal` is "every surface in the checklist carries a disposition
and its evidence". It does: 9 rows, each with `file:line` evidence for the call, 2
unaffected / 3 needs-contract / 4 needs-change, none `unknown`. Eight items that
genuinely could not be determined are written down as findings in their own section
rather than absorbed into a disposition.

SUB-6 decides the goal. The two rows most likely to move under SUB-1's demonstrations
are §5 (child materialization — my shim-pruning prediction is *not* observed, and I
say so) and §3 (whether re-provisioning survives a platform change). SUB-1's own
`local_signal` asks for at least one fixture that **contradicts** this survey; §5 and
§3 are where I would look first.
