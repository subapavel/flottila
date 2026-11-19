package dev.rail8.mapping.studio;

/**
 * The RFC 9457 body of a refused call.
 *
 * @param type {@code urn:rail8:mapping:problem:<slug>}
 * @param title the slug's fixed summary
 * @param status the HTTP status, fixed by the slug
 * @param detail what was wrong with this request
 */
record Problem(String type, String title, int status, String detail) {}
