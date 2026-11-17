package dev.rail8.jq.query.ir;

/**
 * A function of a {@link Program} (GLOSSARY "Lifted function", ADR-0045 and its #107 amendment), which is no node: its
 * body takes {@code closures} closure parameters, which a {@link ParamCall} runs, and {@code values} value parameters,
 * slots {@code 0 .. values - 1} of the body, in scope in it and bound by no node. The body sees no slot of its caller.
 */
public record Function(Source source, int closures, int values, Node body) {}
