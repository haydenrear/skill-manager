package dev.skillmanager.commands;

import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.registry.SkillPackager;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "publish", description = "Package a skill and upload it to the registry.")
public final class PublishCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1",
            description = "Skill source directory (defaults to current working directory)")
    Path skillDir;

    @Option(names = "--version", description = "Override version; otherwise taken from skill-manager.toml [skill].version")
    String version;

    @Option(names = "--registry", description = "Registry base URL override")
    String registryUrl;

    @Option(names = "--dry-run", description = "Build the tarball but do not upload")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();

        Path src = (skillDir == null ? Path.of(System.getProperty("user.dir")) : skillDir).toAbsolutePath();
        if (!Files.isRegularFile(src.resolve(SkillParser.SKILL_FILENAME))) {
            Log.error("not a skill directory (no %s): %s", SkillParser.SKILL_FILENAME, src);
            return 1;
        }

        Skill skill = SkillParser.load(src);
        String effectiveVersion = version != null ? version : skill.version();
        if (effectiveVersion == null || effectiveVersion.isBlank()) {
            Log.error("skill has no version (set [skill].version in skill-manager.toml or pass --version)");
            return 1;
        }

        Path outDir = store.cacheDir().resolve("publish");
        Fs.ensureDir(outDir);
        Path tar = SkillPackager.pack(src, outDir);
        Log.ok("packaged %s (%d bytes) → %s", skill.name(), Files.size(tar), tar);

        if (dryRun) {
            Log.info("--dry-run: not uploading");
            return 0;
        }

        RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
        RegistryClient client = new RegistryClient(cfg);
        if (!client.ping()) {
            Log.error("registry not reachable at %s", cfg.baseUrl());
            return 2;
        }

        var result = client.publish(skill.name(), effectiveVersion, tar);
        Log.ok("published %s@%s (sha256=%s, %d bytes)", result.name(), result.version(), result.sha256(), result.sizeBytes());
        Log.info("download: %s%s", cfg.baseUrl(), result.downloadUrl());
        return 0;
    }
}
