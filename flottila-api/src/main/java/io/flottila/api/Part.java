package io.flottila.api;

import java.util.Objects;

/**
 * A multipart part (mail, SOAP/MTOM, AS2/AS4) carried beside the main body, by reference.
 *
 * @since 0.1
 */
public record Part(String name, String contentType, Body body) {
    public Part {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
    }
}
