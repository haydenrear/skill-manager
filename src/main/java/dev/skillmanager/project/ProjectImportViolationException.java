package dev.skillmanager.project;

import dev.skillmanager.validation.MarkdownImportValidator;

import java.io.IOException;
import java.util.List;

/**
 * A {@code project resolve} refused to publish because the STAGED declared
 * closure carries markdown {@code skill-imports} naming units that are in
 * neither the candidate closure nor the installed store (issue #168).
 *
 * <p>Typed rather than a bare {@link IOException} because the caller needs to
 * distinguish "your declared closure's markdown names something that is not
 * there" — which exits with
 * {@link MarkdownImportValidator#EXIT_CODE} and leaves NO partial state,
 * since validation runs before any unit is committed — from an install
 * failure. Each carried {@link MarkdownImportValidator.Violation} attributes
 * the evidence: the importing unit, the file, and the missing target.
 *
 * <p>The registration written before dependency install retains the project's
 * declared intent; everything downstream of it — installed units, project
 * lock, child home, projections — is withheld when this is thrown.
 */
public final class ProjectImportViolationException extends IOException {

    private final transient List<MarkdownImportValidator.Violation> violations;

    public ProjectImportViolationException(List<MarkdownImportValidator.Violation> violations) {
        super("project resolve refused: declared closure has unresolved markdown skill-imports; "
                + "nothing was installed. Declare the missing unit in skill-project.toml or fix "
                + "the import.\n" + MarkdownImportValidator.format(violations));
        this.violations = List.copyOf(violations);
    }

    public List<MarkdownImportValidator.Violation> violations() {
        return violations;
    }
}
