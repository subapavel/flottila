package io.flottila.runtime;

/** Deliberate violation: beside Main and named like it, but not nested in it — the exemption is no name prefix. */
public final class MainHelper {
    private MainHelper() {}
    public static Class<?> step() { return io.flottila.steps.xslt.SaxonXml.class; }
}
