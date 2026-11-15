package dev.rail8.cyclea;

/** Deliberate violation: a package cycle with cycleb. */
public final class CycleA { public static Object b() { return new dev.rail8.cycleb.CycleB(); } }
