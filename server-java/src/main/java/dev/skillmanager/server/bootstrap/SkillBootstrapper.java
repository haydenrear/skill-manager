package dev.skillmanager.server.bootstrap;

import dev.skillmanager.server.publish.PublishException;
import dev.skillmanager.server.publish.SkillPublishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Seeds the registry with the two skills the CLI's {@code onboard} command
 * pulls in: {@code skill-manager-skill} (CLI wrapper) and
 * {@code skill-publisher-skill} (authoring guide). Runs once at server
 * startup so a freshly-provisioned registry has them available without any
 * manual publish step.
 *
 * <p>Source-of-truth is the on-disk skill directories under the install
 * root — typically the repo checkout the JBang launcher was started from.
 * Resolution order:
 *
 * <ol>
 *   <li>{@code SKILL_MANAGER_INSTALL_DIR} env var (set by the CLI bash
 *       wrapper; the server JBang script doesn't set it itself, but
 *       operators can export it).</li>
 *   <li>{@code SKILL_MANAGER_BOOTSTRAP_DIR} env var — explicit override.</li>
 *   <li>Walk up from {@code user.dir} looking for a directory that
 *       contains both bundled skill subdirs.</li>
 * </ol>
 *
 * <p>If the skill source dirs can't be found we log a warning and skip
 * bootstrap rather than fail the server start — the registry is still
 * fully functional, just without these two pre-seeded.
 *
 * <p>Idempotent across restarts: name-ownership is claimed by
 * {@code "system"} on the first publish; subsequent calls hit
 * {@link PublishException.Conflict} (already published at the same
 * version) which we swallow. Bumping the version in
 * {@code skill-manager.toml} re-seeds automatically on next start.
 */
@Component
public final class SkillBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(SkillBootstrapper.class);

    /** Username recorded as the owner of system-bootstrapped skills. */
    private static final String SYSTEM_USER = "system";

    /**
     * Subdirectory names under the install root that ship as bundled skills.
     * Order matters only for log readability.
     */
    private static final List<String> BUNDLED_SKILLS = List.of(
            "skill-manager-skill",
            "skill-publisher-skill"
    );

    private final SkillPublishService publishService;

    public SkillBootstrapper(SkillPublishService publishService) {
        this.publishService = publishService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedBundledSkills() {
        Path root = resolveInstallRoot();
        if (root == null) {
            log.warn("SkillBootstrapper: could not locate install root containing {} — "
                    + "skipping bundled-skill seed. Set SKILL_MANAGER_BOOTSTRAP_DIR to override.",
                    BUNDLED_SKILLS);
            return;
        }
        log.info("SkillBootstrapper: seeding bundled skills from {}", root);
        for (String name : BUNDLED_SKILLS) {
            Path skillDir = root.resolve(name);
            try {
                seedOne(skillDir);
            } catch (Exception e) {
                // Don't let one bad skill block the rest, and don't let
                // the bootstrapper fail the whole server start.
                log.warn("SkillBootstrapper: failed to seed {}: {}", name, e.getMessage());
            }
        }
    }

    private void seedOne(Path skillDir) throws IOException {
        if (!Files.isDirectory(skillDir)) {
            log.warn("SkillBootstrapper: {} is not a directory — skipping", skillDir);
            return;
        }
        if (!Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
            log.warn("SkillBootstrapper: {} has no SKILL.md — skipping", skillDir);
            return;
        }
        ParsedManifest m = ParsedManifest.read(skillDir);
        byte[] payload = packTarGz(skillDir);
        try {
            publishService.publish(m.name, m.version, payload, SYSTEM_USER);
            log.info("SkillBootstrapper: seeded {}@{} ({} bytes)", m.name, m.version, payload.length);
        } catch (PublishException.Conflict alreadyPublished) {
            // Same version already in storage — expected on every restart
            // after the first. Quiet this to debug so the log stays clean.
            log.debug("SkillBootstrapper: {}@{} already published — skipping", m.name, m.version);
        } catch (PublishException.Forbidden ownership) {
            // Someone else (a real user) claimed this name first. Refuse
            // to silently re-seed under their identity — log loudly so
            // an operator notices.
            log.warn("SkillBootstrapper: name '{}' is owned by another user; "
                    + "leaving registry untouched", m.name);
        }
    }

    private static Path resolveInstallRoot() {
        String explicit = System.getenv("SKILL_MANAGER_BOOTSTRAP_DIR");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit).toAbsolutePath();
            return hasBundledSkills(p) ? p : null;
        }
        String installDir = System.getenv("SKILL_MANAGER_INSTALL_DIR");
        if (installDir != null && !installDir.isBlank()) {
            Path p = Path.of(installDir).toAbsolutePath();
            if (hasBundledSkills(p)) return p;
        }
        // Walk up from cwd. JBang typically starts the server from the
        // repo root, but be defensive against `cd` to a subdir.
        Path cur = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cur != null) {
            if (hasBundledSkills(cur)) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean hasBundledSkills(Path candidate) {
        for (String name : BUNDLED_SKILLS) {
            if (!Files.isRegularFile(candidate.resolve(name).resolve("skill-manager.toml"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Build an in-memory {@code .tar.gz} of {@code skillDir}, omitting
     * dotfiles. Mirrors {@code SkillPackager} on the CLI side but stays
     * inlined here to avoid pulling the CLI's TOML / model module into
     * the server jar just to re-package a directory.
     */
    private static byte[] packTarGz(Path skillDir) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (OutputStream bout = new BufferedOutputStream(buf);
             GZIPOutputStream gzip = new GZIPOutputStream(bout)) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.walk(skillDir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .forEach(files::add);
            }
            for (Path p : files) {
                String name = skillDir.relativize(p).toString().replace('\\', '/');
                byte[] data = Files.readAllBytes(p);
                writeTarEntry(gzip, name, data, Files.isExecutable(p));
            }
            // End-of-archive: two 512-byte zero blocks.
            gzip.write(new byte[1024]);
        }
        return buf.toByteArray();
    }

    private static void writeTarEntry(OutputStream out, String name, byte[] data, boolean executable) throws IOException {
        byte[] header = new byte[512];
        writeName(header, 0, 100, name);
        writeOctal(header, 100, 8, executable ? 0755 : 0644);
        writeOctal(header, 108, 8, 0);           // uid
        writeOctal(header, 116, 8, 0);           // gid
        writeOctal(header, 124, 12, data.length);
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000);
        for (int i = 148; i < 156; i++) header[i] = ' '; // checksum placeholder
        header[156] = '0'; // normal file
        writeName(header, 257, 6, "ustar");
        writeName(header, 263, 2, "00");
        long cksum = 0;
        for (byte b : header) cksum += (b & 0xff);
        writeOctal(header, 148, 7, cksum);
        header[155] = 0;

        out.write(header);
        out.write(data);
        int rem = data.length % 512;
        if (rem > 0) out.write(new byte[512 - rem]);
    }

    private static void writeName(byte[] buf, int off, int len, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(bytes.length, len);
        System.arraycopy(bytes, 0, buf, off, n);
    }

    private static void writeOctal(byte[] buf, int off, int len, long value) {
        String oct = Long.toOctalString(value);
        String padded = "0".repeat(Math.max(0, len - 1 - oct.length())) + oct;
        byte[] bytes = padded.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(bytes.length, len - 1);
        System.arraycopy(bytes, 0, buf, off, n);
        buf[off + len - 1] = 0;
    }

    /**
     * Tiny purpose-built reader for the {@code [skill]} table — we only
     * need name + version, so pulling in a full TOML parser on the server
     * side is overkill. Mirrors the format the rest of skill-manager
     * already produces.
     */
    private record ParsedManifest(String name, String version) {
        static ParsedManifest read(Path skillDir) throws IOException {
            Path toml = skillDir.resolve("skill-manager.toml");
            if (!Files.isRegularFile(toml)) {
                throw new IOException("missing skill-manager.toml in " + skillDir);
            }
            String name = null;
            String version = null;
            boolean inSkillTable = false;
            for (String raw : Files.readAllLines(toml)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    inSkillTable = line.equals("[skill]");
                    continue;
                }
                if (!inSkillTable) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String value = stripQuotes(line.substring(eq + 1).trim());
                switch (key) {
                    case "name" -> name = value;
                    case "version" -> version = value;
                    default -> { /* ignore */ }
                }
            }
            if (name == null || version == null) {
                throw new IOException("skill-manager.toml in " + skillDir
                        + " is missing [skill].name or [skill].version");
            }
            return new ParsedManifest(name, version);
        }

        private static String stripQuotes(String s) {
            if (s.length() >= 2
                    && (s.startsWith("\"") && s.endsWith("\"")
                        || s.startsWith("'") && s.endsWith("'"))) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }
    }
}
