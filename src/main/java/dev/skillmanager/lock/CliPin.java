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

    /** Domain-separation prefix of {@link #selection}. */
    private static final String SELECTION_SCHEME = "pin-selection-v1";

    /**
     * What one unit declares for a row, as {@link CliClaimants} and the
     * shared-pin guard compare it.
     *
     * @param selection {@link #selection} of the declaration
     * @param described the declared pin, for a person to read
     */
    public record Claim(String selection, String described) {}

    private CliPin() {}

    /** The {@link Claim} {@code dep} makes. */
    public static Claim claim(CliDependency dep) {
        RequestedVersion.Requested req = RequestedVersion.of(dep);
        return new Claim(selection(dep), describe(dep.spec(), req.version()));
    }

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
        return withTargets(fp, dep).hex();
    }

    /**
     * #434 F4: the part of a declaration that chooses the bytes, without the
     * {@code min_version} floor. Two units with equal selections want the same
     * artifact, so a floor is not something they can disagree about.
     */
    public static String selection(CliDependency dep) {
        return withTargets(Fingerprints.scheme(SELECTION_SCHEME)
                .field("backend", dep.backend())
                .field("spec", dep.spec()), dep).hex();
    }

    private static Fingerprints withTargets(Fingerprints fp, CliDependency dep) {
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
        return fp;
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
            if (satisfiedBy(row, dep)) return null;
            if (sameSelection(row, dep)) {
                return "min_version " + dep.minVersion() + " is not met by the installed "
                        + describe(row.spec(), row.version());
            }
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
     * #434 F4: {@code dep} differs from the declaration {@code row} was
     * installed from only in a {@code min_version} the row's version already
     * meets. Not a bump: reinstalling would fetch the same bytes, and the row
     * stays as it is ({@link CliInstallRecorder#record}), because rewriting it
     * from every such unit in turn is what made each one look moved to the next.
     */
    public static boolean satisfiedBy(CliLock.Entry row, CliDependency dep) {
        if (row == null || row.declaredPin() == null || !governs(dep)) return false;
        if (row.declaredPin().equals(of(dep))) return false;
        return sameSelection(row, dep) && atLeast(row.version(), dep.minVersion());
    }

    /**
     * Whether {@code dep} chooses the same artifact as the declaration behind
     * {@code row}. pip and npm: the spec is the choice, and install targets are
     * ignored. tar: the row's {@code version} IS the {@code min_version} it was
     * recorded from, so {@code dep} re-digested at that floor must reproduce
     * the row's pin; anything else differs in a target.
     */
    private static boolean sameSelection(CliLock.Entry row, CliDependency dep) {
        if (!"tar".equals(dep.backend())) return Objects.equals(row.spec(), dep.spec());
        return row.declaredPin().equals(of(new CliDependency(dep.name(), dep.spec(), row.version(),
                dep.versionCheck(), dep.onPath(), dep.platformIndependent(), dep.install())));
    }

    /**
     * Whether {@code have} meets the floor {@code want}, compared segment by
     * segment numerically. Anything it cannot read as numbers, or an unknown
     * {@code have}, does not meet a floor: an unproven floor stays a difference.
     */
    static boolean atLeast(String have, String want) {
        if (want == null || want.isBlank()) return true;
        if (have == null || have.isBlank()) return false;
        String[] h = have.strip().replaceFirst("^v", "").split("\\.");
        String[] w = want.strip().replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(h.length, w.length); i++) {
            String hs = i < h.length ? h[i] : "0";
            String ws = i < w.length ? w[i] : "0";
            if (!hs.matches("\\d{1,18}") || !ws.matches("\\d{1,18}")) return have.strip().equals(want.strip());
            int c = Long.compare(Long.parseLong(hs), Long.parseLong(ws));
            if (c != 0) return c > 0;
        }
        return true;
    }

    /**
     * When {@code unit} moved its pin for a row that other units also claim:
     * a sentence naming the units still on the row's pin, or null when there is
     * nothing to refuse. Null when the pin did not move, when no other unit
     * claims the row, or when every other claimant declares {@code unit}'s new
     * pin ({@code declared}: unit -> {@link #claim}).
     *
     * <p>#428: {@code declared} is every LIVE claimant's pin
     * ({@link CliClaimants#livePins}): the units in this pass, plus installed
     * units whose manifest still declares the row. A requester missing from it
     * is stale (uninstalled, or no longer declares the dep) and is not a
     * holdout; counting it was how one dropped dependency blocked every later
     * bump. A null {@code declared} means the installed records could not be
     * read, and then every other requester is a holdout, which is the
     * conservative reading.
     */
    public static String sharedConflict(CliLock.Entry row, CliDependency dep, String unit,
                                        Map<String, Claim> declared) {
        String why = moved(row, dep);
        if (why == null) return null;
        Claim mine = claim(dep);
        Map<String, String> holdouts = new java.util.LinkedHashMap<>();
        for (String other : row.requestedBy()) {
            if (other == null || other.equals(unit)) continue;
            if (declared != null && !declared.containsKey(other)) continue;
            Claim theirs = declared == null ? null : declared.get(other);
            if (theirs != null && mine.selection().equals(theirs.selection())) continue;
            // An unreadable record leaves only the row to name their pin from.
            String pin = theirs == null ? describe(row.spec(), row.version()) : theirs.described();
            holdouts.put(other, pin.equals(mine.described()) ? pin + " from a different install target" : pin);
        }
        if (holdouts.isEmpty()) return null;
        StringBuilder out = new StringBuilder(dep.name()).append(": ").append(unit)
                .append(" declares ").append(mine.described());
        String sep = " but ";
        for (var h : holdouts.entrySet()) {
            out.append(sep).append(h.getKey()).append(" declares ").append(h.getValue());
            sep = ", and ";
        }
        java.util.List<String> uninstalls = new java.util.ArrayList<>();
        for (String other : holdouts.keySet()) uninstalls.add("`skill-manager uninstall " + other + "`");
        return out.append(" — not reinstalling; bin/cli/").append(dep.name())
                .append(" keeps ").append(describe(row.spec(), row.version()))
                .append(". One install backs every unit, so to resolve this either make them declare")
                .append(" one pin (edit the [[cli_dependencies]] entry in the unit's source, then")
                .append(" `skill-manager sync <unit> --from <dir> --yes`, or move it to a revision that")
                .append(" agrees with `skill-manager sync <unit> --to <rev>`), or remove the other unit (")
                .append(String.join(", ", uninstalls)).append(")").toString();
    }

    private static String describe(String spec, String version) {
        String s = spec == null || spec.isBlank() ? "(no spec)" : spec;
        return version == null || version.isBlank() || s.contains(version)
                ? s : s + " @ " + version;
    }
}
