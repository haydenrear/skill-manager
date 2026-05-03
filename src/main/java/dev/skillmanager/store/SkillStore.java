package dev.skillmanager.store;

import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class SkillStore {

    private final Path root;
    private final Path skillsDir;
    private final Path binDir;
    private final Path cliBinDir;
    private final Path mcpBinDir;
    private final Path venvsDir;
    private final Path npmDir;
    private final Path cacheDir;
    private final Path sourcesDir;

    public SkillStore(Path root) {
        this.root = root;
        this.skillsDir = root.resolve("skills");
        this.binDir = root.resolve("bin");
        this.cliBinDir = binDir.resolve("cli");
        this.mcpBinDir = binDir.resolve("mcp");
        this.venvsDir = root.resolve("venvs");
        this.npmDir = root.resolve("npm");
        this.cacheDir = root.resolve("cache");
        // Per-skill provenance JSON: kind (git / local), origin, git hash,
        // version. Lives outside skills/<name>/ so file ops on the skill
        // dir (delete + copy during sync) don't blow it away.
        this.sourcesDir = root.resolve("sources");
    }

    public static SkillStore defaultStore() {
        String env = System.getenv("SKILL_MANAGER_HOME");
        Path root = env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".skill-manager");
        return new SkillStore(root);
    }

    public Path root() { return root; }
    public Path skillsDir() { return skillsDir; }
    public Path binDir() { return binDir; }
    public Path cliBinDir() { return cliBinDir; }
    public Path mcpBinDir() { return mcpBinDir; }
    public Path venvsDir() { return venvsDir; }
    public Path npmDir() { return npmDir; }
    public Path cacheDir() { return cacheDir; }
    public Path sourcesDir() { return sourcesDir; }

    public void init() throws IOException {
        Fs.ensureDir(root);
        Fs.ensureDir(skillsDir);
        Fs.ensureDir(binDir);
        Fs.ensureDir(cliBinDir);
        Fs.ensureDir(mcpBinDir);
        Fs.ensureDir(venvsDir);
        Fs.ensureDir(npmDir);
        Fs.ensureDir(cacheDir);
        Fs.ensureDir(sourcesDir);
    }

    public Path skillDir(String name) {
        return skillsDir.resolve(name);
    }

    public boolean contains(String name) {
        return Files.isDirectory(skillDir(name)) && Files.isRegularFile(skillDir(name).resolve(SkillParser.SKILL_FILENAME));
    }

    public Optional<Skill> load(String name) throws IOException {
        Path d = skillDir(name);
        if (!Files.isDirectory(d)) return Optional.empty();
        return Optional.of(SkillParser.load(d));
    }

    public List<Skill> listInstalled() throws IOException {
        List<Skill> out = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) return out;
        try (Stream<Path> s = Files.list(skillsDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(p)) continue;
                if (!Files.isRegularFile(p.resolve(SkillParser.SKILL_FILENAME))) continue;
                try {
                    out.add(SkillParser.load(p));
                } catch (IOException e) {
                    // skip unreadable skills
                }
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    public void remove(String name) throws IOException {
        Path d = skillDir(name);
        if (Files.exists(d)) Fs.deleteRecursive(d);
    }
}
