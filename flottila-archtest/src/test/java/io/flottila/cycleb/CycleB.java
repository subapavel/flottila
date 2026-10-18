package io.flottila.cycleb;

/** Deliberate violation: a package cycle with cyclea. */
public final class CycleB { public static Object a() { return new io.flottila.cyclea.CycleA(); } }
