package io.flottila.runtime;

import io.flottila.core.Envelope;
import io.flottila.core.StageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stage-facing DLQ: parks into the durable store, then answers the waiting client if any. */
public class Dlq implements StageContext.Dlq {
    private static final Logger log = LoggerFactory.getLogger(Dlq.class);

    private final Component.Consumer replyTo;
    private final boolean atMostOnce;
    private final DlqStore store;

    public Dlq(Component.Consumer replyTo, boolean atMostOnce, DlqStore store) {
        this.replyTo = replyTo; this.atMostOnce = atMostOnce; this.store = store;
    }

    @Override public void park(String stage, Envelope e, Throwable t) {
        // an overload rejection was never accepted (client got 503 + retry) — parking it too
        // would make a later replay a duplicate; at-most-once flows log and drop instead
        if (atMostOnce) log.warn("dropped (at-most-once) {} at {}: {}", e.id(), stage, t.toString());
        else if (!(t instanceof BatchingWriter.OverloadedException))
            store.park(stage, e, t).subscribe().with(x -> {}, err -> log.error("DLQ park failed for {}", e.id(), err));
        replyTo.fail(e, t);
    }
}
