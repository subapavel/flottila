package io.flottila.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Business correlation keys operators search by (invoiceId, orderNo, …). Filled by extractKey,
 * declared per flow, indexed in the monitoring store. Never reaches producers.
 */
public record Keys(Map<String, String> asMap) {
    public Keys { asMap = Map.copyOf(asMap); }

    public static Keys empty() { return new Keys(Map.of()); }

    public String get(String name) { return asMap.get(name); }
    public Keys with(String name, String value) { var m = new HashMap<>(asMap); m.put(name, value); return new Keys(m); }
}
