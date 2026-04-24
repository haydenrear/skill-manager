package dev.skillmanager.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.model.McpDependency;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-server outcome of an install-time registration attempt. Emitted to stdout
 * as structured JSON so both humans and the invoking agent can react.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstallResult(
        String serverId,
        String status,                 // deployed | awaiting-init | error | registered
        String scope,                  // effective scope for this server
        String message,                // human-readable summary
        List<RequiredField> requiredInit,  // present when awaiting-init
        String error                   // present when status=error
) {
    public enum Status {
        /** Registered AND deployed successfully. */
        DEPLOYED("deployed"),
        /** Registered; agent must supply required init before deploying. */
        AWAITING_INIT("awaiting-init"),
        /** Registered but deploy failed (see error). */
        ERROR("error"),
        /** Registered, not auto-deployed (session scope). */
        REGISTERED("registered");

        public final String code;
        Status(String code) { this.code = code; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequiredField(String name, String description, boolean secret) {
        public static RequiredField of(McpDependency.InitField f) {
            return new RequiredField(f.name(), f.description(), f.secret());
        }
    }

    public static InstallResult deployed(String serverId, String scope, String message) {
        return new InstallResult(serverId, Status.DEPLOYED.code, scope, message, null, null);
    }

    public static InstallResult awaitingInit(String serverId, String scope, McpDependency dep, List<String> missingNames) {
        List<RequiredField> fields = new ArrayList<>();
        for (McpDependency.InitField f : dep.initSchema()) {
            if (missingNames.contains(f.name())) fields.add(RequiredField.of(f));
        }
        String message = String.format(
                "%s: scope=%s requires init vars %s — agent must supply them via deploy_mcp_server.",
                serverId, scope, missingNames);
        return new InstallResult(serverId, Status.AWAITING_INIT.code, scope, message, fields, null);
    }

    public static InstallResult registered(String serverId, String scope, String message) {
        return new InstallResult(serverId, Status.REGISTERED.code, scope, message, null, null);
    }

    public static InstallResult error(String serverId, String scope, String err) {
        return new InstallResult(serverId, Status.ERROR.code, scope, err, null, err);
    }
}
