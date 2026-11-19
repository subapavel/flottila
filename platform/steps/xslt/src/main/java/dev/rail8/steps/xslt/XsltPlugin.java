package dev.rail8.steps.xslt;

import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.Plugin;

import java.util.List;

/**
 * The xslt module: the steps of {@link SaxonXml} and the expression language {@code xpath}, over one Saxon processor, so a
 * stylesheet and an XPath expression share its compiled forms.
 *
 * @since 0.1
 */
public final class XsltPlugin implements Plugin {

    private final SaxonXml xml = new SaxonXml();

    @Override public String name() { return "xslt"; }

    @Override public String api() { return API; }

    /** The steps of {@link SaxonXml#steps()}. */
    @Override public List<StepFactory> steps() { return xml.steps(); }

    /** The languages of {@link SaxonXml#languages()}: {@code xpath}. */
    @Override public List<Expressions> languages() { return xml.languages(); }
}
