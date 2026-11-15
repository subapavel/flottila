package io.flottila.steps.validate;

import io.flottila.api.StepFactory;

import java.util.List;

/**
 * The validation steps of this module: one {@link XsdStepFactory} per runtime, so a schema shared by several
 * flows compiles once. Create one per runtime and register {@link #steps()} with the flow builder.
 *
 * @since 0.1
 */
public final class XsdValidate {

    private final XsdStepFactory xsd = new XsdStepFactory();

    /** The step factories of this module: {@code xsd}. */
    public List<StepFactory> steps() { return List.of(xsd); }
}
