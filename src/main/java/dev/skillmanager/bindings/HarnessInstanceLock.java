package dev.skillmanager.bindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Per-instance metadata persisted at {@code <sandboxRoot>/<instanceId>/.harness-instance.json}
 * when {@code harness instantiate} runs. Captures the three target
 * paths the instantiator resolved so {@code sync harness:<name>} can
 * re-plan with the same layout without re-deriving them from the env
 * (which may have drifted) or scraping bindings.
 *
 * <p>Lives inside the sandbox dir alongside the user's resolved
 * targetDir — even when {@code projectDir} is elsewhere, the
 * {@code <sandbox>/<id>/} dir still exists as a thin marker holding
 * this file. {@code harness rm} deletes the whole sandbox dir, taking
 * the lock with it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HarnessInstanceLock(
        String harnessName,
        String instanceId,
        Path claudeConfigDir,
        Path codexHome,
        Path projectDir,
        String createdAt
) {

    public static final String FILENAME = ".harness-instance.json";

    public static Path file(Path sandboxRoot, String instanceId) {
        return sandboxRoot.resolve(instanceId).resolve(FILENAME);
    }

    public void write(Path sandboxRoot) throws IOException {
        Path f = file(sandboxRoot, instanceId);
        Files.createDirectories(f.getParent());
        BindingJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), this);
    }

    public static Optional<HarnessInstanceLock> read(Path sandboxRoot, String instanceId) {
        Path f = file(sandboxRoot, instanceId);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(BindingJson.MAPPER.readValue(f.toFile(), HarnessInstanceLock.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
