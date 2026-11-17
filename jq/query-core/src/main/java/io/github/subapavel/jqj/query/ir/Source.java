package io.github.subapavel.jqj.query.ir;

/** A node's text in the filter: the UTF-8 byte offsets {@code [start, end)} into the filter text (ADR-0031). */
public record Source(int start, int end) {}
