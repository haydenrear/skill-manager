package dev.skillmanager.lifecycle;

import java.util.Set;

/**
 * Skills that ship in-tree with the skill-manager CLI itself — installed
 * by {@code OnboardCommand} from {@code SKILL_MANAGER_INSTALL_DIR/<dir>/}
 * rather than fetched from a git remote, so they have no {@code .git/}
 * in the store and would otherwise carry permanent
 * {@link dev.skillmanager.source.InstalledUnit.ErrorKind#NEEDS_GIT_MIGRATION}.
 *
 * <p>Suppression is a workaround. The real fix tracked in
 * <a href="https://github.com/haydenrear/skill-manager/issues/44">#44</a>
 * is to publish each one to its own repo so they go through the normal
 * github-source install path. Once that ships, this allowlist goes away.
 *
 * <p>Names match {@code [skill].name} from the manifest (the directory
 * is {@code skill-manager-skill/}, but the published name is
 * {@code skill-manager}).
 */
public final class BundledSkills {

    private BundledSkills() {}

    private static final Set<String> NAMES = Set.of("skill-manager", "skill-publisher");

    public static boolean isBundled(String skillName) {
        return skillName != null && NAMES.contains(skillName);
    }
}
