package io.github.subapavel.jqj.query.ir;

/**
 * The results of {@code body}, on the Try's input, until it raises an error of its own; then {@code handler} runs on
 * that error's value and the body is abandoned (ADR-0008, ADR-0039). An error of what runs downstream of the body's
 * results passes the Try at its wrap depth, and so does an error of the handler; a break and a Termination are in no
 * catch set. There is always a handler: {@code try E} and {@code E?} take an Intrinsic that gives no result. It charges
 * no step of its own, entering the handler included.
 */
public record Try(Source source, Node body, Node handler) implements Node {}
