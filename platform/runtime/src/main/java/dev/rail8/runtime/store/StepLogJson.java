package dev.rail8.runtime.store;

import dev.rail8.core.record.StepLog;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A step's {@link StepLog} as JSON, the form the {@code messages.log} column keeps and the admin timeline shows:
 * {@code {"lines":[{"at","level","logger","text","thrown"?}],"dropped":n}}, {@code at} in ISO-8601 and {@code level} a
 * {@code System.Logger} level's name; {@code thrown} is there only on a line logged with a failure.
 *
 * @since 0.1
 */
public final class StepLogJson {

    private StepLogJson() {}

    /** {@code log} as JSON. */
    public static JsonObject json(StepLog log) {
        JsonArray lines = new JsonArray();
        for (StepLog.Line l : log.lines()) {
            JsonObject line = new JsonObject().put("at", l.at().toString()).put("level", l.level().name()).put("logger", l.logger()).put("text", l.text());
            l.thrown().ifPresent(thrown -> line.put("thrown", thrown));
            lines.add(line);
        }
        return new JsonObject().put("lines", lines).put("dropped", log.dropped());
    }

    /** The log {@link #json} wrote; {@link StepLog#EMPTY} for null. */
    public static StepLog log(JsonObject json) {
        if (json == null) return StepLog.EMPTY;
        List<StepLog.Line> lines = new ArrayList<>();
        for (Object o : json.getJsonArray("lines")) {
            JsonObject l = (JsonObject) o;
            lines.add(new StepLog.Line(Instant.parse(l.getString("at")), System.Logger.Level.valueOf(l.getString("level")), l.getString("logger"),
                    l.getString("text"), Optional.ofNullable(l.getString("thrown"))));
        }
        return new StepLog(lines, json.getLong("dropped"));
    }
}
