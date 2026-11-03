/**
 * Deterministic simulation of the real core (F-25, D-101): one driver virtual thread runs the units START, POLL, RELEASE, WAKE
 * and ADVANCE over {@code FlowBuilder}, {@code Delivery}, {@code FlowWorker} and admission against in-memory stores and SPI
 * doubles, so at most one message thread is runnable at a time; seeds per {@link io.flottila.sim.Stream}; an oracle of
 * {@link io.flottila.sim.Check}s; a {@link io.flottila.sim.Verdict} per run. Never shipped; nothing depends on it.
 */
package io.flottila.sim;
