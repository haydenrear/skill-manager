package dev.skillmanager._lib.fakes;

/**
 * Stub for the in-memory filesystem fake. Ticket 01 introduces the
 * package; tickets that need fast in-process IO (notably tickets 06–09
 * for handler substitutability and failure-injection sweeps) flesh
 * out the implementation. Until then, ticket-01 parser tests use real
 * temp directories — they're testing on-disk parsing, so an in-memory
 * shortcut would skip the IO path under test.
 *
 * <p>When this fake gains substance, the surface should cover:
 * <ul>
 *   <li>{@code mkdir} / {@code write} / {@code read} / {@code exists} /
 *       {@code list} for files and directories;</li>
 *   <li>symlink create + resolve (the projector exercises symlink
 *       semantics heavily);</li>
 *   <li>scriptable failures keyed by path so tests can force
 *       "filesystem returned EIO on the third write" scenarios for
 *       failure-injection sweeps.</li>
 * </ul>
 */
public final class InMemoryFs {
    private InMemoryFs() {}
}
