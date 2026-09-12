package io.flottila.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Transport metadata (HTTP/JMS/mail headers from ingress, http.status from egress).
 * Nothing propagates to producers implicitly — the flow config allow-lists via forwardHeaders.
 */
public record Headers(Map<String, String> asMap) {
    public Headers { asMap = Map.copyOf(asMap); }

    public static Headers empty() { return new Headers(Map.of()); }

    public String get(String k) { return asMap.get(k); }
    public String getOrDefault(String k, String def) { return asMap.getOrDefault(k, def); }
    public Headers with(String k, String v) { var m = new HashMap<>(asMap); m.put(k, v); return new Headers(m); }
    public Headers withAll(Map<String, String> extra) { var m = new HashMap<>(asMap); m.putAll(extra); return new Headers(m); }
}
