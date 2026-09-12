package io.flottila.runtime;

import io.flottila.core.Envelope;
import io.flottila.core.StageContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Persistent in the real thing (state whose loss loses messages goes to DB). */
public class Dlq implements StageContext.Dlq {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Dlq.class);

    public record Parked(String stage, Envelope envelope, String error) {}
    private final List<Parked> parked = new CopyOnWriteArrayList<>();
    private final Component.Consumer replyTo;
    private final boolean atMostOnce;

    public Dlq(Component.Consumer replyTo, boolean atMostOnce) { this.replyTo = replyTo; this.atMostOnce = atMostOnce; }

    @Override public void park(String stage, Envelope e, Throwable t) {
        // an overload rejection was never accepted (client got 503 + retry) — parking it too
        // would make a later replay a duplicate; at-most-once flows log and drop instead of parking
        if (atMostOnce) log.warn("dropped (at-most-once) {} at {}: {}", e.id(), stage, t.toString());
        else if (!(t instanceof BatchingWriter.OverloadedException)) parked.add(new Parked(stage, e, t.toString()));
        replyTo.fail(e, t);
    }
    public List<Parked> parked() { return parked; }
}
