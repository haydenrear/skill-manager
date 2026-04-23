package dev.skillmanager.server.publish;

/**
 * Typed publish-path failures. {@link SkillPublishService} throws these and
 * the controller maps them to HTTP statuses — keeps the service free of
 * Spring web imports.
 */
public sealed class PublishException extends RuntimeException
        permits PublishException.BadVersion,
                PublishException.Forbidden,
                PublishException.Conflict {

    protected PublishException(String message) { super(message); }

    public static final class BadVersion extends PublishException {
        public BadVersion(String message) { super(message); }
    }

    public static final class Forbidden extends PublishException {
        public Forbidden(String message) { super(message); }
    }

    public static final class Conflict extends PublishException {
        public Conflict(String message) { super(message); }
    }
}
