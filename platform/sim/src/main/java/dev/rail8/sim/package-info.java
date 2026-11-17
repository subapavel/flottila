/**
 * Deterministic simulation of the real core: one driver virtual thread runs the units START, POLL, RELEASE, WAKE
 * and ADVANCE over {@code FlowBuilder}, {@code Delivery}, {@code FlowWorker} and admission against in-memory stores and SPI
 * doubles, so at most one message thread is runnable at a time; seeds per {@link dev.rail8.sim.Stream}; an oracle of
 * {@link dev.rail8.sim.Check}s; a {@link dev.rail8.sim.Verdict} per run. Never shipped; nothing depends on it.
 */
package dev.rail8.sim;
