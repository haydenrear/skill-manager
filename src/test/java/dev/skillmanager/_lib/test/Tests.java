package dev.skillmanager._lib.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal test runner. Each test class exposes a {@code static int run()}
 * that calls {@link #suite(String)} to build a {@link Suite}, registers
 * cases via {@link Suite#test}, and returns {@link Suite#runAll()}.
 *
 * <p>This is the project's Layer-2 test substrate. JBang-style: no
 * external test framework, no Gradle, runs via the {@code RunTests}
 * script at the repo root. Parameterized sweeps are explicit Java
 * loops that call {@code suite.test(...)} per cell.
 *
 * <p>Failing tests print a stack trace and contribute to the
 * suite's failure count; passing tests print one line each. Run
 * status is reported per suite and aggregated by the runner.
 */
public final class Tests {

    private Tests() {}

    public static Suite suite(String name) { return new Suite(name); }

    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    public static final class Suite {
        private final String name;
        private final List<Case> cases = new ArrayList<>();

        private Suite(String name) { this.name = name; }

        public Suite test(String label, Body body) {
            cases.add(new Case(label, body));
            return this;
        }

        public int runAll() {
            System.out.println("== " + name + " (" + cases.size() + " cases)");
            int pass = 0, fail = 0;
            for (Case c : cases) {
                try {
                    c.body.run();
                    System.out.println("  [PASS] " + c.label);
                    pass++;
                } catch (Throwable t) {
                    System.out.println("  [FAIL] " + c.label + ": " + describe(t));
                    t.printStackTrace(System.out);
                    fail++;
                }
            }
            System.out.println("   → " + pass + " passed, " + fail + " failed");
            return fail;
        }

        private static String describe(Throwable t) {
            String msg = t.getMessage();
            return (msg == null ? t.getClass().getSimpleName() : msg);
        }
    }

    private record Case(String label, Body body) {}

    // --------------------------------------------------------- assertion helpers

    public static void assertEquals(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError("not true: " + what);
    }

    public static void assertFalse(boolean condition, String what) {
        if (condition) throw new AssertionError("expected false: " + what);
    }

    public static void assertNotNull(Object o, String what) {
        if (o == null) throw new AssertionError("expected non-null: " + what);
    }

    public static void assertContains(String haystack, String needle, String what) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(what + ": expected <" + haystack + "> to contain <" + needle + ">");
        }
    }

    public static <T> void assertSize(int expected, java.util.Collection<T> coll, String what) {
        if (coll == null || coll.size() != expected) {
            throw new AssertionError(what + ": expected size " + expected
                    + " but was " + (coll == null ? "null" : coll.size()));
        }
    }
}
