package dev.skillmanager.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * <b>One log file per invocation.</b> Everything the CLI would have printed
 * goes here; the console keeps only the verdict, the counts that constitute
 * evidence, and what the caller has to act on.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The commands an agent runs constantly — {@code sync}, {@code install},
 * {@code home clone} — printed one line per (unit × agent). A twenty-unit home
 * synced across three agents is sixty lines that say the same thing sixty
 * times, and the reader pays for every one of them. The information is not
 * worthless; it is just not worth reading on the happy path. So it is written
 * down and the path to it is named.
 *
 * <h2>Where the file lives, and why NOT inside the home</h2>
 *
 * <p>Under {@code $TMPDIR/skill-manager/logs} (override with
 * {@code $SKILL_MANAGER_LOG_DIR}), never under a Skill Manager home. The
 * obvious alternative — {@code <home>/logs/} — is wrong here for reasons that
 * are properties of this product rather than preferences:
 *
 * <ol>
 *   <li><b>A log inside a home changes the verdict about that home.</b>
 *       {@code home verify} walks the WHOLE destination byte-wise looking for
 *       the source home's path ({@code HomeCloner.verifyRoots}); it does not
 *       share the clone's {@code SKIPPED_DIRS}, which is the only place
 *       {@code logs/} is excluded. A {@code home clone} log necessarily names
 *       the source home, so writing it into the destination would make a
 *       correct clone fail its own verification. A diagnostic artifact must
 *       not be able to decide the thing it describes.</li>
 *   <li><b>Half these commands must not write to the home at all.</b>
 *       {@code home verify}, {@code home drift} and {@code home close-out}
 *       inspect a home they may not own and that may be frozen. "Do not write
 *       logs into a home the command is only reading" is not a special case to
 *       remember — it is every read-only command, so the rule is simply that
 *       the log never goes in a home.</li>
 *   <li><b>A clone would carry the source's logs.</b> {@code logs/} is skipped
 *       today; the moment it stops being skipped a copy inherits another run's
 *       diagnostics. Keeping logs out of the home keeps that question from
 *       arising.</li>
 * </ol>
 *
 * <p>The cost of the decision is that logs are not cleaned up with the home.
 * Paid for by {@link #prune()}: files older than {@link #KEEP_DAYS} days in the
 * log directory are removed on open, bounded work on a directory this class
 * owns outright.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>The file is created <b>lazily</b>, on the first line written. A
 *       {@code --help} or a command with nothing to say leaves no file.</li>
 *   <li>Nothing here ever throws. A log that cannot be written is not a reason
 *       for a command to fail; {@link #path()} then returns null and no path is
 *       advertised.</li>
 *   <li>{@link #demoted()} counts lines that reached the file and NOT the
 *       console. It is what decides whether naming the file is worth a line.</li>
 * </ul>
 */
public final class RunLog {

    /** Directory override, for tests and for operators who want logs kept. */
    public static final String DIR_ENV = "SKILL_MANAGER_LOG_DIR";

    /** How long a run log survives in the shared temp directory. */
    public static final int KEEP_DAYS = 7;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static RunLog current;

    private final String command;
    private final Path path;
    private BufferedWriter writer;
    private boolean broken;
    private int demoted;

    private RunLog(String command, Path path) {
        this.command = command;
        this.path = path;
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Arm a run log for {@code command}. Replaces any previous one (an
     * embedded caller running several commands in one JVM gets one file per
     * command, which is the same contract a subprocess gets).
     */
    public static synchronized void open(String command) {
        close();
        Path dir = directory();
        if (dir == null) return;
        String safe = command == null || command.isBlank()
                ? "skill-manager"
                : command.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        String name = safe + "-" + LocalDateTime.now().format(STAMP) + "-"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000))
                + ".log";
        current = new RunLog(safe, dir.resolve(name));
    }

    /** Flush and release the current log. Idempotent. */
    public static synchronized void close() {
        RunLog log = current;
        current = null;
        if (log == null) return;
        try {
            if (log.writer != null) log.writer.close();
        } catch (IOException ignored) {
            // A log that cannot be closed is still not a command failure.
        }
    }

    /**
     * The file this run's detail is in, or {@code null} when nothing has been
     * written to it (or when it could not be opened at all).
     */
    public static synchronized Path path() {
        RunLog log = current;
        return log == null || log.writer == null ? null : log.path;
    }

    /** Lines that reached the file and not the console. */
    public static synchronized int demoted() {
        RunLog log = current;
        return log == null ? 0 : log.demoted;
    }

    // ---------------------------------------------------------------- write

    /** Record a line that the console also showed. */
    public static synchronized void mirror(String line) {
        write(line, false);
    }

    /** Record a line the console did NOT show. */
    public static synchronized void demote(String line) {
        write(line, true);
    }

    private static void write(String line, boolean demotedLine) {
        RunLog log = current;
        if (log == null || log.broken) return;
        try {
            if (log.writer == null) {
                Files.createDirectories(log.path.getParent());
                log.writer = Files.newBufferedWriter(log.path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.writer.write("# skill-manager " + log.command + "  "
                        + Instant.now() + System.lineSeparator());
            }
            log.writer.write((demotedLine ? "  " : "> ") + line + System.lineSeparator());
            log.writer.flush();
        } catch (IOException | RuntimeException e) {
            // One failure disables the log for the rest of the run rather than
            // producing an error per line — the failure mode this whole class
            // exists to avoid.
            log.broken = true;
            return;
        }
        if (demotedLine) log.demoted++;
    }

    // ----------------------------------------------------------- directory

    /** {@code $SKILL_MANAGER_LOG_DIR}, else {@code $TMPDIR/skill-manager/logs}. */
    public static Path directory() {
        try {
            String override = System.getenv(DIR_ENV);
            if (override != null && !override.isBlank()) {
                return Path.of(override).toAbsolutePath().normalize();
            }
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp == null || tmp.isBlank()) return null;
            Path dir = Path.of(tmp).resolve("skill-manager").resolve("logs");
            prune(dir);
            return dir;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Delete run logs older than {@link #KEEP_DAYS}. Bounded, best-effort, and
     * confined to the directory this class owns — it never touches a home.
     */
    static void prune(Path dir) {
        if (!Files.isDirectory(dir)) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(KEEP_DAYS));
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".log")).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                    // Someone else's file, or a race with another run.
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // Pruning is housekeeping; it is never worth a failure.
        }
    }

}
