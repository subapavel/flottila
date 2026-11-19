package dev.rail8.steps.validate;

import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.Plugin;

import java.util.List;

/**
 * The xsd module: the validation steps of {@link XsdValidate}, {@code xsd}.
 *
 * @since 0.1
 */
public final class XsdPlugin implements Plugin {

    @Override public String name() { return "xsd"; }

    @Override public String api() { return API; }

    /** The steps of {@link XsdValidate#steps()}. */
    @Override public List<StepFactory> steps() { return new XsdValidate().steps(); }
}
