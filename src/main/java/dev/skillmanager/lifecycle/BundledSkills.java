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
    // `skill-manager` IS STILL HERE, AND DEF-OUN-017 EXPLAINS WHY REMOVING IT
    // IS NOT A ONE-LINE CHANGE.
    //
    // The complaint is real: UnitSupersession.TABLE retires the standalone
    // `skill-manager` into skt, and this map installs it again on every fresh
    // onboard, so the install path retires it on the way through. Two tables
    // describing one fact and disagreeing.
    //
    // But deleting the entry does NOT stop it being installed -- the onboard
    // seeds from the local install dir, whose tree carries
    // `skill-manager-skill/`, so the unit arrives either way. What the entry
    // does is convert that local source to its GIT provenance. Without it the
    // unit is installed with LOCAL_DIR provenance and no upstream at all:
    // `skt check` reports it unverifiable and `sync` has nothing to pull
    // from. Measured by the onboard graph, which went red on exactly that
    // (`managerRemote=false`, every other assertion green).
    //
    // A unit installed with no way to update it is worse than a unit
    // installed and retired. So the entry stays until the SEEDING stops --
    // the standalone must not be seeded at all, which is OUN-6's end state
    // and a change to what onboarding installs rather than to what provenance
    // it records.
    private static final Map<String, String> GITHUB_COORDS = Map.of(
            "skill-manager", "github:haydenrear/skill-manager-skill",
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
