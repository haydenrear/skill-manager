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
 *
 * <p>{@code skill-publisher} was replaced by the {@code skt} PLUGIN, which
 * its repo now ships (skill-manager-plugin.toml + contained skills). The
 * bundled entry is the plugin unit itself — the repo-root remote is the
 * correct upstream for a plugin, while a contained skill must never carry
 * it (a later sync would pull plugin-root content into a skill dir).
 */
public final class BundledSkills {

    private BundledSkills() {}

    // skill-dev-skill was here until OUN-4. It installed a `skill-dev` CLI
    // whose open/status/sync/git/close is now covered by `skt publish`, `skt
    // ticket` and `sync --from --merge`; `deps --who-imports skill-dev-skill`
    // reported zero importers in the only home that still held it.
    // `skill-manager` was here until OUN-13, and it CONTRADICTED
    // UnitSupersession.TABLE. That table retires the standalone
    // `skill-manager` against skt (MOVED_INTO_CARRIER); this map installed it
    // again on every fresh onboard, so a new home cloned
    // skill-manager-skill, installed the standalone, and the install path
    // retired it on the way through — work done in order to be undone, and
    // part of why a root sync visibly re-cloned the bundled coordinates.
    //
    // The skill still arrives: skt carries `skills/skill-manager`, which is
    // the whole point of MOVED_INTO_CARRIER. Nothing is lost by not bundling
    // it separately, and `bundledUnitIsNotSuperseded` fails if the two tables
    // ever disagree again.
    private static final Map<String, String> GITHUB_COORDS = Map.of(
            "skt", "github:haydenrear/skill-publisher-skill"
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
