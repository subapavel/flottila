package dev.rail8.jq.query.ir;

/**
 * Runs closure parameter {@code param} of the {@link Function} it is in on its input: the closure runs in the
 * scope it was written in, and its call activation ticks one step. As a closure argument of a {@link Call} it is passed on
 * as it is, not run.
 */
public record ParamCall(Source source, int param) implements Node {}
