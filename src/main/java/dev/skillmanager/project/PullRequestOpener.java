package dev.skillmanager.project;

import dev.skillmanager.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Opens the pull request that carries a unit's edits back to its trunk.
 *
 * <h2>Why a pull request is the default</h2>
 *
 * <p>A ticket agent improves a skill inside its own home; some other agent — or
 * a person — decides whether that improvement belongs on the skill's trunk. Those
 * are different jobs, and collapsing them means every home that pulls the trunk
 * inherits whatever the last agent thought. So the default is to propose, and
 * {@code --direct} is the escape hatch for the case where the proposer <em>is</em>
 * the reviewer.
 *
 * <h2>Why this is an interface</h2>
 *
 * <p>Opening a PR is the one step in the push-back that cannot be exercised
 * against a fixture: a bare repository on disk accepts a push but has no notion
 * of a pull request, and pointing the tests at a real forge would mean opening
 * real pull requests as a side effect of running the suite. Behind this seam the
 * tests drive the full commit → branch → push sequence against a local bare
 * repository and assert on the request that <em>would</em> be opened, which is
 * the whole contract minus the network call.
 */
public interface PullRequestOpener {

    record Request(Path repo, String remote, String headBranch, String baseBranch,
                   String title, String body) {}

    /**
     * @return the PR's URL, or empty when no pull request could be opened. Empty
     *         is not a failure: the branch is already pushed, so the caller
     *         reports how to open one by hand and exits successfully. Treating a
     *         missing forge CLI as a hard error would throw away a completed push.
     */
    Optional<String> open(Request request) throws IOException;

    /** The production opener: {@code gh pr create}. */
    static PullRequestOpener gh() { return new GhPullRequestOpener(); }

    /** An opener that never opens anything — {@code --no-pr} / dry runs. */
    static PullRequestOpener none() { return request -> Optional.empty(); }

    final class GhPullRequestOpener implements PullRequestOpener {

        @Override
        public Optional<String> open(Request request) throws IOException {
            if (!available()) {
                Log.warn("`gh` is not on PATH — the branch is pushed but no pull request was "
                        + "opened. Open one with: gh pr create --head %s --base %s",
                        request.headBranch(), request.baseBranch());
                return Optional.empty();
            }
            Result created = run(request.repo(), List.of(
                    "gh", "pr", "create",
                    "--head", request.headBranch(),
                    "--base", request.baseBranch(),
                    "--title", request.title(),
                    "--body", request.body()));
            if (created.exit != 0) {
                // A PR may already exist for this branch, which is the normal
                // shape of a second publish on the same ticket. Ask rather than
                // parse the error text.
                Result existing = run(request.repo(), List.of(
                        "gh", "pr", "view", request.headBranch(), "--json", "url", "--jq", ".url"));
                if (existing.exit == 0 && !existing.out.isBlank()) {
                    return Optional.of(existing.out.trim());
                }
                Log.warn("`gh pr create` failed (rc=%d): %s", created.exit, created.out.trim());
                return Optional.empty();
            }
            return firstUrl(created.out);
        }

        private static boolean available() {
            return run(null, List.of("gh", "--version")).exit == 0;
        }

        private static Optional<String> firstUrl(String output) {
            if (output == null) return Optional.empty();
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    return Optional.of(trimmed);
                }
            }
            return Optional.empty();
        }

        private record Result(int exit, String out) {}

        private static Result run(Path workdir, List<String> argv) {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            if (workdir != null) pb.directory(workdir.toFile());
            try {
                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) out.append(line).append('\n');
                }
                return new Result(p.waitFor(), out.toString());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return new Result(-1, e.getMessage() == null ? "" : e.getMessage());
            }
        }
    }
}
