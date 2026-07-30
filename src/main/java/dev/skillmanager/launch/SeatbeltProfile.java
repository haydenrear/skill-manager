package dev.skillmanager.launch;

import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The macOS Seatbelt (SBPL) profile a sandboxed launch runs under, and the
 * validator that decides whether a profile means anything.
 *
 * <h2>What this is, and what it is emphatically NOT</h2>
 *
 * <p>This is a <b>filesystem write boundary against accidents</b>. It is the
 * enforcement half of a mechanism whose other half — {@link LaunchEnv} — is
 * convention: env vars and {@code PATH} order. Every leak this epic fixed
 * (#18, #30, #47) was a process writing where nothing stopped it, and each fix
 * was a convention that regresses the moment someone forgets a variable. The
 * kernel does not forget.
 *
 * <p>It is <b>not containment against an adversary</b>, and it must not be
 * described as one. Three ways ordinary code with no adversarial intent gets
 * out, all measured on this host:
 *
 * <ol>
 *   <li><b>An inherited write file descriptor.</b> {@code sandbox(7)}
 *       restrictions are enforced at <em>acquisition</em>. A parent that opens
 *       {@code ~/.claude/x} for append and then spawns the sandboxed child
 *       hands it a working write handle; measured, the child wrote through
 *       {@code >&9} and the sandbox never saw a path. Not fixable from
 *       here.</li>
 *   <li><b>A pre-existing daemon is a confused deputy.</b> A sandboxed Gradle
 *       invocation talks to an <em>already running, unsandboxed</em> Gradle
 *       daemon and the daemon does the writing. There was one on this machine
 *       writing into {@code ~/.skill-manager/skills/test-graph/.../.gradle/}
 *       from another session while this was being researched. Any client of any
 *       out-of-sandbox service has this shape.</li>
 *   <li><b>A hardlink created outside the sandbox.</b> {@code ln
 *       ~/.claude/protected.txt <worktree>/hl.txt} made from an unsandboxed
 *       shell leaves the protected inode reachable through an allowed path;
 *       measured, the sandboxed write through {@code hl.txt} changed
 *       {@code protected.txt}. The kernel checks the path that is opened, and
 *       that path is allowed.</li>
 * </ol>
 *
 * <p>So: a very good guard against the {@code #18}/{@code #30}/{@code #47}
 * shape — a process writing to a home nobody meant it to touch — and not a
 * security boundary. Say that, rather than letting a reader infer more.
 *
 * <h2>Provenance and licence</h2>
 *
 * <p>The region between {@link #VENDOR_BEGIN} and {@link #VENDOR_END} is
 * {@code codex-rs/sandboxing/src/seatbelt_base_policy.sbpl} from
 * <a href="https://github.com/openai/codex">openai/codex</a>, vendored
 * <b>verbatim</b> — 122 lines, sha256
 * {@value #VENDORED_SHA256} of the upstream file. openai/codex is
 * <b>Apache-2.0</b> (repository root {@code LICENSE}, 201 lines, verified
 * before vendoring). Upstream's own header credits Chromium's
 * {@code sandbox/policy/mac/common.sb} (BSD-3-Clause) and is kept.
 *
 * <p>It was vendored rather than written because it already answers the
 * questions a from-scratch profile gets wrong for a week: which
 * {@code sysctl-read} names a JVM and a Rust binary probe (including the
 * {@code hw.optional.arm.*} prefix), {@code /dev/null} as a character device,
 * {@code /dev/ptmx} and the {@code /dev/ttys*} extension dance that keeps an
 * interactive PTY working, and the {@code ipc-posix-sem} Python's
 * multiprocessing needs.
 *
 * <p>{@link #vendoredBase()} plus {@link #VENDORED_SHA256} exist so that a
 * later edit inside the vendored region is a test failure rather than a silent
 * divergence from upstream. Modify the skill-manager section below it instead.
 *
 * <h2>SBPL is LAST-MATCH-WINS, which is why {@link #validate} exists</h2>
 *
 * <p>A profile is a sequence of rules and the <em>last</em> one that matches
 * decides. {@code (deny default)} at the top is the default action, not a
 * final word: one trailing
 * {@code (allow file-write* (subpath "/private/tmp"))} silently reopens
 * everything under it while the profile still reads as deny-by-default. That
 * is not hypothetical — the researcher's own first draft did exactly this and
 * reported success while enforcing nothing, and it was reproduced here
 * (a decoy write that had been denied succeeded, file present, after appending
 * that one line).
 *
 * <p>So the shipped profile is not trusted because it looks right. Every
 * profile is parsed before use and refused if any rule grants a filesystem
 * write to something other than
 *
 * <ul>
 *   <li>a path supplied by the caller as a {@code (param "…")} — the writable
 *       roots, which {@link SeatbeltSandbox} canonicalizes and bounds; or</li>
 *   <li>a path under {@code /dev/} — the character devices every shell and
 *       toolchain writes to.</li>
 * </ul>
 *
 * <p>Nothing else may be granted a write. A broad allow anywhere fails that
 * test, wherever it is placed, which is the right rule for a language where
 * position is the whole semantics.
 *
 * <h2>Why writes are granted by parameter and never by interpolation</h2>
 *
 * <p>SBPL is a Lisp dialect. A path spliced into the profile text as a string
 * literal is an injection vector the moment it contains a quote, and there is
 * no escaping story worth maintaining. {@code sandbox-exec -D NAME=VALUE} binds
 * the value as data, so a worktree called {@code fo"o} is a directory name and
 * not a syntax error.
 *
 * @see SeatbeltSandbox for the parameter rules — every {@code -D} value must be
 *      an existing, absolute, canonical directory, and must not be the user's
 *      home or a filesystem root
 */
public final class SeatbeltProfile {

    private SeatbeltProfile() {}

    /** First line of the vendored region. */
    public static final String VENDOR_BEGIN =
            "; >>> BEGIN vendored openai/codex seatbelt_base_policy.sbpl (Apache-2.0)";

    /** Last line of the vendored region. */
    public static final String VENDOR_END =
            "; <<< END vendored openai/codex seatbelt_base_policy.sbpl";

    /**
     * sha256 of the upstream file as fetched from
     * {@code https://raw.githubusercontent.com/openai/codex/main/codex-rs/sandboxing/src/seatbelt_base_policy.sbpl}
     * on 2026-07-30.
     */
    public static final String VENDORED_SHA256 =
            "9a7a181ac5fab3e8fcecfeeec280f8b0d4fd60c852cf71cdf3b5c65d02401e0c";

    /**
     * The parameters every generated profile is invoked with, in the order
     * {@link SeatbeltSandbox} passes them. All five are always supplied: an
     * SBPL {@code (param "X")} with no binding is a load error, so "sometimes
     * omitted" is not an option a profile can be written against.
     */
    public static final List<String> PARAMETERS = List.of(
            "OP_WORKTREE", "OP_STORE", "OP_TMPDIR", "OP_SYSTMP", "OP_VARTMP");

    /** Filters that carry a path, in the shapes SBPL spells them. */
    private static final Set<String> PATH_FILTERS =
            Set.of("literal", "subpath", "path", "regex", "prefix", "home-literal", "home-subpath");

    /** Operations that grant a write to the filesystem. */
    private static final Set<String> WRITE_OPERATIONS = Set.of(
            "file*", "file-write*", "file-write", "file-write-create", "file-write-data",
            "file-write-flags", "file-write-mode", "file-write-owner", "file-write-setugid",
            "file-write-times", "file-write-unlink", "file-write-mount", "file-write-unmount",
            "file-link", "file-clone");

    // ------------------------------------------------------------- content

    private static final String HEADER = """
            ; skill-manager launch profile.
            ;
            ; The SBPL version declaration is NOT written here: it is the first line
            ; of the vendored region below, and SBPL takes exactly one of them.
            ; Comments ahead of it are fine.
            ;
            ; Generated by `skill-manager home shims --sandbox` and applied by
            ; `skill-manager exec` via /usr/bin/sandbox-exec. See the javadoc of
            ; dev.skillmanager.launch.SeatbeltProfile for what this does and does
            ; not guarantee: it is a FILESYSTEM WRITE boundary against accidents,
            ; not containment against an adversary. An inherited write fd, a
            ; pre-existing daemon, and a hardlink made outside the sandbox each
            ; get out of it, and each is reachable by ordinary code.
            ;
            ; SBPL IS LAST-MATCH-WINS. Appending `(allow file-write* ...)` below
            ; reopens everything it names, silently, while this file still reads
            ; as deny-by-default. Every write granted here is granted through a
            ; (param "...") whose value skill-manager canonicalizes, or to a
            ; character device under /dev. `skill-manager exec` re-parses this
            ; file on every launch and REFUSES to run if that is not true, so an
            ; edit that widens it stops launches instead of silently permitting
            ; writes.

            """;

    private static final String VENDORED = """
            (version 1)

            ; inspired by Chrome's sandbox policy:
            ; https://source.chromium.org/chromium/chromium/src/+/main:sandbox/policy/mac/common.sb;l=273-319;drc=7b3962fe2e5fc9e2ee58000dc8fbf3429d84d3bd
            ; https://source.chromium.org/chromium/chromium/src/+/main:sandbox/policy/mac/renderer.sb;l=64;drc=7b3962fe2e5fc9e2ee58000dc8fbf3429d84d3bd

            ; start with closed-by-default
            (deny default)

            ; child processes inherit the policy of their parent
            (allow process-exec)
            (allow process-fork)
            (allow signal (target same-sandbox))

            ; process-info
            (allow process-info* (target same-sandbox))

            (allow file-write-data
              (require-all
                (path "/dev/null")
                (vnode-type CHARACTER-DEVICE)))

            ; sysctls permitted.
            (allow sysctl-read
              (sysctl-name "hw.activecpu")
              (sysctl-name "hw.busfrequency_compat")
              (sysctl-name "hw.byteorder")
              (sysctl-name "hw.cacheconfig")
              (sysctl-name "hw.cachelinesize_compat")
              (sysctl-name "hw.cpufamily")
              (sysctl-name "hw.cpufrequency_compat")
              (sysctl-name "hw.cputype")
              (sysctl-name "hw.l1dcachesize_compat")
              (sysctl-name "hw.l1icachesize_compat")
              (sysctl-name "hw.l2cachesize_compat")
              (sysctl-name "hw.l3cachesize_compat")
              (sysctl-name "hw.logicalcpu_max")
              (sysctl-name "hw.machine")
              (sysctl-name "hw.model")
              (sysctl-name "hw.memsize")
              (sysctl-name "hw.ncpu")
              (sysctl-name "hw.nperflevels")
              ; Chrome locks these CPU feature detection down a bit more tightly,
              ; but mostly for fingerprinting concerns which isn't an issue for codex.
              (sysctl-name-prefix "hw.optional.arm.")
              (sysctl-name-prefix "hw.optional.armv8_")
              (sysctl-name "hw.packages")
              (sysctl-name "hw.pagesize_compat")
              (sysctl-name "hw.pagesize")
              (sysctl-name "hw.physicalcpu")
              (sysctl-name "hw.physicalcpu_max")
              (sysctl-name "hw.logicalcpu")
              (sysctl-name "hw.cpufrequency")
              (sysctl-name "hw.tbfrequency_compat")
              (sysctl-name "hw.vectorunit")
              (sysctl-name "machdep.cpu.brand_string")
              (sysctl-name "kern.argmax")
              (sysctl-name "kern.hostname")
              (sysctl-name "kern.maxfilesperproc")
              (sysctl-name "kern.maxproc")
              (sysctl-name "kern.osproductversion")
              (sysctl-name "kern.osrelease")
              (sysctl-name "kern.ostype")
              (sysctl-name "kern.osvariant_status")
              (sysctl-name "kern.osversion")
              (sysctl-name "kern.secure_kernel")
              (sysctl-name "kern.usrstack64")
              (sysctl-name "kern.version")
              (sysctl-name "sysctl.proc_cputype")
              (sysctl-name "vm.loadavg")
              (sysctl-name-prefix "hw.perflevel")
              (sysctl-name-prefix "kern.proc.pgrp.")
              (sysctl-name-prefix "kern.proc.pid.")
              (sysctl-name-prefix "net.routetable.")
            )

            ; Allow Java to read some CPU info. This is misclassified as a "write" because
            ; userspace passes a memory buffer to the sysctl, but conceptually it is a read.
            (allow sysctl-write
              (sysctl-name "kern.grade_cputype"))

            ; IOKit
            (allow iokit-open
              (iokit-registry-entry-class "RootDomainUserClient")
            )

            ; needed to look up user info, see https://crbug.com/792228
            (allow mach-lookup
              (global-name "com.apple.system.opendirectoryd.libinfo")
            )

            ; Needed for python multiprocessing on MacOS for the SemLock
            (allow ipc-posix-sem)

            ; Needed for PyTorch/libomp on macOS to register OpenMP runtimes.
            (allow ipc-posix-shm-read-data
              ipc-posix-shm-write-create
              ipc-posix-shm-write-unlink
              (ipc-posix-name-regex #"^/__KMP_REGISTERED_LIB_[0-9]+$"))

            (allow mach-lookup
              (global-name "com.apple.PowerManagement.control")
            )

            ; allow openpty()
            (allow pseudo-tty)
            (allow file-read* file-write* file-ioctl (literal "/dev/ptmx"))
            (allow file-read* file-write*
              (require-all
                (regex #"^/dev/ttys[0-9]+")
                (extension "com.apple.sandbox.pty")))
            ; PTYs created before entering seatbelt may lack the extension; allow ioctl
            ; on those slave ttys so interactive shells detect a TTY and remain functional.
            (allow file-ioctl (regex #"^/dev/ttys[0-9]+"))

            ; allow readonly user preferences
            (allow ipc-posix-shm-read* (ipc-posix-name-prefix "apple.cfprefs."))
            (allow mach-lookup
              (global-name "com.apple.cfprefsd.daemon")
              (global-name "com.apple.cfprefsd.agent")
              (local-name "com.apple.cfprefsd.agent"))
            (allow user-preference-read)
            """;

    private static final String ADDITIONS = """

            ; ---------------------------------------------------------------------
            ; skill-manager's own rules. Everything above this line is upstream's.
            ; ---------------------------------------------------------------------

            ; READS ARE ALLOWED EVERYWHERE, deliberately. The thing being defended
            ; is "an agent wrote into a home nobody meant it to touch"; an agent
            ; that can no longer read /usr/lib, the JDK, its own git objects or the
            ; operator's caches is an agent that does not run. Confidentiality is
            ; not claimed and must not be inferred.
            (allow file-read*)
            (allow file-read-metadata)

            ; Operations that are not filesystem writes. The vendored base denies
            ; these by default and enumerates a narrow allowlist; that allowlist is
            ; correct for a short-lived Rust tool and wrong for `claude`, a JVM, a
            ; python venv build and `git clone`. Widened on purpose, and recorded
            ; here rather than by editing the vendored region.
            (allow sysctl-read)
            (allow mach*)
            (allow ipc-posix*)
            (allow signal)
            (allow process*)
            (allow pseudo-tty)
            (allow user-preference-read)

            ; NETWORK IS ALLOWED. An agent harness talks to its API, git fetches,
            ; and uv/npm/gradle download. This profile makes no network claim.
            (allow network*)
            (allow system-socket)

            ; Character devices. Everything a shell needs to be a shell.
            (allow file-write* file-ioctl
              (literal "/dev/null")
              (literal "/dev/zero")
              (literal "/dev/random")
              (literal "/dev/urandom")
              (literal "/dev/dtracehelper")
              (literal "/dev/tty")
              (literal "/dev/stdout")
              (literal "/dev/stderr")
              (subpath "/dev/fd"))

            ; THE WRITABLE ROOTS.
            ;
            ; OP_WORKTREE is the SOURCE TREE, not the home. An agent handed a
            ; ticket has to edit the repository the ticket is about, and
            ; <worktree>/.skill-manager, .claude, .codex and .gemini are all
            ; inside it already because home.runtime.json puts CLAUDE_CONFIG_DIR
            ; at $SKILL_MANAGER_HOME/../.claude. Making the home the writable root
            ; instead would confine the agent out of its own job.
            (allow file-write* file-ioctl (subpath (param "OP_WORKTREE")))

            ; OP_STORE is the skill-manager home. Normally inside OP_WORKTREE and
            ; therefore redundant; not redundant when --home-root moves the agent
            ; config root away from the store's parent.
            (allow file-write* file-ioctl (subpath (param "OP_STORE")))

            ; Temporary directories. Without OP_TMPDIR, mktemp(1) fails and git
            ; degrades. OP_SYSTMP is a MEASURED necessity and a deliberate
            ; widening: /bin/bash 3.2 writes here-document bodies to /tmp and
            ; ignores TMPDIR, so without it every `bash -c 'cat <<EOF'` in an
            ; agent session dies with "cannot create temp file for here document".
            ; /tmp is world-writable and sticky already; nothing this profile
            ; protects lives there. If you place a test decoy under /tmp, this
            ; rule makes it writable — put decoys elsewhere.
            (allow file-write* file-ioctl (subpath (param "OP_TMPDIR")))
            (allow file-write* file-ioctl (subpath (param "OP_SYSTMP")))
            (allow file-write* file-ioctl (subpath (param "OP_VARTMP")))
            """;

    /** The profile {@code home shims --sandbox} writes. */
    public static String defaultProfile() {
        return HEADER + VENDOR_BEGIN + "\n" + VENDORED + VENDOR_END + "\n" + ADDITIONS;
    }

    /**
     * The vendored upstream region, byte-for-byte as fetched. Its sha256 is
     * {@link #VENDORED_SHA256}; a test asserts the two agree, so an edit inside
     * the region fails rather than quietly forking upstream.
     */
    public static String vendoredBase() {
        return VENDORED;
    }

    /** sha256 of {@code text}, lowercase hex. */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Write the default profile to {@code file}, creating parents. */
    public static void install(Path file) throws IOException {
        if (file.getParent() != null) Fs.ensureDir(file.getParent());
        Files.writeString(file, defaultProfile());
    }

    // ------------------------------------------------------------ validate

    /** One reason a profile is not trustworthy, with the text that caused it. */
    public record Problem(Kind kind, int line, String detail) {
        public enum Kind {
            /** No {@code (deny default)} — the profile is allow-by-default. */
            NOT_DENY_BY_DEFAULT,
            /** An {@code (allow default)} rule, which undoes everything. */
            ALLOW_DEFAULT,
            /**
             * A rule granting a filesystem write to something that is neither a
             * caller-supplied {@code (param "…")} nor a path under {@code /dev/}.
             * This is the trailing-broad-allow class, caught wherever it sits.
             */
            BROAD_WRITE_ALLOW,
            /** Unbalanced parentheses — sandbox-exec would reject it anyway. */
            UNPARSEABLE
        }

        @Override
        public String toString() {
            return kind + " at line " + line + ": " + detail;
        }
    }

    /**
     * Every reason {@code text} would be untrustworthy, empty when there is
     * none.
     *
     * <p>Deliberately a list rather than a boolean: the caller prints all of
     * them, because a profile with two broad allows fixed one at a time is two
     * launches that reported "now it is fine" and were not.
     */
    public static List<Problem> validate(String text) {
        List<Problem> problems = new ArrayList<>();
        if (text == null || text.isBlank()) {
            problems.add(new Problem(Problem.Kind.UNPARSEABLE, 1, "the profile is empty"));
            return problems;
        }
        List<Form> forms;
        try {
            forms = topLevelForms(text);
        } catch (IllegalArgumentException unbalanced) {
            problems.add(new Problem(Problem.Kind.UNPARSEABLE, 1, unbalanced.getMessage()));
            return problems;
        }
        boolean denyDefault = false;
        for (Form form : forms) {
            List<String> atoms = form.atoms();
            if (atoms.isEmpty()) continue;
            String head = atoms.get(0);
            boolean mentionsDefault = atoms.size() > 1 && atoms.contains("default");
            if ("deny".equals(head) && mentionsDefault) denyDefault = true;
            if ("allow".equals(head) && mentionsDefault) {
                problems.add(new Problem(Problem.Kind.ALLOW_DEFAULT, form.line(),
                        "(allow default) grants every operation; nothing after it is a boundary"));
            }
            if (!"allow".equals(head)) continue;
            boolean grantsWrite = atoms.stream().anyMatch(WRITE_OPERATIONS::contains);
            if (!grantsWrite) continue;
            if (form.text().contains("(param ")) continue;      // bound by the caller
            List<String> paths = form.pathLiterals();
            if (paths.isEmpty()) {
                problems.add(new Problem(Problem.Kind.BROAD_WRITE_ALLOW, form.line(),
                        "a write is granted with no path filter at all: " + form.oneLine()));
                continue;
            }
            for (String path : paths) {
                if (isDevicePath(path)) continue;
                problems.add(new Problem(Problem.Kind.BROAD_WRITE_ALLOW, form.line(),
                        "a write is granted to " + path + ", which is neither a (param \"…\") "
                                + "nor a /dev/ device: " + form.oneLine()));
            }
        }
        if (!denyDefault) {
            problems.add(new Problem(Problem.Kind.NOT_DENY_BY_DEFAULT, 1,
                    "the profile never says (deny default), so unmatched operations are ALLOWED"));
        }
        return problems;
    }

    /**
     * {@code /dev/x} and {@code ^/dev/x} both count. A regex is accepted only
     * when it is anchored at {@code /dev/}: an unanchored one matches anywhere
     * in the path and {@code #"/dev/null"} would then also match
     * {@code /Users/me/dev/null-ish}.
     */
    private static boolean isDevicePath(String path) {
        return path.startsWith("/dev/") || path.startsWith("^/dev/");
    }

    // -------------------------------------------------------------- parser

    /** One top-level parenthesised form, with the 1-based line it starts on. */
    private record Form(String text, int line) {
        /**
         * The bare symbols in the form, quoted strings excluded. Enough to ask
         * "is this an allow", "does it name a write operation".
         */
        List<String> atoms() {
            List<String> out = new ArrayList<>();
            StringBuilder token = new StringBuilder();
            boolean inString = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') {
                    inString = true;
                    flush(out, token);
                    continue;
                }
                if (c == '(' || c == ')' || Character.isWhitespace(c) || c == '#') {
                    flush(out, token);
                } else {
                    token.append(c);
                }
            }
            flush(out, token);
            return out;
        }

        private static void flush(List<String> out, StringBuilder token) {
            if (token.length() > 0) {
                out.add(token.toString());
                token.setLength(0);
            }
        }

        /**
         * The quoted argument of every path-bearing filter in the form, in
         * order. {@code (subpath "/x")} yields {@code /x}; {@code (regex
         * #"^/dev/ttys[0-9]+")} yields {@code ^/dev/ttys[0-9]+}. A quoted
         * string belonging to any other filter — {@code (extension "…")},
         * {@code (sysctl-name "…")} — is not a path and is skipped, which is
         * what lets the vendored PTY rule through without a special case.
         */
        List<String> pathLiterals() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) != '(') continue;
                int j = i + 1;
                while (j < text.length() && !Character.isWhitespace(text.charAt(j))
                        && text.charAt(j) != ')' && text.charAt(j) != '(') j++;
                String name = text.substring(i + 1, j);
                if (!PATH_FILTERS.contains(name)) continue;
                int quote = text.indexOf('"', j);
                if (quote < 0) continue;
                int end = text.indexOf('"', quote + 1);
                if (end < 0) continue;
                out.add(text.substring(quote + 1, end));
            }
            return out;
        }

        String oneLine() {
            String flat = text.replaceAll("\\s+", " ").trim();
            return flat.length() > 160 ? flat.substring(0, 157) + "..." : flat;
        }
    }

    /**
     * Split {@code text} into its top-level forms.
     *
     * <p>Comments ({@code ;} to end of line) and string contents are skipped
     * when counting parentheses, because a {@code ;} inside a string or a
     * {@code )} inside a comment would otherwise desynchronise the scan — and a
     * desynchronised scan is a validator that reports "no problems" because it
     * could not look, which is the failure mode this whole ticket is about.
     */
    private static List<Form> topLevelForms(String text) {
        List<Form> forms = new ArrayList<>();
        int depth = 0;
        int start = -1;
        int startLine = 1;
        int line = 1;
        boolean inString = false;
        boolean inComment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                inComment = false;
                continue;
            }
            if (inComment) continue;
            if (inString) {
                if (c == '"') inString = false;
                continue;
            }
            switch (c) {
                case ';' -> inComment = true;
                case '"' -> inString = true;
                case '(' -> {
                    if (depth == 0) {
                        start = i;
                        startLine = line;
                    }
                    depth++;
                }
                case ')' -> {
                    depth--;
                    if (depth < 0) {
                        throw new IllegalArgumentException(
                                "unbalanced ')' at line " + line);
                    }
                    if (depth == 0 && start >= 0) {
                        forms.add(new Form(text.substring(start, i + 1), startLine));
                        start = -1;
                    }
                }
                default -> { }
            }
        }
        if (depth != 0) throw new IllegalArgumentException("unbalanced '(' — " + depth + " unclosed");
        return forms;
    }
}
