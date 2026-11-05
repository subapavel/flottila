package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The per-sample series of the open record (CORE-15b): one entry per backlog sample, null where the sample could not be read.
 * A delta series' first entry is 0, the window's start being the baseline, and each later one the value minus the one before.
 *
 * @since 0.1
 */
final class Series {

    /** The note of an unknown entry whose source gave no reason. */
    static final String NOT_READ = "not read";

    private Series() {}

    /** The deltas of {@code values}: the first 0, each later one minus the one before; null where either value is unknown. */
    static List<Long> deltas(List<Long> values) {
        List<Long> out = new ArrayList<>();
        for (int k = 0; k < values.size(); k++) {
            Long now = values.get(k);
            Long before = k == 0 ? now : values.get(k - 1);
            out.add(now == null || before == null ? null : now - before);
        }
        return out;
    }

    /** {@link #deltas(List)} of decimal values. */
    static List<Double> decimalDeltas(List<Double> values) {
        List<Double> out = new ArrayList<>();
        for (int k = 0; k < values.size(); k++) {
            Double now = values.get(k);
            Double before = k == 0 ? now : values.get(k - 1);
            out.add(now == null || before == null ? null : now - before);
        }
        return out;
    }

    /**
     * Puts {@code entries} into {@code out} under {@code field}: JSON null when no entry is known (no sample included: never 0,
     * never an empty list), else the entries; when any entry is unknown {@code notes.<field>} carries the first of
     * {@code reasons} (one per sample, null where the sample was read), or {@value #NOT_READ}.
     */
    static void put(JsonObject out, JsonObject notes, String field, List<?> entries, List<String> reasons) {
        String reason = reasons.stream().filter(Objects::nonNull).findFirst().orElse(NOT_READ);
        if (entries.stream().allMatch(Objects::isNull)) {
            out.putNull(field);
            notes.put(field, reason);
        } else {
            out.put(field, new JsonArray(new ArrayList<>(entries)));
            if (entries.contains(null)) notes.put(field, reason);
        }
    }
}
