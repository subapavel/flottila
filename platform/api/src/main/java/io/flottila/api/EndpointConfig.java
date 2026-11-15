package io.flottila.api;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * The configuration a component receives for one endpoint, validated at deploy time against the
 * component's {@link Component#configSchema()}. Draft — review at hand-over.
 *
 * @param name endpoint name inside the flow
 * @param uri scheme-specific address ({@code http://…}, {@code sftp://…}, {@code mock://…})
 * @param options the remaining, scheme-specific settings from the flow config
 * @since 0.1
 */
public record EndpointConfig(String name, URI uri, Map<String, Object> options) {
    public EndpointConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(uri, "uri");
        options = Map.copyOf(options);
    }

    public String scheme() {
        return uri.getScheme();
    }
}
