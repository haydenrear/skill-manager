package dev.skillmanager.commands;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.store.HomeDescriptor;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>A home report never states a default as a fact, and never answers about a
 * file it did not read.</b>
 *
 * <p>HIS-21 / DEF-105 and DEF-106. Both are the same failure in two verbs: a
 * reader answering confidently about a state it did not check. The epic's own
 * sentence for it is <i>"a diagnostic that cannot be trusted is worse than no
 * diagnostic"</i>, and both of these were measured on the operator's real
 * homes.
 *
 * <h2>DEF-105, and the correction to how it was filed</h2>
 *
 * <p>#253 filed it as <i>"the root home carries a security policy the product
 * does not read"</i>. <b>Measured, the product reads it.</b> A throwaway home
 * holding {@code allowed_backends = ["tar"], require_hash = true,
 * allow_init_scripts = true} in {@code policy.toml} produced exactly those
 * three values from {@code skill-manager policy show}, via
 * {@link dev.skillmanager.policy.Policy#load}, which resolves
 * {@code <store>/policy.toml}. It is not legacy and it is not dead.
 *
 * <p>The defect that IS there is narrower and worse than a stale file: two
 * files in one directory are both called policy, both govern that home, they
 * are read by two different verbs, and neither verb mentioned the other.
 * {@code home policy} said {@code home.policy.toml (absent — live by default)}
 * while a live security policy permitting init scripts sat beside it.
 *
 * <h2>DEF-106, and the open question it carried</h2>
 *
 * <p>#253 asks whether a project home is SUPPOSED to have a
 * {@code home.runtime.json}. Answered from the code rather than from taste:
 * {@code LaunchEnv.of} reads one when present and otherwise derives the same
 * block from the home's layout, and only {@code home clone} and
 * {@code describe --write} persist one. A home without a descriptor is a normal
 * home, so {@code describe} computing one is correct — and printing the
 * computed answer in the same shape as a recorded one is not.
 *
 * <h2>What reddens each assertion</h2>
 *
 * <p>Named per test rather than in a block, because mechanism D of the vacuity
 * ledger is what these are most exposed to: every claim here is about a
 * SENTENCE, and a sentence assertion passes for free against any output that
 * happens to contain the word.
 */
public final class HomeReportsMarkWhatTheyInventTest {

    public static int run() throws Exception {
        return Tests.suite("HomeReportsMarkWhatTheyInventTest")

                .test("DEF-105: `home policy` names the OTHER policy file in the same directory", () -> {
                    // BRANCH: PolicyCmd's read path -> reportInstallPolicy, the
                    // present arm.
                    // MUTATION THAT REDDENS IT: delete the reportInstallPolicy
                    // call. Every assertion below then fails on output that is
                    // still perfectly true about home.policy.toml -- which is
                    // the defect, stated exactly.
                    Path home = bareHome("policy-both");
                    Files.writeString(home.resolve("policy.toml"), """
                            # skill-manager policy — guards against hostile skills
                            allowed_backends = ["tar"]
                            require_hash = true
                            allow_init_scripts = true
                            """, StandardCharsets.UTF_8);
                    assertFalse(Files.exists(home.resolve(HomePolicy.FILENAME)),
                            "precondition: the file this verb is ABOUT is absent — otherwise "
                                    + "the report has two declared files and the silence this "
                                    + "test is about cannot occur");

                    Result r = policy(home);

                    assertEquals(0, r.rc, "a report verb still reports");
                    assertContains(r.out, "(absent — live by default)",
                            "precondition: the original, true sentence about home.policy.toml "
                                    + "is unchanged — this ticket adds a sentence, it does not "
                                    + "replace one");
                    assertContains(r.out, "install policy:",
                            "THE CLAIM: the other policy file in this directory is named");
                    assertContains(r.out, "policy.toml  (declared)",
                            "and it is marked DECLARED, in the same house convention "
                                    + "lazy_artifacts uses one line above");
                    assertContains(r.out, "allow_init_scripts=true",
                            "the security-relevant value is quoted, not summarised — 'there is "
                                    + "another file' sends a reader to another verb; 'it permits "
                                    + "init scripts' makes them look");
                    assertContains(r.out, "allowed_backends=[tar]",
                            "and the value came from Policy.load rather than from a template — "
                                    + "the default is all five backends, so [tar] can only have "
                                    + "been read off this home's file");
                })

                .test("DEF-105: with no policy.toml the same verb says so, rather than staying silent", () -> {
                    // The absent arm, and the reason it is not optional. A verb
                    // that mentions the file only when it exists teaches the
                    // reader nothing about the case where it does not, and this
                    // whole class of defect is a zero that means "did not look"
                    // being read as "looked and found nothing".
                    // MUTATION: make reportInstallPolicy return early when the
                    // file is absent. This test goes red; the one above stays
                    // green.
                    Path home = bareHome("policy-neither");

                    Result r = policy(home);

                    assertContains(r.out, "install policy:",
                            "the second policy file is named whether or not it exists");
                    assertContains(r.out, "(absent — install defaults apply",
                            "and its absence is marked as a DEFAULT rather than left unsaid");
                    assertFalse(Files.exists(home.resolve("policy.toml")),
                            "and the REPORT verb did not create the file it reported on — a "
                                    + "diagnostic that materialises its own subject is the "
                                    + "fail-open shape requireHome exists to remove");
                })

                .test("DEF-106: a home with no descriptor says every field below is DERIVED", () -> {
                    // BRANCH: renderHuman's `recorded == false` arm.
                    // MUTATION THAT REDDENS IT: restore the four original
                    // Log.info lines. The command still prints policy, cli and
                    // gateway, all still true of what it computed, and this
                    // test fails on every line -- which is what "printing a
                    // synthesised field as a recorded one" means.
                    Path home = bareHome("describe-derived");
                    assertFalse(Files.exists(HomeDescriptor.file(home)),
                            "precondition: this home has no home.runtime.json. A home that HAD "
                                    + "one would be the other branch and this test would assert "
                                    + "nothing");

                    Result r = describe(home);

                    assertEquals(0, r.rc, "describe still describes");
                    assertContains(r.out, "every field below is DERIVED",
                            "THE CLAIM: the reader is told the descriptor is computed");
                    assertContains(r.out, "(not declared — default; no home.policy.toml here)",
                            "policy follows `home policy`'s own house convention, which this "
                                    + "command did not follow");
                })

                .test("DEF-106: a home that declared no gateway does not report the default as OWNED", () -> {
                    // NAMED FOR WHAT IT ASSERTS. Review of PR #256, minor 3:
                    // this case was called "two homes never both report one
                    // gateway as OWNED", and the code does not hold that.
                    // TWO homes that each PERSIST gateway.owned=true for one
                    // port both print `(owned — declared in gateway.properties)`
                    // and always did. What this ticket changed is the
                    // DECLARED-versus-DEFAULTED distinction, which is what the
                    // body below actually drives, and a name that overreaches
                    // its body is a claim a reader takes on trust.
                    //
                    // The measured half. The root home's descriptor declares
                    // {"gateway": {"owned": true}} for 127.0.0.1:51717 and the
                    // project home -- which has no gateway.properties at all --
                    // reported `(owned)` for the same URL, with one process
                    // listening. Two homes cannot both own a port.
                    //
                    // BRANCH: renderHuman's gateway arm, `gatewayDeclared`
                    // false.
                    // MUTATION: `d.gateway().owned() ? "owned" : ...`, the
                    // original line. The first assertion then fails; the second
                    // still passes, so the pair distinguishes "the report is
                    // honest" from "the report says nothing".
                    Path claimant = bareHome("gateway-claimant");
                    GatewayConfig.persist(new SkillStore(claimant), GatewayConfig.DEFAULT_URL, true);
                    Path silent = bareHome("gateway-silent");
                    assertFalse(Files.exists(silent.resolve("gateway.properties")),
                            "precondition: the second home has declared nothing about a gateway");

                    Result quiet = describe(silent);
                    assertContains(quiet.out, "no gateway.properties here",
                            "a home that claimed nothing reports the default AS a default");
                    assertFalse(quiet.out.contains("(owned"),
                            "and it does not claim ownership of a port it never mentioned; "
                                    + "got:\n" + quiet.out);

                    Result loud = describe(claimant);
                    assertContains(loud.out, "(owned — declared in gateway.properties)",
                            "while the home that DID claim it still reports ownership — "
                                    + "otherwise this fix is 'stop saying owned', which would "
                                    + "make the two homes agree by making both silent");
                    assertContains(loud.out, GatewayConfig.DEFAULT_URL,
                            "on the same URL, so the two reports above are about one port");
                })

                .test("DEF-106: the CLI's PROVENANCE is printed, not just its path", () -> {
                    // The third synthesised field, and the one that reads most
                    // like a fact about the home while being a fact about the
                    // caller's shell. CliSource already models the distinction
                    // -- "three of these steps identify a build somebody
                    // deliberately associated with this home; the fourth is a
                    // guess that happens to be spelled absolutely" -- and
                    // printing the bare path threw it away at the last step.
                    //
                    // BRANCH: cliProvenance over CliSource.HOME_ENTRYPOINT.
                    // Driven through the home's OWN entrypoint rather than
                    // through $SKILL_MANAGER_CLI because a JVM cannot set its
                    // own environment, so the PINNED_ENV arm is not reachable
                    // from in-process -- naming that rather than asserting a
                    // branch this test cannot reach.
                    Path home = bareHome("describe-cli");
                    Path entrypoint = home.resolve("bin").resolve("cli").resolve("skill-manager");
                    Files.createDirectories(entrypoint.getParent());
                    Files.writeString(entrypoint, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
                    entrypoint.toFile().setExecutable(true);

                    Result r = describe(home);

                    assertContains(r.out, "this home's own bin/cli/skill-manager",
                            "THE CLAIM: the report says WHERE the CLI answer came from. got:\n"
                                    + r.out);
                })

                .test("MAJOR-2: a refused `home clone` names a remedy and says where the copy went", () -> {
                    // Review of PR #256, MAJOR-2. HIS-21 widened `home verify`
                    // to see a wrapper shim; `home clone` verifies its copy
                    // with the same check, so it inherited the refusal and
                    // printed NO REMEDY AT ALL, on the one command whose
                    // failure leaves a populated directory behind. This epic's
                    // standing rule is that a refusal with no remedy is the
                    // #142 class.
                    //
                    // BRANCH: HomeCommand.cloneFailureRemedy, the
                    // FOREIGN_PATH_IN_SHIM arm.
                    // MUTATION THAT REDDENS IT: delete the cloneFailureRemedy
                    // call from print()'s failure branch. The refusal itself
                    // still prints -- exit 1 and the finding -- so a test that
                    // only asserted "clone refuses" would stay green. These
                    // assert the REMEDY.
                    Path source = bareHome("clone-remedy-src");
                    Path third = bareHome("clone-remedy-third");
                    Files.createDirectories(third.resolve("skills").resolve("tool"));
                    Files.writeString(third.resolve("skills/tool/run.sh"), "echo hi\n",
                            StandardCharsets.UTF_8);
                    Path shim = source.resolve("bin").resolve("cli").resolve("thirdparty");
                    Files.createDirectories(shim.getParent());
                    Files.writeString(shim, "#!/usr/bin/env bash\nexec bash \""
                            + third.resolve("skills/tool/run.sh") + "\" \"$@\"\n",
                            StandardCharsets.UTF_8);
                    shim.toFile().setExecutable(true);
                    Path dest = Files.createTempDirectory("clone-remedy-dest-")
                            .resolve("copy").resolve(".skill-manager");

                    Result r = capture(() -> new CommandLine(new HomeCommand.CloneCmd())
                            .execute("--from", source.toString(), "--to", dest.toString()));

                    assertEquals(1, r.rc(), "precondition: the clone is refused at all");
                    assertContains(r.out(), "FOREIGN_PATH_IN_SHIM",
                            "precondition: refused for the reason this ticket introduced");
                    assertTrue(Files.isDirectory(dest),
                            "precondition: the half-made copy really is left on disk — the "
                                    + "sentence below would otherwise be false");
                    assertContains(r.out(), "was LEFT IN PLACE",
                            "THE CLAIM 1: the operator is told the copy exists and does not "
                                    + "pass its own verify. got:\n" + r.out());
                    assertContains(r.out(), "repair the SOURCE and clone again",
                            "THE CLAIM 2: a remedy is printed at all");
                    assertContains(r.out(), "home repair --home " + source,
                            "and it names the SOURCE — re-anchoring rewrites paths naming the "
                                    + "source, and these name a THIRD home, so repairing only "
                                    + "the copy leaves the next clone failing identically");
                    assertContains(r.out(), "home repair --home " + dest,
                            "with the repair-in-place exit offered too");
                })

                .runAll();
    }

    // ---------------------------------------------------------------- drivers

    /**
     * A laid-out home and nothing else: no {@code home.runtime.json}, no
     * {@code gateway.properties}, no {@code home.policy.toml}, no
     * {@code policy.toml}. The shape the project home is actually in, which is
     * what made both defects visible there.
     */
    private static Path bareHome(String label) throws Exception {
        Path tmp = Files.createTempDirectory("home-reports-" + label + "-");
        Path store = tmp.resolve(".skill-manager");
        new SkillStore(store).init();
        for (String invented : new String[] {
                HomeDescriptor.FILENAME, "gateway.properties",
                HomePolicy.FILENAME, "policy.toml" }) {
            assertFalse(Files.exists(store.resolve(invented)),
                    "fixture invariant: init() must not have written " + invented
                            + " — this fixture's whole job is to have none of them");
        }
        return store;
    }

    private static Result policy(Path home) throws Exception {
        return capture(() -> new CommandLine(new HomeCommand.PolicyCmd())
                .execute("--home", home.toString()));
    }

    private static Result describe(Path home) throws Exception {
        return capture(() -> new CommandLine(new HomeCommand.DescribeCmd())
                .execute("--home", home.toString()));
    }

    private interface Body { int run(); }

    private static Result capture(Body body) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int rc = body.run();
            System.out.flush();
            System.err.flush();
            // BOTH streams, joined. `home describe` renders through Log, which
            // routes human lines to stderr under --json and to stdout
            // otherwise; a test that read one stream would be asserting about
            // the routing rather than about the sentence, and would go green
            // the day the routing changed.
            return new Result(rc,
                    out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    private record Result(int rc, String out) {}
}
