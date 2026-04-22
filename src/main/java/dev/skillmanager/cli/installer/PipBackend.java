package dev.skillmanager.cli.installer;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.pm.PackageManager;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Install pip packages using uv. Auto-installs a bundled uv into the
 * skill-manager {@code pm/} directory on first use so the install is not
 * dependent on whatever pip/python is sitting on the user's PATH.
 */
public final class PipBackend implements InstallerBackend {

    @Override public String id() { return "pip"; }

    @Override public boolean available() { return true; }

    @Override
    public void install(CliDependency dep, SkillStore store, String skillName) throws IOException {
        if (dep.onPath() != null && isOnPath(dep.onPath())) {
            Log.ok("cli: %s already on PATH", dep.onPath());
            return;
        }
        String pkg = dep.packageRef();
        if (pkg == null || pkg.isBlank()) throw new IOException("pip: spec missing package name (pip:<package>)");
        Fs.ensureDir(store.cliBinDir());
        Fs.ensureDir(store.venvsDir());

        PackageManagerRuntime pm = new PackageManagerRuntime(store);
        if (pm.bundledPath("uv") == null) {
            Log.step("pip: bootstrapping bundled uv@%s", PackageManager.UV.defaultVersion);
        }
        String uv = pm.ensureBundled("uv");

        Map<String, String> env = Map.of(
                "UV_TOOL_BIN_DIR", store.cliBinDir().toString(),
                "UV_TOOL_DIR", store.venvsDir().toString()
        );
        Shell.mustWithEnv(List.of(uv, "tool", "install", "--force", pkg), env);
        Log.ok("cli: installed %s via uv tool (bin=%s)", pkg, store.cliBinDir());
    }
}
