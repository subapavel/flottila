package dev.rail8.jq.query.ir;

/** A node's text in the filter: the UTF-8 byte offsets {@code [start, end)} into the filter text. */
public record Source(int start, int end) {}
