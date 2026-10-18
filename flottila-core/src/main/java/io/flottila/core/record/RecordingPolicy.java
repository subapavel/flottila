package io.flottila.core.record;

import java.util.Map;
import java.util.Objects;

/**
 * Recording per flow, overridable per step (ARCHITECTURE §6; schema v1 {@code recording}): the level says which steps
 * leave a snapshot, the payload setting whether the body stays reachable from it. Default: everything, kept —
 * monitoring is the product.
 *
 * @since 0.1
 */
public record RecordingPolicy(Level level, Payload payload) {

    /** @since 0.1 */
    public enum Level { ALL, ENDS, ERRORS, OFF }

    /** @since 0.1 */
    public enum Payload { KEEP, METADATA_ONLY }

    public static final RecordingPolicy DEFAULT = new RecordingPolicy(Level.ALL, Payload.KEEP);

    public RecordingPolicy {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(payload, "payload");
    }

    /** The schema's {@code recording} object over {@code base}: a key not named keeps the base's value; {@code null} is the base itself. */
    public static RecordingPolicy from(Object node, RecordingPolicy base) {
        Objects.requireNonNull(base, "base");
        if (node == null) return base;
        if (!(node instanceof Map<?, ?> m)) throw new IllegalArgumentException("recording must be an object with level / payload");
        Level level = base.level;
        Payload payload = base.payload;
        if (m.get("level") != null) {
            level = switch (String.valueOf(m.get("level"))) {
                case "all" -> Level.ALL;
                case "ends" -> Level.ENDS;
                case "errors" -> Level.ERRORS;
                case "off" -> Level.OFF;
                default -> throw new IllegalArgumentException("recording.level must be all | ends | errors | off, was '" + m.get("level") + "'");
            };
        }
        if (m.get("payload") != null) {
            payload = switch (String.valueOf(m.get("payload"))) {
                case "keep" -> Payload.KEEP;
                case "metadata-only" -> Payload.METADATA_ONLY;
                default -> throw new IllegalArgumentException("recording.payload must be keep | metadata-only, was '" + m.get("payload") + "'");
            };
        }
        return new RecordingPolicy(level, payload);
    }

    /** Whether a snapshot is taken for {@code step}: ingress and the last step are ends, and so is any step where the message ends. */
    public boolean records(int step, boolean last, Snapshot.Outcome outcome) {
        return switch (level) {
            case ALL -> true;
            case ENDS -> step == 0 || last || outcome != Snapshot.Outcome.OK;
            case ERRORS -> outcome == Snapshot.Outcome.FAILED;
            case OFF -> false;
        };
    }

    /** Whether the snapshot carries the body's key (else hash and size only). */
    public boolean keepsBody() { return payload == Payload.KEEP; }
}
