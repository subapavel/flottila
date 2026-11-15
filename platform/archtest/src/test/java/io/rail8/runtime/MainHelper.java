package io.rail8.runtime;

/** Deliberate violation: beside Main and named like it, but not nested in it — the exemption is no name prefix. */
public final class MainHelper {
    private MainHelper() {}
    public static Class<?> step() { return io.rail8.steps.xslt.SaxonXml.class; }
}
