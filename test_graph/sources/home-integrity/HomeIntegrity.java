//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.20.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a healthy Skill Manager home looks like, as checks that can be run
 * against one.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The artifact-DAG epic surfaced ten defects in the operator's own homes,
 * every one of them found by accident while doing something else — a human or
 * an agent noticing an oddity. Nothing anywhere said what a healthy home looks
 * like, so each was discovered once and would have recurred silently. This is
 * that statement, written so a test graph can execute it.
 *
 * <h2>These are not the nine invariants the ticket proposed</h2>
 *
 * <p>ARTI-22 (#124) listed nine candidate invariants drawn from the ten
 * measured defects, and said explicitly that the list was a starting point and
 * not a specification: argue each before modelling it, and drop any that turn
 * out to be false, because <em>an invariant a healthy home genuinely violates
 * is a modelling error, not a bug</em>. Three of the nine did not survive that
 * argument in the form they were written, and the reasons are recorded on each
 * method rather than in a commit message, because the next person to read this
 * file will want to know why the wording differs from the issue.
 *
 * <p>Summary of what changed, with the measurement that changed it. Every
 * figure below was taken read-only from the operator's project home at
 * {@code skill-manager-integration-repository/.skill-manager} on 2026-08-16.
 *
 * <table>
 *   <caption>the nine candidates, adjudicated</caption>
 *   <tr><th>#124's name</th><th>verdict</th></tr>
 *   <tr><td>{@code RecordAgreesWithStore}</td>
 *       <td><b>kept as written</b>, including its error-state disjunct — and
 *       with that disjunct it <em>holds</em> 20/20. See
 *       {@link #recordAgreesWithStore}.</td></tr>
 *   <tr><td>{@code NoPhantomAhead}</td>
 *       <td><b>merged with defect 3 and reshaped</b> into
 *       {@link #upstreamTracksWhatSyncFetched}: as written it constrains a
 *       <em>reporter</em>, not a home.</td></tr>
 *   <tr><td>{@code AckIsStable}</td>
 *       <td><b>kept, second clause dropped.</b> See {@link #ackIsStable}.</td></tr>
 *   <tr><td>{@code EveryShimResolves}</td>
 *       <td><b>restated.</b> As written — "inside this home or an explicitly
 *       sanctioned parent store" — it is FALSE of a healthy home. See
 *       {@link #everyShimResolves}.</td></tr>
 *   <tr><td>{@code EveryLockRowHasAClaimant}</td>
 *       <td><b>kept</b>, with the claimant computed from what units declare
 *       <em>today</em>. See {@link #everyLockRowHasAClaimant}.</td></tr>
 *   <tr><td>{@code RecordedBinaryIsProduced}</td>
 *       <td><b>DROPPED and replaced</b> by
 *       {@link #declaredCliIsSatisfiedAndAttributed}: as written it is false by
 *       design on any machine that already has the tool.</td></tr>
 *   <tr><td>{@code InstanceTemplateInstalled}</td>
 *       <td><b>kept.</b> See {@link #instanceTemplateInstalled}.</td></tr>
 *   <tr><td>{@code ProjectionSourceIsDecidable}</td>
 *       <td><b>restated</b>, and it then <em>holds</em> 106/106. The headline
 *       "51 of 106 serve bytes from another home" is a true measurement with a
 *       false implication. See {@link #projectionSourceIsDecidable}.</td></tr>
 *   <tr><td>{@code BootstrapProjectsTheTargetHome}</td>
 *       <td><b>kept.</b> Checked by its own node rather than here, because it
 *       is a property of a <em>command</em> and not of a home at rest.</td></tr>
 * </table>
 *
 * <h2>The shape of a check</h2>
 *
 * <p>Every method returns the list of {@link Finding}s — the <em>violations</em>
 * — so an empty list means the invariant holds. That direction is deliberate:
 * a check that returns a boolean can be satisfied by looking at nothing, and
 * this epic has already been bitten twice by an oracle that passed vacuously.
 * Each check therefore also reports how many subjects it examined via
 * {@link Report#examined()}, and a caller asserting "healthy" is expected to
 * assert a non-zero subject count as well.
 */
final class HomeIntegrity {

    private HomeIntegrity() {}

    /** One violation: which invariant, what subject, and the evidence. */
    record Finding(String invariant, String subject, String detail) {
        @Override
        public String toString() {
            return invariant + " [" + subject + "] " + detail;
        }
    }

    /**
     * The outcome of one check: what it looked at, and what it found wrong.
     *
     * <p>{@code examined} is not decoration. A check over an empty home returns
     * no findings, which is indistinguishable from a check over a healthy one
     * unless the subject count is carried alongside.
     */
    record Report(String invariant, int examined, List<Finding> findings) {

        boolean holds() {
            return findings.isEmpty();
        }

        /** Holds, and actually looked at something. */
        boolean holdsNonVacuously() {
            return findings.isEmpty() && examined > 0;
        }

        String describe() {
            if (findings.isEmpty()) return invariant + ": holds over " + examined + " subject(s)";
            StringBuilder b = new StringBuilder(invariant + ": " + findings.size()
                    + " violation(s) over " + examined + " subject(s)");
            for (Finding f : findings) b.append("\n    ").append(f);
            return b.toString();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TomlMapper TOML = new TomlMapper();

    // ================================================================= 1

    /**
     * <b>RecordAgreesWithStore</b> — for every git-backed unit, the recorded
     * {@code gitHash} equals the store checkout's HEAD, <em>or</em> the unit's
     * record carries an error explaining why it does not.
     *
     * <h3>Why the disjunct is the whole invariant</h3>
     *
     * <p>#124 lists as defect 1 that "3 of 20 installed records disagree with
     * their own store checkout" — {@code deploy-helm} records {@code a2e79d57}
     * while its store is at {@code 00d00dd4}, and likewise
     * {@code hyper-experiments-finance} and {@code spec-double-compiler}.
     * Reproduced exactly, read-only, on 2026-08-16: 20 git-backed units, those
     * same 3 disagreeing, with those same hashes.
     *
     * <p><b>And all three record an error.</b> Each of their
     * {@code installed/<u>.json} carries
     * {@code errors[0].kind == "MERGE_CONFLICT"} with a message naming the
     * upstream it failed to merge — they are the three units #99 is knowingly
     * holding back on a vendored-{@code test_graph}-symlink conflict, and their
     * stores really are mid-conflict ({@code git status --porcelain} reports
     * {@code DU} entries in every one).
     *
     * <p>So the home is not violating this invariant. It is correctly recording
     * that three units are mid-conflict, and the invariant <em>as #124 wrote
     * it</em>, disjunct included, <b>holds 20 of 20</b>.
     *
     * <p>What is actually wrong is one level up: the consumers of that record
     * read {@code gitHash} without reading {@code errors}. That is why
     * {@code skt check} reported a pull for a unit with nothing to pull, and it
     * is why the census — which grades this class {@code CONTENT} only when
     * <em>every</em> record agrees, with no error-state escape — can never grade
     * this home {@code CONTENT} while three units are legitimately conflicted.
     * The invariant is a property of the home; the defect is in the readers.
     *
     * <p>The regression this check therefore guards is the one that would be
     * silent: a record whose hash disagrees with its store and which says
     * <em>nothing</em> about why.
     */
    static Report recordAgreesWithStore(Path home) {
        List<Finding> out = new ArrayList<>();
        int examined = 0;
        for (Map.Entry<String, JsonNode> e : installedRecords(home).entrySet()) {
            String unit = e.getKey();
            JsonNode rec = e.getValue();
            String recorded = text(rec, "gitHash");
            if (recorded == null || recorded.isBlank()) continue;   // not git-backed
            Path store = storeOf(home, unit);
            if (store == null) continue;                            // no checkout to compare
            examined++;
            String head = gitHead(store);
            if (head == null) {
                out.add(new Finding("RecordAgreesWithStore", unit,
                        "record names gitHash " + shortHash(recorded)
                                + " but the store at " + store + " has no readable HEAD"));
                continue;
            }
            if (head.equals(recorded)) continue;
            if (explainsAHashGap(rec)) continue;                    // the disjunct
            out.add(new Finding("RecordAgreesWithStore", unit,
                    "record says " + shortHash(recorded) + ", store HEAD is " + shortHash(head)
                            + ", and the record carries no error explaining the gap"));
        }
        return new Report("RecordAgreesWithStore", examined, out);
    }

    // ================================================================= 2

    /**
     * <b>UpstreamTracksWhatSyncFetched</b> — for every git-backed unit store
     * that has a tracking ref, that ref is not left behind the commit the last
     * fetch actually brought down.
     *
     * <h3>This is #124's defects 2 and 3, merged and moved down a layer</h3>
     *
     * <p>#124 proposes {@code NoPhantomAhead}: "a unit reported ahead has a
     * commit that is genuinely not an ancestor of its remote tip". That
     * constrains a <em>reporter</em> — {@code skt check} — and skt already fixed
     * it (skill-publisher-skill#15). Asserting it here would test somebody
     * else's repository through a keyhole.
     *
     * <p>Defect 3 ("{@code skt sync} leaves {@code @}{@code {upstream}} stale,
     * so the next {@code check} misreports the unit it just synced") is the same
     * defect one layer down, and that layer <em>is</em> the home. ARTI-11 named
     * the cause and deferred it: {@code GitOps.fetchRef} runs
     * {@code git fetch --no-tags --quiet <remote> <ref>} where {@code <remote>}
     * may be a <b>URL</b>, which writes {@code FETCH_HEAD} and updates no
     * remote-tracking ref at all. skt's fix stops its own false alarm; every
     * other path that advances a store still leaves the tracking ref behind.
     *
     * <p>So the two candidates collapse into one home-level fact, and it is the
     * fact that generates both symptoms. Measured on the project home: of 20
     * stores, <b>13</b> have HEAD equal to {@code FETCH_HEAD} while
     * {@code @}{@code {upstream}} names some earlier commit — {@code git-integration-repo}
     * reports {@code ahead=52} in exactly that state. That is the phantom, in
     * the home, at rest.
     *
     * <p>The check is deliberately network-free: it compares the tracking ref
     * against {@code FETCH_HEAD}, which is the record of what the last fetch
     * brought. A store whose HEAD and {@code FETCH_HEAD} agree while the
     * tracking ref does not is a store that was advanced by a URL fetch.
     *
     * <p>A store with no tracking ref at all is <em>not</em> counted as a
     * violation here — that is a different defect (ARTI-11 recorded it:
     * {@code _local_state} is silent for such a store, and
     * {@code spec-double-compiler}'s store is live in that state) and folding
     * two defects into one check makes both harder to act on.
     */
    static Report upstreamTracksWhatSyncFetched(Path home) {
        List<Finding> out = new ArrayList<>();
        int examined = 0;
        for (Map.Entry<String, JsonNode> e : installedRecords(home).entrySet()) {
            Path store = storeOf(home, e.getKey());
            if (store == null) continue;
            String upstream = git(store, "rev-parse", "@{upstream}");
            if (upstream == null) continue;             // no tracking ref: a different defect
            String fetchHead = git(store, "rev-parse", "FETCH_HEAD");
            if (fetchHead == null) continue;            // never fetched here
            examined++;
            if (upstream.equals(fetchHead)) continue;
            // A tracking ref BEHIND what the last fetch brought is the defect.
            // A tracking ref AHEAD of it is ordinary (someone fetched the ref
            // properly, later), so only report the behind direction.
            if (isAncestor(store, upstream, fetchHead)) {
                out.add(new Finding("UpstreamTracksWhatSyncFetched", e.getKey(),
                        "@{upstream} is " + shortHash(upstream) + " but FETCH_HEAD is "
                                + shortHash(fetchHead)
                                + " — the fetch that advanced this store did not move the"
                                + " tracking ref, so every ahead/behind count read from it"
                                + " is phantom"));
            }
        }
        return new Report("UpstreamTracksWhatSyncFetched", examined, out);
    }

    // ================================================================= 3

    /**
     * <b>AckIsStable</b> — an acknowledged drift record stays acknowledged, and
     * the baseline it acknowledged is the home's current digest.
     *
     * <h3>What was dropped, and why</h3>
     *
     * <p>#124 proposes: "an acknowledged drift stays acknowledged until content
     * changes; <em>an operation that changes content and then acks must not
     * leave a fresh unacked record</em>". The first clause is true and is
     * checked here. <b>The second clause is dropped as a modelling error.</b>
     *
     * <p>{@code DriftGate}'s class comment is explicit and argued: "the pending
     * record is a fact about a change that happened, not a statement about the
     * home's current self-consistency, and nothing but an explicit
     * acknowledgement retires it". A gate that cleared itself when the digest
     * was refreshed was tried and produced a vacuously-passing oracle — the
     * comment records the spec run that proved it. So if you ack and then sync,
     * and the sync changes a unit, a fresh unacked record is <em>correct</em>.
     * ARTI-01's observation (defect 4: drift re-pended after ARTI-00's ack,
     * "caused by the acking operation's own syncs") is a real and annoying
     * sequencing problem in an operator's workflow. It is not a broken
     * invariant, and asserting it as one would demand the gate fail open.
     *
     * <p>What <em>is</em> assertable, and sharp, is that an ack must not be
     * stale on arrival. {@code DriftGate.recordSince} records the pending gate
     * and <em>then</em> writes the new baseline, so after any recording pass
     * {@code home.drift.json}'s {@code report.to} equals
     * {@code home.digest.json}'s {@code digest}, and {@code acknowledge} carries
     * that report through unchanged. If those two ever diverge while the record
     * reads acknowledged, the ack acknowledged a baseline the home has already
     * left, and the very next check re-pends with no cause a user can see.
     *
     * <p>Verified on the operator's root home, 2026-08-16:
     * {@code acknowledged=true}, {@code report.to} and {@code home.digest.json}
     * both {@code df22c759…}. The invariant holds there today.
     */
    static Report ackIsStable(Path home) {
        List<Finding> out = new ArrayList<>();
        Path driftFile = home.resolve("home.drift.json");
        Path digestFile = home.resolve("home.digest.json");
        if (!Files.isRegularFile(driftFile)) {
            // No drift record is a legitimate state for a fresh home. It is not
            // a violation, but it IS zero subjects, and the caller is told so.
            return new Report("AckIsStable", 0, out);
        }
        JsonNode drift = readJson(driftFile);
        if (drift == null) {
            out.add(new Finding("AckIsStable", "home.drift.json",
                    "present but unparseable — DriftGate.read synthesizes an UNACKNOWLEDGED"
                            + " record for exactly this case, so a home in this state gates"
                            + " every launch until it is repaired"));
            return new Report("AckIsStable", 1, out);
        }
        boolean acked = drift.path("acknowledged").asBoolean(false);
        if (!acked) {
            // Pending drift is a normal state, and this invariant is about what
            // an ACK leaves behind — so there is nothing here to be right or
            // wrong about. ZERO subjects, not one: reporting 1 examined / 0
            // violations would make holdsNonVacuously() true having checked
            // nothing, and the operator's project home is in exactly this state
            // right now, so a caller asserting non-vacuity there would have been
            // asserting a check that never ran. Caught by review.
            return new Report("AckIsStable", 0, out);
        }
        if (drift.path("acknowledgedAt").isMissingNode() || text(drift, "acknowledgedAt") == null) {
            out.add(new Finding("AckIsStable", "home.drift.json",
                    "acknowledged=true with no acknowledgedAt — the receipt does not say when,"
                            + " so nothing can order it against the operations around it"));
        }
        String to = drift.path("report").path("to").asText(null);
        JsonNode digest = readJson(digestFile);
        String current = digest == null ? null : text(digest, "digest");
        if (to != null && current != null && !to.equals(current)) {
            out.add(new Finding("AckIsStable", "home.drift.json",
                    "the acknowledged baseline is " + shortHash(to) + " but the home digest is "
                            + shortHash(current)
                            + " — the ack was stale on arrival and the next drift check will"
                            + " re-pend with no cause the operator can see"));
        }
        return new Report("AckIsStable", 1, out);
    }

    // ================================================================= 4

    /**
     * <b>EveryShimResolves</b> — every entry in {@code bin/cli} resolves to a
     * file that exists and is executable.
     *
     * <h3>#124's wording is false of a healthy home, and this is the correction</h3>
     *
     * <p>#124 proposes: "every {@code bin/cli} entry resolves to something
     * executable <em>inside this home or an explicitly sanctioned parent
     * store</em>". Measured on the project home, that is false — and it is false
     * for a shim that is working perfectly.
     * {@code bin/cli/tofu -> /opt/homebrew/opt/opentofu/bin/tofu} is a
     * brew-backed CLI dependency, correctly installed, correctly linked, and it
     * points at neither this home nor any parent store. Under #124's wording it
     * is a violation; under any sane reading it is the brew backend doing its
     * job. Requiring a shim to resolve inside a home would forbid the brew, npm
     * and pip backends from ever succeeding.
     *
     * <p>So the location clause is dropped and the invariant is the part that
     * actually matters: <b>a shim that does not run is broken, wherever it
     * points.</b> Measured: 12 of 12 entries resolve in the project home.
     *
     * <p>Defect 5 (#124's "dangling CLI shims — {@code bin/cli/jinja2} →
     * {@code venvs/…}, {@code bin/cli/skill-dev} → {@code cache/…}, neither
     * tree present") is real, and this check catches it — but note <em>where</em>
     * it is real. Both targets are present in the source home; ARTI-01 found
     * them dangling in a <b>cloned ticket home</b>, because {@code HomeCloner}
     * deliberately does not carry {@code venvs/} (1.2 MB) or {@code cache/}
     * (2672.1 MB). The defect is a property of the clone, not of the home it was
     * cloned from, which is why the damaged fixture for this invariant is a
     * clone and not a mutation.
     */
    static Report everyShimResolves(Path home) {
        List<Finding> out = new ArrayList<>();
        int examined = 0;
        Path binCli = home.resolve("bin").resolve("cli");
        if (!Files.isDirectory(binCli)) return new Report("EveryShimResolves", 0, out);
        for (Path entry : listDir(binCli)) {
            String name = entry.getFileName().toString();
            if (name.startsWith(".")) continue;         // per-unit shim subdirs
            if (Files.isDirectory(entry)) continue;
            examined++;
            if (!Files.exists(entry)) {                 // follows the link
                String target = Files.isSymbolicLink(entry) ? readLink(entry) : "(not a link)";
                out.add(new Finding("EveryShimResolves", name,
                        "dangles: " + entry + " -> " + target + " and the target is not there"));
                continue;
            }
            if (!Files.isExecutable(entry)) {
                out.add(new Finding("EveryShimResolves", name,
                        "resolves to " + entry + " but it is not executable"));
            }
        }
        return new Report("EveryShimResolves", examined, out);
    }

    // ================================================================= 5

    /**
     * <b>EveryLockRowHasAClaimant</b> — every {@code cli-lock.toml} row is
     * declared by a unit that is installed <em>now</em>.
     *
     * <h3>The trap this check has to avoid</h3>
     *
     * <p>#124's defect 6 is "3 orphan {@code npm} lock rows declared by no
     * installed unit, unprunable". Reproduced exactly: {@code npm:gemini-cli},
     * {@code npm:google} and {@code npm:google-gemini-cli}.
     *
     * <p>But each of those rows carries
     * {@code requested_by = ["acp-cdc-ai-python", ...]}, and
     * {@code acp-cdc-ai-python} <b>is installed</b>. A check that reads the
     * row's own {@code requested_by} and tests it against the installed set
     * therefore finds <em>zero</em> orphans and passes — over the exact three
     * rows the defect is about. {@code requested_by} is a historical record of
     * who asked, not a statement about who still asks.
     *
     * <p>The claimant must be computed from what units declare today:
     * {@code cli_dependencies[].spec} in each installed unit's manifest.
     * {@code acp-cdc-ai-python} declares {@code npm:@google/gemini-cli} and
     * nothing else in that family; the other three specs are what is left of
     * three earlier guesses at the package name.
     *
     * <p>And the manifest is not one filename. Skills and doc-repos declare in
     * {@code skill-manager.toml}; <b>plugins declare in
     * {@code skill-manager-plugin.toml}</b>. A first pass of this check that
     * read only {@code skill-manager.toml} reported a fourth orphan,
     * {@code skill-script:skt} — which the {@code skt} plugin declares, in the
     * plugin manifest, correctly. Reading one filename produces a false
     * accusation against a healthy row.
     *
     * <p>The fix for the three real orphans is #109's ({@code CliDependencyCleaner}
     * only prunes from an uninstall of a declaring unit, so a row orphaned by a
     * spec rename is never reached again). This ticket owns the assertion.
     */
    static Report everyLockRowHasAClaimant(Path home) {
        List<Finding> out = new ArrayList<>();
        Map<String, Set<String>> declared = declaredCliSpecs(home);
        Map<String, String> rows = lockRowSpecs(home);
        for (Map.Entry<String, String> row : rows.entrySet()) {
            String spec = row.getValue();
            Set<String> claimants = declared.get(spec);
            if (claimants != null && !claimants.isEmpty()) continue;
            out.add(new Finding("EveryLockRowHasAClaimant", row.getKey(),
                    "spec " + spec + " is declared by no installed unit"
                            + " (requested_by names history, not a current claim)"));
        }
        return new Report("EveryLockRowHasAClaimant", rows.size(), out);
    }

    // ================================================================= 6

    /**
     * <b>DeclaredCliIsSatisfiedAndAttributed</b> — every declared CLI dependency
     * is satisfied, either by a shim this home produced or by a binary the
     * presence check accepted from the system, <b>and the home records which of
     * the two it was</b>.
     *
     * <h3>Why #124's {@code RecordedBinaryIsProduced} was dropped outright</h3>
     *
     * <p>#124 proposes: "a row recording a {@code binary} means that binary
     * exists at the recorded path, or the row records why not" (defect 7: "11
     * lock rows record a {@code binary} the install never produced").
     *
     * <p>As a statement about a healthy home the first half is <b>false by
     * design</b>. {@code CliPresence.alreadyProvided} counts a binary already on
     * the system {@code PATH} as satisfying a CLI dep, so {@code BrewBackend},
     * {@code PipBackend} and {@code NpmBackend} return {@code ALREADY_PRESENT}
     * and write nothing into the home. Measured on the operator's project home:
     * <b>27 declaration rows across 22 distinct {@code on_path} names</b>; 16 of
     * the 27 rows (11 distinct lock rows, 11 distinct binaries) have no
     * {@code bin/cli/<on_path>} — and <b>every one of the 11 is present on the
     * system PATH</b> ({@code helm}, {@code kubectl}, {@code k3d},
     * {@code docker}, {@code gh}, {@code gemini}, {@code claude}, {@code codex},
     * {@code ollama}, {@code pytest}, {@code tb-query}). Those 11 are ARTI-06's
     * 11, name for name. The home is not broken; it correctly declined to
     * install eleven tools the machine already had.
     *
     * <p>So the first conjunct here — <em>satisfied</em> — holds over all 22
     * distinct binaries, and asserting #124's version would have this check fail
     * on a perfectly healthy machine and pass on a machine that happened to have
     * nothing installed. That is the modelling error the ticket warned about, in
     * its sharpest form.
     *
     * <p><i>Denominators, because there are two and a review caught me sliding
     * between them: 27 counts declaration rows (units declare the same tool
     * more than once — three units declare {@code brew:gh}), 22 counts distinct
     * {@code on_path} binaries, and this check iterates the 22.</i>
     *
     * <h3>The second conjunct is the real defect, and how this check stays
     * sensitive to it</h3>
     *
     * <p>Nothing in {@code cli-lock.toml} distinguishes "this home built a shim"
     * from "the machine already had it". ARTI-06 measured the consequence and
     * deferred it with two candidate fixes — record the output at
     * {@code Scope.EXTERNAL} pointing at the accepted system path, or stop
     * recording a {@code binary} the install did not produce — and called it the
     * owner's decision.
     *
     * <p><b>An earlier version of this check could not observe that fix, and the
     * review was right to refuse it.</b> It asked only two questions — does
     * {@code bin/cli/<on_path>} exist, and is {@code <on_path>} on {@code PATH}
     * — and neither answer changes when the product starts recording provenance.
     * So the defect pinned on it was unfalsifiable by any product change and
     * could only ever be retired by editing this file, which is precisely the
     * "lie with a ticket number attached" the node's own comment warns about.
     *
     * <p>It now reads the lock row and asks whether the row <b>says</b> the
     * dependency was satisfied from outside this home, via
     * {@link #recordsExternalProvenance}. Writing any of those fields turns this
     * check green and the pinned assertion red, which is the sensitivity the pin
     * needs to be worth having.
     */
    static Report declaredCliIsSatisfiedAndAttributed(Path home) {
        List<Finding> out = new ArrayList<>();
        int examined = 0;
        Map<String, JsonNode> rowsByTool = lockRowsByTool(home);
        for (Map.Entry<String, Set<String>> dep : declaredOnPath(home).entrySet()) {
            String onPath = dep.getKey();
            examined++;
            Path shim = home.resolve("bin").resolve("cli").resolve(onPath);
            if (Files.exists(shim)) continue;               // this home produced it
            String external = onSystemPath(onPath);
            if (external == null) {
                out.add(new Finding("DeclaredCliIsSatisfiedAndAttributed", onPath,
                        "declared by " + dep.getValue() + " but there is no shim in this home"
                                + " and nothing named " + onPath + " on PATH — the dependency"
                                + " is simply unsatisfied"));
                continue;
            }
            if (recordsExternalProvenance(rowsByTool.get(onPath))) continue;
            out.add(new Finding("DeclaredCliIsSatisfiedAndAttributed", onPath,
                    "satisfied externally by " + external + " and no lock row says so —"
                            + " 'declared and not materialized here' and 'satisfied by the"
                            + " machine' are indistinguishable in the ledger (#122)"));
        }
        return new Report("DeclaredCliIsSatisfiedAndAttributed", examined, out);
    }

    /**
     * The field names that would constitute "the home records that this
     * dependency was satisfied from outside itself".
     *
     * <p>None of them exists today — that is the defect. They are enumerated
     * rather than guessed at one name, because ARTI-06 left the choice open
     * between two shapes (an {@code EXTERNAL}-scoped output naming the accepted
     * system path, or a flag saying the install produced nothing), and this
     * check should go green on <em>either</em> rather than dictate which the
     * fix must be. Whoever closes it should not also have to negotiate with a
     * test about the field's spelling.
     */
    static final List<String> EXTERNAL_PROVENANCE_FIELDS =
            List.of("install_scope", "scope", "external_path", "provided_by",
                    "provided_outside_home", "binary_scope");

    /** Whether {@code row} states that this dependency is satisfied externally. */
    static boolean recordsExternalProvenance(JsonNode row) {
        if (row == null) return false;
        for (String field : EXTERNAL_PROVENANCE_FIELDS) {
            JsonNode v = row.path(field);
            if (v.isMissingNode() || v.isNull()) continue;
            if (v.isBoolean()) return v.asBoolean();
            String s = v.asText("");
            if (!s.isBlank()) return true;
        }
        return false;
    }

    /**
     * Every {@code cli-lock.toml} row keyed by the BINARY it declares rather
     * than by the package it installs.
     *
     * <p>The row is keyed by package ({@code ["npm"."@google/gemini-cli"]}) and
     * the binary lives in the declaring unit's {@code on_path}
     * ({@code gemini}), so the two have to be joined through the manifests —
     * ARTI-03 recorded that the mapping is unrecoverable once the declaring unit
     * is gone. Rows this home can still attribute are matched here; the rest are
     * simply absent from the map, which reads as "records no provenance" and is
     * the correct answer for them.
     */
    static Map<String, JsonNode> lockRowsByTool(Path home) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        JsonNode doc = readToml(home.resolve("cli-lock.toml"));
        if (doc == null) return out;
        Map<String, String> specToOnPath = new LinkedHashMap<>();
        forEachDeclaredDep(home, (unit, dep) -> {
            String spec = dep.path("spec").asText(null);
            String onPath = dep.path("on_path").asText(null);
            if (spec != null && onPath != null && !onPath.isBlank()) {
                specToOnPath.put(spec, onPath);
            }
        });
        for (java.util.Iterator<String> backends = doc.fieldNames(); backends.hasNext(); ) {
            String backend = backends.next();
            JsonNode tools = doc.get(backend);
            if (tools == null || !tools.isObject()) continue;
            for (java.util.Iterator<String> names = tools.fieldNames(); names.hasNext(); ) {
                String tool = names.next();
                JsonNode row = tools.get(tool);
                if (row == null || !row.isObject()) continue;
                String spec = row.path("spec").asText(backend + ":" + tool);
                String onPath = specToOnPath.get(spec);
                if (onPath != null) out.put(onPath, row);
            }
        }
        return out;
    }

    /**
     * The unsatisfied subset of {@link #declaredCliIsSatisfiedAndAttributed} —
     * the conjunct that <em>does</em> hold on a healthy home.
     *
     * <p>Split out so a node can assert the true half positively instead of
     * asserting nothing while the attribution half is known-broken. A green
     * assertion over a known-broken invariant is worse than a red one; a green
     * assertion over the half that is genuinely true, beside a pinned defect for
     * the half that is not, is the honest decomposition.
     */
    static Report declaredCliIsSatisfied(Path home) {
        List<Finding> out = new ArrayList<>();
        int examined = 0;
        for (Map.Entry<String, Set<String>> dep : declaredOnPath(home).entrySet()) {
            String onPath = dep.getKey();
            examined++;
            if (Files.exists(home.resolve("bin").resolve("cli").resolve(onPath))) continue;
            if (onSystemPath(onPath) != null) continue;
            out.add(new Finding("DeclaredCliIsSatisfied", onPath,
                    "declared by " + dep.getValue() + ": no shim in this home and nothing"
                            + " on PATH"));
        }
        return new Report("DeclaredCliIsSatisfied", examined, out);
    }

    // ================================================================= 7

    /**
     * <b>InstanceTemplateInstalled</b> — every harness instance's template is an
     * installed unit.
     *
     * <p>#124's defect 8: "5 harness instances reference templates no longer
     * installed — cannot be re-fingerprinted in place". Reproduced exactly. The
     * project home holds five records under
     * {@code harnesses/instances/<id>/.harness-instance.json}, each naming a
     * {@code harnessName} of {@code learning-app-run-*}, and
     * {@code units.lock.toml} contains <b>no unit of kind {@code harness}</b> at
     * all — 17 skills, 2 plugins, 1 doc. Each instance directory is otherwise
     * empty, and the {@code projectDir}/{@code claudeConfigDir} it names points
     * into a different repository's run tree.
     *
     * <p>Kept as written: an instance whose template is gone cannot be
     * re-fingerprinted, re-instantiated, or verified, and nothing in the home
     * says so. Unlike defect 7 there is no design reason for this state — it is
     * simply an uninstall that did not reach its instances. The fix belongs with
     * <b>#120</b> (harness instances are one of the three artifact classes it
     * owns; ARTI-06 recorded that {@code HARNESS_INSTANCE} is "inherently
     * per-item" and cheap to make buildable "once #120 gives them something to
     * compare"), so this check reports and the node pins it there.
     */
    static Report instanceTemplateInstalled(Path home) {
        List<Finding> out = new ArrayList<>();
        Set<String> installed = installedRecords(home).keySet();
        int examined = 0;
        Path instances = home.resolve("harnesses").resolve("instances");
        if (!Files.isDirectory(instances)) return new Report("InstanceTemplateInstalled", 0, out);
        for (Path dir : listDir(instances)) {
            Path rec = dir.resolve(".harness-instance.json");
            if (!Files.isRegularFile(rec)) continue;
            examined++;
            JsonNode node = readJson(rec);
            String template = node == null ? null : text(node, "harnessName");
            String id = node == null ? dir.getFileName().toString()
                    : orElse(text(node, "instanceId"), dir.getFileName().toString());
            if (template == null) {
                out.add(new Finding("InstanceTemplateInstalled", id,
                        "instance record names no harnessName at all"));
                continue;
            }
            if (installed.contains(template)) continue;
            out.add(new Finding("InstanceTemplateInstalled", id,
                    "references template '" + template + "', which is not installed —"
                            + " the instance cannot be re-fingerprinted in place"));
        }
        return new Report("InstanceTemplateInstalled", examined, out);
    }

    // ================================================================= 8

    /**
     * <b>ProjectionSourceIsDecidable</b> — every projection this home recorded
     * resolves somewhere this home can account for: inside itself, or inside a
     * child home it registered, for a unit that child home was registered to
     * carry.
     *
     * <h3>The correction this ticket most wanted to make</h3>
     *
     * <p>#124's defect 9, sourced from ARTI-18 and the census, is "<b>51 of 106
     * projections serve bytes from another home</b>". The measurement is exactly
     * right — reproduced to the projection, 51 of 106 — and the implication
     * drawn from it is wrong.
     *
     * <p>Every one of those 51 is a <b>child-home projection</b>. The project
     * home registered four child homes, each with a
     * {@code child-homes/project_<name>/child-home.json} recording its path and
     * the units it carries. The 51 "foreign" symlinks point into
     * {@code hyper-experiments-finance-polymarket/.skill-manager/skills/…} and
     * {@code support-agent-rears/.skill-manager/skills/…} — and in every case
     * the target home is one of those four registered children, and the unit is
     * named in that child's own {@code units} list. That is child-home
     * materialization working precisely as designed: the parent records the
     * binding it created, the child serves the bytes.
     *
     * <p>Classified with the child-home clause, the project home comes out
     * <b>106 of 106 decidable</b>: 51 resolve inside the home, 51 resolve inside
     * a registered child that claims the unit, and the remaining 4 are not
     * symlinks at all — one {@code MANAGED_COPY} and three
     * {@code IMPORT_DIRECTIVE} projections belonging to the {@code doc-repo-devops}
     * doc unit, which are legitimately files rather than links.
     *
     * <p>So the invariant holds, and "51 of 106 serve bytes from another home" is
     * a true sentence that should not have been read as a defect. What #121 is
     * really fixing is that the <em>census</em> cannot see this: it grades the
     * class on {@code boundHash}, which is populated 1 of 106, and no amount of
     * correct child-home wiring moves that number.
     *
     * <p>The regressions worth guarding are therefore the ones that would be
     * genuinely undecidable: a projection whose target is missing, and a
     * projection into a home this one never registered.
     */
    static Report projectionSourceIsDecidable(Path home) {
        List<Finding> out = new ArrayList<>();
        int examined = 0;
        Map<Path, Set<String>> children = registeredChildHomes(home);
        Path homeReal = real(home);
        for (Path f : listDir(home.resolve("installed"))) {
            String name = f.getFileName().toString();
            if (!name.endsWith(".projections.json")) continue;
            JsonNode doc = readJson(f);
            if (doc == null) continue;
            String unit = orElse(text(doc, "unitName"),
                    name.substring(0, name.length() - ".projections.json".length()));
            for (JsonNode binding : doc.path("bindings")) {
                for (JsonNode p : binding.path("projections")) {
                    String destPath = p.path("destPath").asText(null);
                    if (destPath == null) continue;
                    examined++;
                    String kind = p.path("kind").asText("");
                    Path dest = Path.of(destPath);
                    if (!Files.exists(dest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        out.add(new Finding("ProjectionSourceIsDecidable", unit,
                                kind + " projection " + destPath + " is not there at all"));
                        continue;
                    }
                    if (!Files.isSymbolicLink(dest)) continue;   // COPY / IMPORT_DIRECTIVE
                    Path target = real(dest);
                    if (target == null) {
                        out.add(new Finding("ProjectionSourceIsDecidable", unit,
                                "symlink " + destPath + " does not resolve"));
                        continue;
                    }
                    if (homeReal != null && target.startsWith(homeReal)) continue;
                    Path child = childHomeContaining(children, target);
                    if (child == null) {
                        out.add(new Finding("ProjectionSourceIsDecidable", unit,
                                "symlink " + destPath + " -> " + target
                                        + " leaves this home and lands in no child home this"
                                        + " home registered — nothing here can say whose bytes"
                                        + " those are"));
                        continue;
                    }
                    if (!children.get(child).contains(unit)) {
                        out.add(new Finding("ProjectionSourceIsDecidable", unit,
                                "symlink " + destPath + " -> " + target + " lands in child home "
                                        + child + ", which was not registered to carry "
                                        + unit));
                    }
                }
            }
        }
        return new Report("ProjectionSourceIsDecidable", examined, out);
    }

    // ========================================================== all of them

    /** Every check that is a property of a home at rest, in a stable order. */
    static List<Report> all(Path home) {
        List<Report> out = new ArrayList<>();
        out.add(recordAgreesWithStore(home));
        out.add(upstreamTracksWhatSyncFetched(home));
        out.add(ackIsStable(home));
        out.add(everyShimResolves(home));
        out.add(everyLockRowHasAClaimant(home));
        out.add(declaredCliIsSatisfied(home));
        out.add(declaredCliIsSatisfiedAndAttributed(home));
        out.add(instanceTemplateInstalled(home));
        out.add(projectionSourceIsDecidable(home));
        return out;
    }

    static String describe(List<Report> reports) {
        StringBuilder b = new StringBuilder();
        for (Report r : reports) b.append(r.describe()).append('\n');
        return b.toString();
    }

    // =============================================================== reading

    /** {@code installed/<unit>.json}, by unit name, excluding the projection files. */
    static Map<String, JsonNode> installedRecords(Path home) {
        Map<String, JsonNode> out = new TreeMap<>();
        for (Path f : listDir(home.resolve("installed"))) {
            String name = f.getFileName().toString();
            if (!name.endsWith(".json") || name.endsWith(".projections.json")) continue;
            JsonNode node = readJson(f);
            if (node == null) continue;
            out.put(name.substring(0, name.length() - ".json".length()), node);
        }
        return out;
    }

    /**
     * The store checkout for a unit, or null when there is none.
     *
     * <p>Probed across all four unit roots rather than switched on the
     * record's {@code unitKind}, which is deliberately not read: a record
     * written by an older schema may carry no kind, and a wrong guess here
     * silently skips the unit — the direction that makes a check pass over
     * nothing.
     */
    static Path storeOf(Path home, String unit) {
        for (String root : List.of("skills", "plugins", "docs", "harnesses")) {
            Path p = home.resolve(root).resolve(unit);
            if (Files.isDirectory(p.resolve(".git"))) return p;
        }
        return null;
    }

    /** {@code cli-lock.toml} rows as {@code backend:tool -> spec}. */
    static Map<String, String> lockRowSpecs(Path home) {
        Map<String, String> out = new LinkedHashMap<>();
        Path lock = home.resolve("cli-lock.toml");
        if (!Files.isRegularFile(lock)) return out;
        JsonNode doc = readToml(lock);
        if (doc == null) return out;
        for (java.util.Iterator<String> backends = doc.fieldNames(); backends.hasNext(); ) {
            String backend = backends.next();
            JsonNode tools = doc.get(backend);
            if (tools == null || !tools.isObject()) continue;
            for (java.util.Iterator<String> names = tools.fieldNames(); names.hasNext(); ) {
                String tool = names.next();
                JsonNode row = tools.get(tool);
                if (row == null || !row.isObject()) continue;
                String key = backend + ":" + tool;
                String spec = row.path("spec").asText(null);
                out.put(key, spec == null ? key : spec);
            }
        }
        return out;
    }

    /**
     * The CLI specs each installed unit declares <em>today</em>, as
     * {@code spec -> declaring units}.
     *
     * <p>Reads {@code skill-manager.toml} for skills and doc-repos and
     * {@code skill-manager-plugin.toml} for plugins. Reading only the first
     * produces a false orphan for every plugin-declared dependency — see
     * {@link #everyLockRowHasAClaimant}.
     */
    static Map<String, Set<String>> declaredCliSpecs(Path home) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        forEachDeclaredDep(home, (unit, dep) -> {
            String spec = dep.path("spec").asText(null);
            if (spec == null) return;
            out.computeIfAbsent(spec, k -> new LinkedHashSet<>()).add(unit);
        });
        return out;
    }

    /** As {@link #declaredCliSpecs}, keyed by the {@code on_path} binary name. */
    static Map<String, Set<String>> declaredOnPath(Path home) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        forEachDeclaredDep(home, (unit, dep) -> {
            String onPath = dep.path("on_path").asText(null);
            if (onPath == null || onPath.isBlank()) return;
            out.computeIfAbsent(onPath, k -> new LinkedHashSet<>()).add(unit);
        });
        return out;
    }

    private interface DepVisitor {
        void accept(String unit, JsonNode dep);
    }

    private static void forEachDeclaredDep(Path home, DepVisitor visitor) {
        for (String unit : installedRecords(home).keySet()) {
            for (Path manifest : List.of(
                    home.resolve("skills").resolve(unit).resolve("skill-manager.toml"),
                    home.resolve("docs").resolve(unit).resolve("skill-manager.toml"),
                    home.resolve("harnesses").resolve(unit).resolve("harness.toml"),
                    home.resolve("plugins").resolve(unit).resolve("skill-manager-plugin.toml"))) {
                if (!Files.isRegularFile(manifest)) continue;
                JsonNode doc = readToml(manifest);
                if (doc == null) continue;
                for (JsonNode dep : doc.path("cli_dependencies")) visitor.accept(unit, dep);
            }
        }
    }

    /** Registered child homes as {@code realpath -> the units it was registered to carry}. */
    static Map<Path, Set<String>> registeredChildHomes(Path home) {
        Map<Path, Set<String>> out = new LinkedHashMap<>();
        for (Path dir : listDir(home.resolve("child-homes"))) {
            Path rec = dir.resolve("child-home.json");
            if (!Files.isRegularFile(rec)) continue;
            JsonNode node = readJson(rec);
            if (node == null) continue;
            String childHome = text(node, "childHome");
            if (childHome == null) continue;
            Set<String> units = new LinkedHashSet<>();
            for (JsonNode u : node.path("units")) units.add(u.asText());
            Path real = real(Path.of(childHome));
            if (real != null) out.put(real, units);
        }
        return out;
    }

    private static Path childHomeContaining(Map<Path, Set<String>> children, Path target) {
        for (Path child : children.keySet()) if (target.startsWith(child)) return child;
        return null;
    }

    // ================================================================ shell

    static String gitHead(Path repo) {
        return git(repo, "rev-parse", "HEAD");
    }

    /** {@code git -C repo args…}, trimmed stdout, or null on any non-zero exit. */
    static String git(Path repo, String... args) {
        List<String> argv = new ArrayList<>(List.of("git", "-C", repo.toString()));
        Collections.addAll(argv, args);
        return capture(argv);
    }

    static boolean isAncestor(Path repo, String maybeAncestor, String descendant) {
        List<String> argv = List.of("git", "-C", repo.toString(), "merge-base",
                "--is-ancestor", maybeAncestor, descendant);
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Where {@code name} resolves on the inherited {@code PATH}, or null.
     *
     * <p>Deliberately the inherited PATH and not a curated one: the question
     * this answers is the same question {@code CliPresence} asked at install
     * time, and it asked it of the environment the operator was in.
     */
    static String onSystemPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir).resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static String capture(List<String> argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ================================================================ util

    static List<Path> listDir(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) out.add(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Path::compareTo);
        return out;
    }

    static JsonNode readJson(Path f) {
        try {
            return Files.isRegularFile(f) ? JSON.readTree(f.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    static JsonNode readToml(Path f) {
        try {
            return Files.isRegularFile(f) ? TOML.readTree(f.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    static boolean hasErrors(JsonNode record) {
        JsonNode errors = record.path("errors");
        return errors.isArray() && errors.size() > 0;
    }

    /**
     * The error kinds that can explain a recorded hash disagreeing with its
     * store — the disjunct in {@link #recordAgreesWithStore}, narrowed.
     *
     * <p>These are the states in which the store was deliberately left at a
     * revision the record does not name: a merge that conflicted and stashed
     * (the three live cases in the operator's project home), a checkout that
     * could not be completed, a fetch that failed. Each is a reason the two
     * legitimately differ.
     */
    static final Set<String> HASH_GAP_EXPLAINING_ERROR_KINDS = Set.of(
            "MERGE_CONFLICT", "CHECKOUT_FAILED", "FETCH_FAILED", "SYNC_FAILED",
            "STASH_CONFLICT", "DIRTY_WORKTREE");

    /**
     * Whether {@code record}'s errors explain a hash gap.
     *
     * <p><b>Narrowed after review, and the review was right.</b> This used to be
     * {@link #hasErrors} — <em>any</em> error of <em>any</em> kind excused
     * <em>any</em> divergence, indefinitely. That is a hole straight through an
     * invariant whose name is {@code RecordDescribesItsStoreOrSaysWhy} and whose
     * whole regression case is "a record that says NOTHING about why": a unit
     * carrying one unrelated warning from months ago could then drift
     * arbitrarily and this check would keep passing. The disjunct is meant to
     * admit "the store is somewhere else and here is the reason", not "this unit
     * has had a bad day at some point".
     *
     * <p>So the error has to be of a kind that actually bears on the gap. An
     * unrecognised kind is <b>not</b> accepted: the permissive direction is the
     * silent one, and a new error kind that legitimately explains a gap should
     * arrive here as a one-line addition with a name, rather than being waved
     * through by a check that stopped looking.
     */
    static boolean explainsAHashGap(JsonNode record) {
        JsonNode errors = record.path("errors");
        if (!errors.isArray()) return false;
        for (JsonNode e : errors) {
            String kind = e.path("kind").asText("");
            if (HASH_GAP_EXPLAINING_ERROR_KINDS.contains(kind)) return true;
        }
        return false;
    }

    static String readLink(Path p) {
        try {
            return Files.readSymbolicLink(p).toString();
        } catch (IOException e) {
            return "(unreadable link)";
        }
    }

    static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    static String shortHash(String h) {
        return h == null ? "(none)" : (h.length() > 8 ? h.substring(0, 8) : h);
    }

    private static String orElse(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
