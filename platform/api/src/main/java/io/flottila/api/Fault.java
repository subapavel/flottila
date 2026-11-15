package io.flottila.api;

/**
 * A failure that says whose fault it is (D-049): implemented by the exceptions a step or a producer throws, so the component
 * that speaks to the sender can answer by {@link Kind} without knowing the step's types (core and the components depend on
 * the api only). A failure that implements nothing of this is {@link Kind#INTERNAL} by default.
 *
 * @since 0.1
 */
public interface Fault {

    /** Whose fault the failure is. */
    Kind kind();

    /**
     * The classes of failure a sender can be told apart (http: 400, 422, 502 / 503, 500).
     *
     * @since 0.1
     */
    enum Kind {
        /** The input could not be read at all: not well-formed, not parseable. The sender's fault; the same bytes fail again. */
        MALFORMED,
        /** The input was read and broke a rule: a schema, a required value, a size limit. The sender's fault; the same bytes fail again. */
        INVALID,
        /** A system downstream refused or failed; whether the sender may retry is the failure's own answer. */
        DOWNSTREAM,
        /** The flow itself failed: a stylesheet error, a bug. Not the sender's fault. */
        INTERNAL
    }
}
