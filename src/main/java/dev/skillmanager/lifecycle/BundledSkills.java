package dev.skillmanager.lifecycle;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Skills that ship in-tree with the skill-manager CLI itself — installed
 * by {@code OnboardCommand}. Local onboard still records the GitHub remote
 * for these units so later {@code skill-manager sync} can fetch from the
 * real upstream instead of treating the install path as the source of truth.
 *
 * <p>Names match {@code [skill].name} from the manifest (the directory
 * is {@code skill-manager-skill/}, but the published name is
 * {@code skill-manager}).
 */
public final class BundledSkills {

    private BundledSkills() {}

    private static final Map<String, String> GITHUB_COORDS = Map.of(
            "skill-manager", "github:haydenrear/skill-manager-skill",
            "skill-publisher", "github:haydenrear/skill-publisher-skill",
            "skill-dev-skill", "github:haydenrear/skill-dev-skill"
    );

    private static final Set<String> NAMES = GITHUB_COORDS.keySet();

    public static boolean isBundled(String skillName) {
        return skillName != null && NAMES.contains(skillName);
    }

    public static Optional<String> githubCoord(String skillName) {
        return Optional.ofNullable(GITHUB_COORDS.get(skillName));
    }

    public static Optional<String> githubUrl(String skillName) {
        return githubCoord(skillName).map(coord -> {
            String body = coord.substring("github:".length());
            String url = "https://github.com/" + body;
            return url.endsWith(".git") ? url : url + ".git";
        });
    }
}
