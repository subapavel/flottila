package dev.rail8.mapping.studio;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A runtime error of a test run, on the wire.
 *
 * @param code the error's QName, such as {@code po:MINOCCURS} or {@code rail8:TIMEOUT}
 * @param message what stopped the run, in English
 * @param authoringId where the error was raised: the target field mapping for {@code po:MINOCCURS},
 *     the function box for a context function's data error; null, so an absent member, for an error
 *     of the test run itself such as {@code rail8:TIMEOUT}
 */
record RuntimeErrorDto(
    String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) String authoringId) {}
