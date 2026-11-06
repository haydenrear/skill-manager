package dev.skillmanager.lock;

import dev.skillmanager.model.CliDependency;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What a unit DECLARED for one CLI dep, as a digest, and whether that
 * declaration has moved since this home last installed it (#404).
 *
 * <h2>The defect this closes</h2>
 *
 * <p>Before this class, nothing on the install path ever compared a declared
 * version with anything. {@code CliDependency.versionCheck} had no callers;
 * {@code minVersion} reached only the lock's identity and {@code show}; every
 * backend opened with {@link dev.skillmanager.cli.installer.CliPresence}, which
 * asks whether {@code bin/cli/<name>} runs and not which version it is; and
 * {@code PlanBuilder}'s {@code CliVersionConflict} excludes the unit that holds
 * the row, so a unit bumping ITS OWN pin raised nothing. A unit moving
 * {@code pip:cowsay==6.1} to {@code ==7.0} kept 6.1 for as long as the file was
 * executable, and {@code cli-lock.toml} then recorded 7.0 over it.
 *
 * <h2>Why a separate digest and not {@code install_fingerprint}</h2>
 *
 * <p>{@code install_fingerprint} is a backend's account of an artifact and
 * mixes in what it observed on disk ({@code tar-v2} hashes the installed file,
 * {@code pip-v1} the version resolved into {@code venvs/}). Its value therefore
 * changes when the home changes as well as when the declaration does, and its
 * scheme changes with whether the artifact was present at record time — so
 * gating an install on it would reinstall on clone damage, on a system-provided
 * binary, and on every scheme upgrade. That may be a fine rebuild trigger for
 * the {@code build} verb; it is the wrong question here. This digest reads the
 * DECLARATION only, so it moves exactly when a manifest edit does.
 *
 * <h2>Scope</h2>
 *
 * <p>{@link #governs} is {@code pip}, {@code npm} and {@code tar}: the three
 * backends whose spec carries a version a unit can bump. {@code brew} tracks
 * its own formula version (and {@link RequestedVersion} records none for it);
 * {@code skill-script} already re-runs on its own content fingerprint.
 *
 * <h2>Rows written before this column existed</h2>
 *
 * <p>A legacy row has no {@code declared_pin}. It is compared on the two
 * declaration fields it DID record — {@code spec} and {@code version} — so a
 * pip/npm pin bump or a tar {@code min_version} bump is caught on the very
 * first pass after upgrading skill-manager, without reinstalling every CLI in
 * every home. A tar url change with an unchanged {@code min_version} is the one
 * bump a legacy row cannot see; it is caught from the next install on, once
 * the row carries a pin.
 */
public final class CliPin {

    /** Domain-separation prefix. Change what the digest covers → bump the N. */
    public static final String SCHEME = "pin-v1";

    private CliPin() {}

    /** True for the backends whose declaration carries a bumpable version. */
    public static boolean governs(CliDependency dep) {
        if (dep == null) return false;
        return switch (dep.backend()) {
            case "pip", "npm", "tar" -> true;
            default -> false;
        };
    }

    /**
     * The digest of everything a unit declares about {@code dep} that selects
     * WHICH binary gets installed: backend, spec, the requested version, the
     * declared {@code min_version}, and every install target.
     *
     * <p>Every target, in platform-key order, rather than only the one this
     * platform would pick: the choice is a function of the machine, and a digest
     * that depended on it could not be compared across homes on different
     * architectures. The cost is that editing the {@code linux-x64} url also
     * reinstalls on {@code darwin-arm64} once — a redundant download, never a
     * stale binary.
     *
     * <p>Deliberately NOT included: {@code on_path}, {@code version_check} and
     * {@code platform_independent} — they change how a binary is probed, not
     * which binary is fetched.
     */
    public static String of(CliDependency dep) {
        RequestedVersion.Requested req = RequestedVersion.of(dep);
        Fingerprints fp = Fingerprints.scheme(SCHEME)
                .field("backend", dep.backend())
                .field("spec", dep.spec())
                .field("tool", req.tool())
                .field("version", req.version())
                .field("min_version", dep.minVersion());
        Map<String, CliDependency.InstallTarget> sorted = new TreeMap<>();
        for (var e : dep.install().entrySet()) {
            if (e.getKey() != null && e.getValue() != null) sorted.put(e.getKey(), e.getValue());
        }
        for (var e : sorted.entrySet()) {
            CliDependency.InstallTarget t = e.getValue();
            fp.field("target", e.getKey())
                    .field("url", t.url())
                    .field("archive", t.archive())
                    .field("binary", t.binary())
                    .fields("extract", t.extract())
                    .field("sha256", t.sha256());
        }
        return fp.hex();
    }

    /**
     * Why {@code dep}'s declaration no longer matches what {@code row} recorded
     * — a sentence naming the old and new values — or null when it still
     * matches, when there is no row (a first install, which presence decides as
     * before), or when this backend is not {@link #governs governed}.
     */
    public static String moved(CliLock.Entry row, CliDependency dep) {
        if (row == null || !governs(dep)) return null;
        RequestedVersion.Requested req = RequestedVersion.of(dep);
        if (row.declaredPin() != null) {
            if (row.declaredPin().equals(of(dep))) return null;
            if (Objects.equals(row.spec(), dep.spec())
                    && Objects.equals(row.version(), req.version())) {
                return describe(dep.spec(), req.version()) + " now declares a different "
                        + "install target (url, sha256, archive, binary or extract)";
            }
            return "declared " + describe(dep.spec(), req.version())
                    + ", this home installed " + describe(row.spec(), row.version());
        }
        // Legacy row: compare the declaration fields it did record.
        boolean versionMoved = !Objects.equals(row.version(), req.version());
        boolean specMoved = row.spec() != null && !Objects.equals(row.spec(), dep.spec());
        if (!versionMoved && !specMoved) return null;
        return "declared " + describe(dep.spec(), req.version())
                + ", this home installed " + describe(row.spec(), row.version())
                + " (row predates declared_pin; compared on spec and version)";
    }

    /**
     * When {@code unit} moved its pin for a row that other units also claim:
     * a sentence naming the units still on the row's pin, or null when there is
     * nothing to refuse. Null when the pin did not move, when no other unit
     * claims the row, or when every other claimant declares {@code unit}'s new
     * pin in this same pass ({@code declaredThisPass}: unit -> {@link #of}).
     *
     * <p>A claimant absent from this pass still declares what it last installed
     * — the row's pin — so it disagrees. Refusing then is the conservative
     * reading: the alternative reinstalls under a unit whose declaration nobody
     * re-read.
     */
    public static String sharedConflict(CliLock.Entry row, CliDependency dep, String unit,
                                        Map<String, String> declaredThisPass) {
        String why = moved(row, dep);
        if (why == null) return null;
        String mine = of(dep);
        java.util.List<String> holdouts = new java.util.ArrayList<>();
        for (String other : row.requestedBy()) {
            if (other == null || other.equals(unit)) continue;
            if (!mine.equals(declaredThisPass.get(other))) holdouts.add(other);
        }
        if (holdouts.isEmpty()) return null;
        return dep.name() + ": " + unit + " moved its pin (" + why + ") but "
                + String.join(", ", holdouts) + (holdouts.size() == 1 ? " still claims " : " still claim ")
                + describe(row.spec(), row.version()) + " — not reinstalling; bin/cli/" + dep.name()
                + " keeps the shared install until the units declare one pin";
    }

    private static String describe(String spec, String version) {
        String s = spec == null || spec.isBlank() ? "(no spec)" : spec;
        return version == null || version.isBlank() || s.contains(version)
                ? s : s + " @ " + version;
    }
}
