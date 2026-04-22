package dev.skillmanager.cli.installer;

import dev.skillmanager.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

final class Shell {

    private Shell() {}

    static int run(List<String> cmd) throws IOException {
        return run(cmd, Map.of());
    }

    static int run(List<String> cmd, Map<String, String> env) throws IOException {
        Log.step("exec: %s", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        pb.environment().putAll(env);
        Process p = pb.start();
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            throw new IOException("interrupted running " + cmd.get(0), e);
        }
    }

    static void must(List<String> cmd) throws IOException {
        int rc = run(cmd, Map.of());
        if (rc != 0) throw new IOException(cmd.get(0) + " exited " + rc);
    }

    static void mustWithEnv(List<String> cmd, Map<String, String> env) throws IOException {
        int rc = run(cmd, env);
        if (rc != 0) throw new IOException(cmd.get(0) + " exited " + rc);
    }

    /** Run {@code cmd} and return its stdout (trimmed). Returns null on non-zero exit. */
    static String capture(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        try {
            int rc = p.waitFor();
            if (rc != 0) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out.toString().trim();
    }
}
