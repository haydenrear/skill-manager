package dev.skillmanager.cli.installer;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.lock.CliInstallRecorder;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-04: every installer backend answers "what was this derived from?".
 *
 * <p>The measurement this replaces: 9 of 9 {@code skill-script} rows in the
 * project home carried {@code install_fingerprint} and <b>0 of 16</b>
 * brew/npm/pip/tar rows did, because
 * {@code CliInstallRecorder} asked one backend by name and handed the other
 * four {@code null}. These cases pin the four properties that stops it coming
 * back: every registered backend answers, the answer is domain-separated per
 * scheme, an unanswerable one says why, and the recorder never names a backend.
 */
public final class InstallerFingerprintTest {

    /**
     * The {@code skill-script-v1} digest of a one-file scripts tree, computed
     * independently of this codebase from the encoding documented on
     * {@code SkillScriptBackend.fingerprintScripts}:
     *
     * <pre>
     * sha256( "skill-script-v1\0"
     *       + "script:install.sh\0"
     *       + "arg:--prefix\0" + "arg:$BIN_DIR\0"
     *       + "file:install.sh\0" + b"#!/bin/sh\necho hi\n" + "\0" )
     * </pre>
     *
     * <p>This is the ticket's whole migration answer, made mechanical. Every
     * {@code install_fingerprint} in every home on the machine came from this
     * scheme, and this backend GATES on it — a moved digest re-runs an arbitrary
     * installer script. Moving {@code fingerprintFor} behind the interface and
     * re-expressing its encoding through {@code Fingerprints} had to leave the
     * bytes alone, and a constant that was never read out of the implementation
     * is what proves it rather than asserts it.
     */
    private static final String SKILL_SCRIPT_V1_GOLDEN =
            "ba46c0377d006af6a9c8caeb817b98614c84cac4c588a8226b54319080917a96";

    /**
     * The same tree with {@code args=[""]} — one EMPTY arg, which the encoder
     * writes as {@code arg:\0} — and with {@code args=[]}. Two vectors, because
     * a first pass at {@code Fingerprints.field} skipped empty values as well as
     * null ones and collapsed these two inputs onto one digest, moving a scheme
     * {@code SkillScriptBackend} gates on. The golden vector above could not
     * catch it: its args are non-empty.
     */
    private static final String SKILL_SCRIPT_V1_EMPTY_ARG =
            "92062451a21dc12172c507d67fd769a145e2a337af91abf63756ce343354f97c";
    private static final String SKILL_SCRIPT_V1_NO_ARGS =
            "bb3e3f59e46271408d9a998bf2b3795dff82da22f8190c0094b387ca69e8103f";

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("InstallerFingerprintTest");

        suite.test("skill-script-v1 digest is byte-identical to the pre-ARTI-04 scheme", () -> {
            Path scripts = Files.createTempDirectory("fp-golden-");
            Files.writeString(scripts.resolve("install.sh"), "#!/bin/sh\necho hi\n");
            assertEquals(SKILL_SCRIPT_V1_GOLDEN,
                    SkillScriptBackend.fingerprintScripts(
                            scripts, "install.sh", List.of("--prefix", "$BIN_DIR")),
                    "no home's recorded skill-script fingerprint moves, so nothing re-fires");
        });

        suite.test("an empty arg is encoded, and does not collide with no args at all", () -> {
            Path scripts = Files.createTempDirectory("fp-empty-arg-");
            Files.writeString(scripts.resolve("install.sh"), "#!/bin/sh\necho hi\n");
            String emptyArg = SkillScriptBackend.fingerprintScripts(
                    scripts, "install.sh", List.of(""));
            String noArgs = SkillScriptBackend.fingerprintScripts(
                    scripts, "install.sh", List.of());
            assertEquals(SKILL_SCRIPT_V1_EMPTY_ARG, emptyArg,
                    "args=[\"\"] hashes as the pre-ARTI-04 encoder did");
            assertEquals(SKILL_SCRIPT_V1_NO_ARGS, noArgs, "args=[] is its own vector");
            assertFalse(emptyArg.equals(noArgs),
                    "two different declarations must not produce one digest");
        });

        suite.test("every registered backend answers, and none throws", () -> {
            SkillStore store = newStore("fp-all-backends-");
            // skill-script is the one backend whose declared input is the
            // unit's own bytes, so its fixture has to exist for it to answer.
            plantSkillScript(store, "fp-unit", "install-skt.sh");
            InstallerRegistry registry = new InstallerRegistry();
            Map<String, CliDependency> byBackend = representativeDeps();
            for (String backend : registry.registeredIds()) {
                CliDependency dep = byBackend.get(backend);
                assertNotNull(dep, "test covers backend " + backend);
                Fingerprint fp = registry.fingerprintFor(dep, store, "fp-unit");
                assertNotNull(fp, backend + " returns a Fingerprint");
                assertTrue(fp.present(), backend + " fingerprints a well-formed dep: "
                        + fp.gap());
                assertNotNull(fp.basis(), backend + " says what its digest covers");
                assertNotNull(fp.kind(), backend + " asserts a grade rather than leaving it "
                        + "to a reader to infer from the prose");
            }
        });

        suite.test("each backend asserts the grade its evidence actually supports", () -> {
            SkillStore store = newStore("fp-kinds-");
            plantSkillScript(store, "fp-unit", "install-skt.sh");
            InstallerRegistry registry = new InstallerRegistry();
            Map<String, CliDependency> deps = representativeDeps();

            // Hashes bytes read off this home's disk.
            assertEquals(Fingerprint.Kind.RESOLVED,
                    registry.fingerprintFor(deps.get("skill-script"), store, "fp-unit").kind(),
                    "skill-script hashes the scripts tree itself");
            // Never looks at what it wrote; a declared sha256 is still a declaration.
            assertEquals(Fingerprint.Kind.DECLARED,
                    registry.fingerprintFor(deps.get("tar"), store, "fp-unit").kind(),
                    "tar's strongest input is still the manifest's");
            // Nothing installed in this home yet.
            for (String backend : List.of("pip", "npm")) {
                assertEquals(Fingerprint.Kind.DECLARED,
                        registry.fingerprintFor(deps.get(backend), store, "fp-unit").kind(),
                        backend + " has nothing installed here to observe");
            }
            // brew deliberately asks the HOST, not the store — brew owns its own
            // prefix and there is no per-home copy to consult — so its grade is
            // a property of this machine and the store cannot stage it. Asserted
            // against a formula no host has; the resolver itself is driven
            // directly against a synthetic prefix two cases below.
            CliDependency absent = new CliDependency("nope", "brew:arti04-no-such-formula",
                    null, null, "nope", true, Map.of());
            assertEquals(Fingerprint.Kind.DECLARED,
                    registry.fingerprintFor(absent, store, "fp-unit").kind(),
                    "brew holds no cellar entry for a formula that does not exist");
            // Now it is.
            plantDistInfo(store, "jinja2-cli", "jinja2_cli", "0.8.2");
            plantNpmPackage(store, "fp-unit", "@google/gemini-cli", "0.4.1");
            assertEquals(Fingerprint.Kind.RESOLVED,
                    registry.fingerprintFor(deps.get("pip"), store, "fp-unit").kind(),
                    "pip found the dist-info");
            assertEquals(Fingerprint.Kind.RESOLVED,
                    registry.fingerprintFor(deps.get("npm"), store, "fp-unit").kind(),
                    "npm read the installed package.json");
        });

        suite.test("the four backends that recorded nothing now record a digest", () -> {
            SkillStore store = newStore("fp-four-");
            InstallerRegistry registry = new InstallerRegistry();
            Map<String, CliDependency> deps = representativeDeps();
            for (String backend : List.of("brew", "npm", "pip", "tar")) {
                Fingerprint fp = registry.fingerprintFor(deps.get(backend), store, "fp-unit");
                assertTrue(fp.present(), backend + " no longer answers with null");
            }
        });

        suite.test("schemes are domain-separated: identical field text, different digests", () -> {
            SkillStore store = newStore("fp-domains-");
            InstallerRegistry registry = new InstallerRegistry();
            Set<String> digests = new LinkedHashSet<>();
            // Every dep names the same package string, so only the scheme
            // prefix separates the four digests. Without it, a consumer
            // reading fingerprints across backends in one listing would see
            // two artifacts as one.
            for (String backend : List.of("brew", "npm", "pip")) {
                CliDependency dep = new CliDependency("collide", backend + ":collide",
                        null, null, "collide", true, Map.of());
                digests.add(registry.fingerprintFor(dep, store, "fp-unit").value());
            }
            assertEquals(3, digests.size(),
                    "three backends over the same package name produce three digests");
        });

        suite.test("tar: the declared sha256 is in the digest, and its absence is in the basis", () -> {
            SkillStore store = newStore("fp-tar-");
            TarBackend tar = new TarBackend();
            String hashed = "a".repeat(64);
            Fingerprint pinned = tar.fingerprint(tarDep("https://example/x.tar.gz", hashed),
                    store, "fp-unit");
            Fingerprint moved = tar.fingerprint(tarDep("https://example/x.tar.gz", "b".repeat(64)),
                    store, "fp-unit");
            Fingerprint unpinned = tar.fingerprint(tarDep("https://example/x.tar.gz", null),
                    store, "fp-unit");
            Fingerprint elsewhere = tar.fingerprint(tarDep("https://example/y.tar.gz", hashed),
                    store, "fp-unit");

            assertFalse(pinned.value().equals(moved.value()), "a new sha256 is a new artifact");
            assertFalse(pinned.value().equals(unpinned.value()),
                    "declaring a sha256 is itself a different declaration");
            assertFalse(pinned.value().equals(elsewhere.value()), "a new url is a new artifact");
            assertContains(pinned.basis(), "sha256", "a pinned target says it covers the hash");
            assertContains(unpinned.basis(), "no sha256",
                    "an unpinned target says a re-publish is undetectable");
        });

        suite.test("tar: no target for this platform is a gap with a reason, not a digest", () -> {
            SkillStore store = newStore("fp-tar-gap-");
            CliDependency dep = new CliDependency("nowhere", "tar:nowhere", null, null,
                    "nowhere", false, Map.of("some-other-os",
                    new CliDependency.InstallTarget("https://example/x", null, "nowhere",
                            List.of(), null)));
            Fingerprint fp = new TarBackend().fingerprint(dep, store, "fp-unit");
            assertFalse(fp.present(), "nothing declares what would be downloaded");
            assertContains(fp.gap(), "no install target", "the gap names what is missing");
        });

        suite.test("pip: the version resolved into venvs/ moves the digest", () -> {
            SkillStore store = newStore("fp-pip-");
            CliDependency dep = new CliDependency("jinja2-cli", "pip:jinja2-cli[yaml]==0.8.2",
                    null, null, "jinja2", true, Map.of());
            PipBackend pip = new PipBackend();

            Fingerprint declaredOnly = pip.fingerprint(dep, store, "fp-unit");
            assertTrue(declaredOnly.present(), "a pinned spec fingerprints with no venv");
            assertContains(declaredOnly.basis(), "declared spec only",
                    "the basis says the installed version is not observable here");

            plantDistInfo(store, "jinja2-cli", "jinja2_cli", "0.8.2");
            Fingerprint resolved = pip.fingerprint(dep, store, "fp-unit");
            assertContains(resolved.basis(), "0.8.2", "the basis names the resolved version");
            assertFalse(declaredOnly.value().equals(resolved.value()),
                    "learning what was installed changes what the digest covers");

            // The case the whole scheme exists for: the declaration did not
            // move and the artifact did.
            SkillStore upgraded = newStore("fp-pip-up-");
            plantDistInfo(upgraded, "jinja2-cli", "jinja2_cli", "0.9.0");
            assertFalse(resolved.value().equals(pip.fingerprint(dep, upgraded, "fp-unit").value()),
                    "same declared pin, different installed version, different digest");
        });

        suite.test("pip: a range spec fingerprints on what is installed, not on the range", () -> {
            SkillStore atSix = newStore("fp-pip-range-6-");
            SkillStore atSeven = newStore("fp-pip-range-7-");
            CliDependency dep = new CliDependency("ruff", "pip:ruff>=0.6", null, null,
                    "ruff", true, Map.of());
            plantDistInfo(atSix, "ruff", "ruff", "0.6.0");
            plantDistInfo(atSeven, "ruff", "ruff", "0.7.4");
            PipBackend pip = new PipBackend();
            assertFalse(pip.fingerprint(dep, atSix, "fp-unit").value()
                            .equals(pip.fingerprint(dep, atSeven, "fp-unit").value()),
                    "a range that resolved differently is not the same artifact — the failure "
                            + "a digest built on RequestedVersion.fromPip's output would have");
        });

        suite.test("pip: the distribution name survives extras and operators", () -> {
            assertEquals("jinja2-cli", PipBackend.distributionName("jinja2-cli[yaml]==0.8.2"),
                    "extras and a pin are not part of the name");
            assertEquals("ruff", PipBackend.distributionName("ruff>=0.6"), "a range is not");
            assertEquals("my-pkg", PipBackend.distributionName("My_Pkg"), "PEP 503 normalization");
        });

        suite.test("npm: the installed package.json version moves the digest", () -> {
            SkillStore store = newStore("fp-npm-");
            CliDependency dep = new CliDependency("gemini", "npm:@google/gemini-cli",
                    null, null, "gemini", true, Map.of());
            NpmBackend npm = new NpmBackend();

            Fingerprint declaredOnly = npm.fingerprint(dep, store, "fp-unit");
            assertTrue(declaredOnly.present(), "a bare npm spec still fingerprints");
            assertContains(declaredOnly.basis(), "declared spec only", "and says what it lacks");

            plantNpmPackage(store, "fp-unit", "@google/gemini-cli", "0.4.1");
            Fingerprint resolved = npm.fingerprint(dep, store, "fp-unit");
            assertContains(resolved.basis(), "0.4.1", "the basis names the installed version");
            assertFalse(declaredOnly.value().equals(resolved.value()),
                    "an npm spec pins nothing, so the installed version is the only mover");
        });

        suite.test("npm: the package name is parsed without inventing a version", () -> {
            // RequestedVersion.fromNpm reads `npm:google@gemini-cli` as
            // tool=google version=gemini-cli, and the project home carries
            // exactly that row. The fingerprint must not inherit it.
            assertEquals("@google/gemini-cli", NpmBackend.packageName("@google/gemini-cli"),
                    "a scope is not a version separator");
            assertEquals("typescript", NpmBackend.packageName("typescript@5.4.5"),
                    "a real version is removed from the directory name");
        });

        suite.test("a backend with nothing declared to hash is a gap, never a digest", () -> {
            SkillStore store = newStore("fp-gaps-");
            InstallerRegistry registry = new InstallerRegistry();
            for (String backend : List.of("brew", "npm", "pip")) {
                CliDependency bare = new CliDependency("bare", backend + ":", null, null,
                        "bare", true, Map.of());
                Fingerprint fp = registry.fingerprintFor(bare, store, "fp-unit");
                assertFalse(fp.present(), backend + " has no package to hash");
                assertContains(fp.gap(), backend, "the gap names the backend that could not answer");
            }
            CliDependency unknown = new CliDependency("nope", "cargo:nope", null, null,
                    "nope", true, Map.of());
            Fingerprint fp = registry.fingerprintFor(unknown, store, "fp-unit");
            assertFalse(fp.present(), "an unregistered backend cannot fingerprint");
            assertContains(fp.gap(), "cargo", "and the gap says which one");
        });

        suite.test("a Fingerprint cannot be both a digest and a gap, or neither", () -> {
            assertTrue(refuses(() -> new Fingerprint(null, null, null, null)),
                    "neither is refused");
            assertTrue(refuses(() -> new Fingerprint(
                            "abc", Fingerprint.Kind.DECLARED, "basis", "gap")),
                    "both is refused");
            assertTrue(refuses(() -> new Fingerprint(
                            "abc", Fingerprint.Kind.DECLARED, null, null)),
                    "a digest with no basis is refused");
        });

        suite.test("the recorder writes a digest, a basis and a binary for a non-script dep", () -> {
            SkillStore store = newStore("fp-record-");
            CliDependency dep = new CliDependency("jinja2-cli", "pip:jinja2-cli[yaml]==0.8.2",
                    null, null, "jinja2", true, Map.of());
            CliLock lock = CliLock.load(store);
            CliInstallRecorder.record(lock, new InstallerRegistry(), dep, store, "fp-unit");
            lock.save(store);

            CliLock reloaded = CliLock.load(store);
            CliLock.Entry row = reloaded.get("pip", "jinja2-cli[yaml]");
            assertNotNull(row, "the row is keyed by package, as it always was");
            assertNotNull(row.installFingerprint(), "and now carries a fingerprint");
            assertNotNull(row.fingerprint().basis(), "which says what it covers");
            assertEquals("jinja2", row.binary(),
                    "and names the artifact it produced, which the row could not before");
            assertContains(Files.readString(store.root().resolve(CliLock.FILENAME)),
                    "install_fingerprint_basis", "the basis reaches the file");
        });

        suite.test("a recorded gap is written to the lock rather than dropped", () -> {
            SkillStore store = newStore("fp-record-gap-");
            CliDependency dep = new CliDependency("bare", "brew:", null, null,
                    "bare", true, Map.of());
            CliLock lock = CliLock.load(store);
            CliInstallRecorder.record(lock, new InstallerRegistry(), dep, store, "fp-unit");
            lock.save(store);
            String written = Files.readString(store.root().resolve(CliLock.FILENAME));
            assertContains(written, "install_fingerprint_gap",
                    "an unfingerprintable artifact is a known gap, not a silently-current one");
            assertFalse(written.contains("install_fingerprint ="), "and carries no digest");
            assertNotNull(CliLock.load(store).get("brew", "bare").fingerprint().gap(),
                    "the reason round-trips");
        });

        suite.test("rows written before ARTI-04 still read, and say their basis is unknown", () -> {
            SkillStore store = newStore("fp-legacy-");
            Files.writeString(store.root().resolve(CliLock.FILENAME), """
                    ["skill-script"."legacy"]
                    spec = "skill-script:legacy"
                    requested_by = ["old-unit"]
                    installed_at = "2026-05-25T17:02:29.173060Z"
                    install_fingerprint = "4a1caec7af0a7a5944c6aef053d46a26ab88b7fa56ba6900b55e0163fc253126"
                    """);
            CliLock.Entry row = CliLock.load(store).get("skill-script", "legacy");
            assertEquals("4a1caec7af0a7a5944c6aef053d46a26ab88b7fa56ba6900b55e0163fc253126",
                    row.installFingerprint(), "the digest is unchanged by the new field set");
            assertContains(row.fingerprint().basis(), "unrecorded",
                    "and does not claim a basis nobody wrote down");
            assertEquals(null, row.binary(), "a row from before cannot name its artifact");
        });

        suite.test("brew: the cellar symlink is the only thing that can move this digest", () -> {
            Path prefix = Files.createTempDirectory("fp-brew-prefix-");
            Path opt = prefix.resolve("opt");
            Files.createDirectories(opt);
            Files.createDirectories(prefix.resolve("Cellar/helm/3.19.0"));
            Files.createSymbolicLink(opt.resolve("helm"), Path.of("../Cellar/helm/3.19.0"));
            assertEquals("3.19.0", BrewBackend.versionInPrefix(prefix, "helm"),
                    "the version segment of the opt symlink's target");

            // The upgrade this scheme exists to catch: a brew spec declares no
            // version, so re-pointing the symlink is the entire signal.
            Files.delete(opt.resolve("helm"));
            Files.createDirectories(prefix.resolve("Cellar/helm/3.20.1"));
            Files.createSymbolicLink(opt.resolve("helm"), Path.of("../Cellar/helm/3.20.1"));
            assertEquals("3.20.1", BrewBackend.versionInPrefix(prefix, "helm"), "after upgrade");

            assertEquals(null, BrewBackend.versionInPrefix(prefix, "not-installed"),
                    "a formula brew does not hold is not observable");
            assertEquals(null, BrewBackend.versionInPrefix(null, "helm"), "no prefix, no answer");
        });

        suite.test("brew: a cask resolves, and an ambiguous one refuses to guess", () -> {
            Path prefix = Files.createTempDirectory("fp-brew-cask-");
            Files.createDirectories(prefix.resolve("Caskroom/claude/1.2.3"));
            assertEquals("1.2.3", BrewBackend.versionInPrefix(prefix, "claude"),
                    "a single cask version directory is the version");

            Files.createDirectories(prefix.resolve("Caskroom/claude/1.3.0"));
            assertEquals(null, BrewBackend.versionInPrefix(prefix, "claude"),
                    "two answers is not an answer — a DECLARED digest beats a guessed "
                            + "RESOLVED one");
        });

        suite.test("tar: a null install target under a present key does not throw", () -> {
            SkillStore store = newStore("fp-tar-null-");
            // A LinkedHashMap can hold a null value where Map.of cannot, and
            // containsKey-then-Map.entry threw on exactly that shape.
            Map<String, CliDependency.InstallTarget> targets = new LinkedHashMap<>();
            targets.put("any", null);
            CliDependency dep = new CliDependency("ghost", "tar:ghost", null, null,
                    "ghost", true, targets);
            Fingerprint fp = new TarBackend().fingerprint(dep, store, "fp-unit");
            assertFalse(fp.present(), "nothing declares what would be downloaded");
        });

        suite.test("an empty digest is refused, not written", () -> {
            assertTrue(refuses(() -> Fingerprint.resolved("", "covers nothing")),
                    "\"\" is the no-fingerprint case wearing a fingerprint's shape");
            assertTrue(refuses(() -> Fingerprint.declared("   ", "covers nothing")),
                    "blank too");
            assertTrue(refuses(() -> new Fingerprint("abc", null, "basis", null)),
                    "a digest with no grade is refused");
        });

        suite.test("an ungraded legacy row is never read as resolved", () -> {
            SkillStore store = newStore("fp-legacy-kind-");
            Files.writeString(store.root().resolve(CliLock.FILENAME), """
                    ["skill-script"."legacy"]
                    spec = "skill-script:legacy"
                    requested_by = ["old-unit"]
                    install_fingerprint = "4a1caec7af0a7a5944c6aef053d46a26ab88b7fa56ba6900b55e0163fc253126"
                    """);
            assertEquals(Fingerprint.Kind.UNKNOWN,
                    CliLock.load(store).get("skill-script", "legacy").fingerprint().kind(),
                    "a digest whose grade nobody recorded does not get a passing one");

            // An unrecognized token is the same answer, not a crash.
            Files.writeString(store.root().resolve(CliLock.FILENAME), """
                    ["skill-script"."legacy"]
                    spec = "skill-script:legacy"
                    requested_by = ["old-unit"]
                    install_fingerprint = "4a1caec7af0a7a5944c6aef053d46a26ab88b7fa56ba6900b55e0163fc253126"
                    install_fingerprint_kind = "from-the-future"
                    """);
            assertEquals(Fingerprint.Kind.UNKNOWN,
                    CliLock.load(store).get("skill-script", "legacy").fingerprint().kind(),
                    "an unreadable grade is unknown, not assumed");
        });

        suite.test("the kind round-trips through the lock file", () -> {
            SkillStore store = newStore("fp-kind-roundtrip-");
            CliDependency dep = new CliDependency("jinja2-cli", "pip:jinja2-cli[yaml]==0.8.2",
                    null, null, "jinja2", true, Map.of());
            plantDistInfo(store, "jinja2-cli", "jinja2_cli", "0.8.2");
            CliLock lock = CliLock.load(store);
            CliInstallRecorder.record(lock, new InstallerRegistry(), dep, store, "fp-unit");
            lock.save(store);
            assertContains(Files.readString(store.root().resolve(CliLock.FILENAME)),
                    "install_fingerprint_kind = \"resolved\"",
                    "the grade reaches the file as a token, not as prose to be matched");
            assertEquals(Fingerprint.Kind.RESOLVED,
                    CliLock.load(store).get("pip", "jinja2-cli[yaml]").fingerprint().kind(),
                    "and reads back");
        });

        suite.test("the effects path records the same row as the bulk path", () -> {
            // LiveInterpreter.runCliInstall carried its own copy of the
            // "skill-script".equals(backend) branch. It is the line that
            // changed, so it is driven here rather than trusted.
            SkillStore store = newStore("fp-live-");
            CliDependency dep = new CliDependency("jinja2-cli", "pip:jinja2-cli[yaml]==0.8.2",
                    null, null, "jinja2", true, Map.of());
            plantDistInfo(store, "jinja2-cli", "jinja2_cli", "0.8.2");
            // Satisfied by this home already, so the effect records without
            // shelling out to uv: the recording is what is under test, not the
            // install. This is the ALREADY_PRESENT path that used to write a
            // null fingerprint on every sync of every home.
            plantUsableShim(store, "jinja2");

            new dev.skillmanager.effects.LiveInterpreter(store).run(
                    new dev.skillmanager.effects.Program<>(
                            "fp-live",
                            List.of(new dev.skillmanager.effects.SkillEffect.RunCliInstall(
                                    "fp-unit", dep, false)),
                            receipts -> null));

            CliLock.Entry row = CliLock.load(store).get("pip", "jinja2-cli[yaml]");
            assertNotNull(row, "the effects path wrote the row");
            assertNotNull(row.installFingerprint(),
                    "with a fingerprint — this path recorded null for four backends");
            assertEquals(Fingerprint.Kind.RESOLVED, row.fingerprint().kind(), "graded");
            assertEquals("jinja2", row.binary(), "and naming the artifact it produced");
        });

        return suite.runAll();
    }

    // ---------------------------------------------------------------- fixtures

    private static SkillStore newStore(String prefix) throws Exception {
        SkillStore store = new SkillStore(Files.createTempDirectory(prefix));
        store.init();
        return store;
    }

    private static Map<String, CliDependency> representativeDeps() {
        Map<String, CliDependency> out = new LinkedHashMap<>();
        out.put("brew", new CliDependency("opentofu", "brew:opentofu", null, null,
                "tofu", true, Map.of()));
        out.put("npm", new CliDependency("gemini", "npm:@google/gemini-cli", null, null,
                "gemini", true, Map.of()));
        out.put("pip", new CliDependency("jinja2-cli", "pip:jinja2-cli[yaml]==0.8.2", null, null,
                "jinja2", true, Map.of()));
        out.put("tar", tarDep("https://example.test/rg.tar.gz", "c".repeat(64)));
        out.put("skill-script", new CliDependency("skt", "skill-script:skt", null, null,
                "skt", true, Map.of("any", new CliDependency.InstallTarget(
                        null, null, "skt", List.of(), null, "install-skt.sh", List.of()))));
        return out;
    }

    private static CliDependency tarDep(String url, String sha256) {
        return new CliDependency("rg", "tar:rg", null, null, "rg", true,
                Map.of("any", new CliDependency.InstallTarget(url, "tar.gz", "rg",
                        List.of(), sha256)));
    }

    /** {@code venvs/<dist>/lib/python3.13/site-packages/<escaped>-<version>.dist-info}. */
    private static void plantDistInfo(SkillStore store, String dist, String escaped,
                                      String version) throws Exception {
        Path sitePackages = store.venvsDir().resolve(dist)
                .resolve("lib").resolve("python3.13").resolve("site-packages");
        Files.createDirectories(sitePackages.resolve(escaped + "-" + version + ".dist-info"));
    }

    /** An executable in {@code bin/cli/} with no references out of the home. */
    private static void plantUsableShim(SkillStore store, String name) throws Exception {
        Files.createDirectories(store.cliBinDir());
        Path shim = store.cliBinDir().resolve(name);
        Files.writeString(shim, "#!/bin/sh\necho stub\n");
        shim.toFile().setExecutable(true, false);
    }

    /** {@code skills/<unit>/skill-scripts/<script>}. */
    private static void plantSkillScript(SkillStore store, String unit, String script)
            throws Exception {
        Path scripts = store.unitDir(unit, dev.skillmanager.model.UnitKind.SKILL)
                .resolve(SkillScriptBackend.SCRIPTS_DIRNAME);
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve(script), "#!/bin/sh\necho hi\n");
    }

    /** {@code npm/<unit>/lib/node_modules/<pkg>/package.json}. */
    private static void plantNpmPackage(SkillStore store, String unit, String pkg,
                                        String version) throws Exception {
        Path dir = store.npmDir().resolve(unit).resolve("lib").resolve("node_modules").resolve(pkg);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"),
                "{\"name\":\"" + pkg + "\",\"version\":\"" + version + "\"}");
    }

    private static boolean refuses(Runnable body) {
        try {
            body.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
