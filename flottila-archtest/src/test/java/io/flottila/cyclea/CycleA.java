package io.flottila.cyclea;

/** Deliberate violation: a package cycle with cycleb. */
public final class CycleA { public static Object b() { return new io.flottila.cycleb.CycleB(); } }
