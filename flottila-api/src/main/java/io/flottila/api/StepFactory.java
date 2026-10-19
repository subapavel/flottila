package io.flottila.api;

/**
 * Builds the {@link Stage} for one step type from its configuration — the step SPI, pluggable like a
 * {@link Component} (ARCHITECTURE §7: "steps are pluggable the same way"). Registered with the flow builder;
 * the built-in types ({@code to}, {@code route}, {@code filter}, {@code extractKey}, {@code validate}) are
 * not factories.
 *
 * @since 0.1
 */
public interface StepFactory {

    /** The {@code type} this factory builds, e.g. {@code xslt}. */
    String type();

    /** JSON Schema (as text) the step's own keys are validated against at deploy time. */
    String configSchema();

    /** What the step needs of the body; a factory that declares nothing is taken to need the whole body — the safe reading. */
    default BodyKind bodyKind() { return BodyKind.WHOLE; }

    /**
     * Builds the stage; an {@link IllegalArgumentException} naming the step and the key for a bad
     * configuration. The stage holds what it is given here and nothing else (CLAUDE.md).
     */
    Stage create(StepConfig step, StepResources resources);
}
