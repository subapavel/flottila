package io.rail8.cycleb;

/** Deliberate violation: a package cycle with cyclea. */
public final class CycleB { public static Object a() { return new io.rail8.cyclea.CycleA(); } }
