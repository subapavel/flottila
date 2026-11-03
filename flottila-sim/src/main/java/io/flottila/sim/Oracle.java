package io.flottila.sim;

import io.flottila.api.Ack;
import io.flottila.core.dlq.Dlq;
import io.flottila.sim.Verdict.Failure;
import io.vertx.core.AsyncResult;
import io.vertx.core.buffer.Buffer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * The model of a run and its checks (D-101). Each comparison counts under its check; a comparison is one expected text against
 * one observed text, so a check fails exactly when they differ.
 * <ul>
 * <li>PREDICTED: a send with room in its flow's admission answers as the model says — a sync send the Reply
 * {@code ack:} + its payload reversed when a partner call delivered, else the failure after every call was refused; an
 * at-least-once send Accepted under its own id, or a retryable Nack when its STORE_REFUSE draw (ingress or enqueue) hit.</li>
 * <li>ADMISSION_BOUNDS, counted only at the limit: a send that finds its flow's requests or bytes full is refused with a
 * retryable Nack in its own unit; after every unit no flow holds more than its maxInFlight requests and maxInFlightBytes
 * bytes, and no worker runs more than its limit.</li>
 * <li>KEY_ORDER: at most one message of a key is at the partner at a time (from its first call to its last), and the first
 * receipts of a key come in the order its messages were sent.</li>
 * <li>after the drain — ACKED_IS_FINAL: every Accepted message was delivered once, or parked at the {@code to} step after
 * every call was refused; SNAPSHOT_PER_STEP: every message that ran has one snapshot per step, the last FAILED when it was
 * refused throughout; DRAINED: no run, no held call, no wake, no item left, every send answered.</li>
 * </ul>
 * Each check keeps its first {@value #KEPT_PER_CHECK} failures. Called from the driver and from the message threads, one at a time.
 */
final class Oracle {

    static final int KEPT_PER_CHECK = 25;
    private static final String RETRYABLE = "Nack retryable=true";

    /** One send: what was sent, what the model expects of it, what came back. */
    static final class Send {
        final String id;
        final String flow;
        final String key;
        final Buffer payload;
        final int seq;                   // its place among the sends of its key (1, 2, ...); 0 without a key
        final boolean storeRefused;      // its STORE_REFUSE draw hit
        final String atLimit;            // what was in flight when it came at the limit; null when it found room
        AsyncResult<Ack> answer;
        boolean accepted;
        final List<Boolean> calls = new ArrayList<>();   // per partner call in order: delivered

        Send(String id, String flow, String key, Buffer payload, int seq, boolean storeRefused, String atLimit) {
            this.id = id;
            this.flow = flow;
            this.key = key;
            this.payload = payload;
            this.seq = seq;
            this.storeRefused = storeRefused;
            this.atLimit = atLimit;
        }
    }

    /** A payload Nacked at {@code tick}, to be sent again. */
    record Pending(String flow, String key, Buffer payload, long tick) {}

    private final SimConfig config;
    private final Trace trace;
    private final LongSupplier tick;
    private final Map<String, Send> sends = new LinkedHashMap<>();
    private final Map<String, long[]> inFlight = new HashMap<>();          // per flow: requests, bytes of sends not answered
    private final Map<String, Integer> sentOfKey = new HashMap<>();
    private final Map<String, String> open = new HashMap<>();              // per key: the message at the partner
    private final Map<String, Integer> firstReceived = new HashMap<>();    // per key: the highest seq received so far
    private final List<Pending> nacked = new ArrayList<>();
    private final Map<Check, Long> compared = new EnumMap<>(Check.class);
    private final Map<Check, Integer> failed = new EnumMap<>(Check.class);
    private final List<Failure> failures = new ArrayList<>();

    Oracle(SimConfig config, Trace trace, LongSupplier tick) {
        this.config = config;
        this.trace = trace;
        this.tick = tick;
        inFlight.put(SimRun.SYNC, new long[2]);
        inFlight.put(SimRun.ALO, new long[2]);
    }

    /** A send starts: judged at the limit or with room against what its flow has in flight. */
    synchronized Send started(String id, String flow, String key, Buffer payload, boolean storeRefused) {
        long[] f = inFlight.get(flow);
        long size = payload.length();
        boolean room = f[0] < maxRequests(flow) && f[1] + size <= maxBytes(flow);
        String state = f[0] + " of " + maxRequests(flow) + " requests, " + f[1] + " + " + size + " of " + maxBytes(flow) + " bytes in flight";
        int seq = key == null ? 0 : sentOfKey.merge(key, 1, Integer::sum);
        Send s = new Send(id, flow, key, payload, seq, storeRefused, room ? null : state);
        f[0]++;
        f[1] += size;
        sends.put(id, s);
        trace.add(tick.getAsLong(), id, "START " + flow + " key=" + key + " size=" + size + " room=" + room);
        return s;
    }

    /** The send's unit is over: one that came at the limit was refused in it. */
    synchronized void startEnded(Send s) {
        if (s.atLimit == null) return;
        count(Check.ADMISSION_BOUNDS, 1);
        String got = s.answer == null ? "admitted (no answer within its unit)" : describe(s);
        if (!got.equals(RETRYABLE)) fail(Check.ADMISSION_BOUNDS, s.id + " came at the limit (" + s.atLimit + ") and was " + got);
    }

    /** The send's answer: out of flight, to the resend pool when retryable, and as predicted when it had room. */
    synchronized void answered(Send s, AsyncResult<Ack> answer) {
        s.answer = answer;
        long[] f = inFlight.get(s.flow);
        f[0]--;
        f[1] -= s.payload.length();
        String got = describe(s);
        trace.add(tick.getAsLong(), s.id, "ANSWER " + got);
        if (got.equals(RETRYABLE)) nacked.add(new Pending(s.flow, s.key, s.payload, tick.getAsLong()));
        s.accepted = got.equals("Accepted " + s.id);   // an acked message is final, predicted or not
        if (s.atLimit != null) return;
        count(Check.PREDICTED, 1);
        String expected = expected(s);
        if (!got.equals(expected)) fail(Check.PREDICTED, s.id + ": expected " + expected + ", was " + got);
    }

    /** A partner call of {@code id} starts: a keyed message checks its key's order. */
    synchronized void call(String id, String key, int ordinal) {
        if (key == null) return;
        Send s = sends.get(id);
        count(Check.KEY_ORDER, 1);
        String there = open.getOrDefault(key, id);
        if (!there.equals(id)) fail(Check.KEY_ORDER, id + " of key " + key + " reached the partner while " + there + " of that key is there");
        if (s.calls.isEmpty()) {
            count(Check.KEY_ORDER, 1);
            int highest = firstReceived.getOrDefault(key, 0);
            if (s.seq < highest) {
                fail(Check.KEY_ORDER, id + " (send #" + s.seq + " of key " + key + ") reached the partner after send #" + highest + " of that key");
            }
            firstReceived.put(key, Math.max(highest, s.seq));
        }
        open.put(key, id);
    }

    /** A partner call of {@code id} ended, delivered or refused; its last one closes the message at the partner. */
    synchronized void called(String id, String key, int ordinal, boolean delivered) {
        sends.get(id).calls.add(delivered);
        if (key != null && (delivered || ordinal == config.retries())) open.remove(key, id);
    }

    /** After a unit: no flow and no worker holds more than its limit; counted only at the limit. */
    synchronized void bounds(Runs runs) {
        for (String flow : List.of(SimRun.SYNC, SimRun.ALO)) {
            long[] f = inFlight.get(flow);
            if (f[0] >= maxRequests(flow) || f[1] + config.minPayload() > maxBytes(flow)) {
                count(Check.ADMISSION_BOUNDS, 1);
                if (f[0] > maxRequests(flow) || f[1] > maxBytes(flow)) {
                    fail(Check.ADMISSION_BOUNDS, flow + " holds " + f[0] + " of " + maxRequests(flow) + " requests, " + f[1] + " of " + maxBytes(flow) + " bytes");
                }
            }
        }
        for (String worker : SimRun.WORKERS) {
            long runsOf = runs.ofWorker(worker);
            if (runsOf >= config.aloMaxInFlight()) {
                count(Check.ADMISSION_BOUNDS, 1);
                if (runsOf > config.aloMaxInFlight()) fail(Check.ADMISSION_BOUNDS, "worker " + worker + " runs " + runsOf + " of " + config.aloMaxInFlight());
            }
        }
    }

    /** The oldest payload Nacked at least resendAfter ticks before {@code now}, taken out; null when there is none. */
    synchronized Pending resend(long now) {
        for (Pending p : nacked) {
            if (p.tick() + config.resendAfter() <= now) {
                nacked.remove(p);
                return p;
            }
        }
        return null;
    }

    /** The checks after the drain, over the in-memory store and DLQ as the drain left them. */
    synchronized void judge(SimStore records, Dlq dlq, Runs runs, int wakes, boolean itemsLeft) {
        for (Send s : sends.values()) {
            if (s.accepted) {
                count(Check.ACKED_IS_FINAL, 1);
                long delivered = s.calls.stream().filter(d -> d).count();
                String got = delivered + " delivered of " + s.calls.size() + " calls, parked "
                        + dlq.get(s.id).await().map(p -> "at step " + p.step()).orElse("nowhere");
                String expected = delivered > 0 ? "1 delivered of " + s.calls.size() + " calls, parked nowhere"
                        : "0 delivered of " + (config.retries() + 1) + " calls, parked at step 3";
                if (!got.equals(expected)) fail(Check.ACKED_IS_FINAL, s.id + " was accepted: expected " + expected + ", was " + got);
            }
            if (!s.calls.isEmpty()) {
                count(Check.SNAPSHOT_PER_STEP, 1);
                String got = records.recorded(s.id).stream().map(x -> x.step() + ":" + x.stepName() + ":" + x.outcome())
                        .collect(Collectors.joining(","));
                String expected = timeline(s);
                if (!got.equals(expected)) fail(Check.SNAPSHOT_PER_STEP, s.id + ": expected " + expected + ", was " + got);
            }
        }
        List<String> unanswered = sends.values().stream().filter(s -> s.answer == null).map(s -> s.id).toList();
        count(Check.DRAINED, 4 + sends.size());
        String got = "runs " + runs.active() + ", held " + runs.held() + ", wakes " + wakes + ", items left " + itemsLeft + ", unanswered " + unanswered;
        String drained = "runs 0, held 0, wakes 0, items left false, unanswered []";
        if (!got.equals(drained)) fail(Check.DRAINED, "after the drain: " + got + "\n" + runs.unclassified());
    }

    synchronized List<Failure> failures() {
        return List.copyOf(failures);
    }

    synchronized Map<Check, Long> compared() {
        return Map.copyOf(compared);
    }

    /** What the model expects of a send with room. */
    private String expected(Send s) {
        if (SimRun.ALO.equals(s.flow)) return s.storeRefused ? RETRYABLE : "Accepted " + s.id;
        if (s.calls.contains(true)) return "Reply " + s.id + " " + Buffer.buffer(SimPartner.REPLY_PREFIX).appendBuffer(SimSteps.reversed(s.payload));
        return "Failed NackException after " + (config.retries() + 1) + " calls";
    }

    /** The timeline a message that ran leaves: a snapshot per step, the last FAILED when no call delivered. */
    private String timeline(Send s) {
        String steps = SimRun.SYNC.equals(s.flow) ? "0:ingress:OK,1:transform:OK,2:partner:" : "0:ingress:OK,1:key:OK,2:transform:OK,3:partner:";
        return steps + (s.calls.contains(true) ? "OK" : "FAILED");
    }

    /** The send's answer as the checks compare it; a failure names its cause's type and how many partner calls were made. */
    private static String describe(Send s) {
        if (s.answer.failed()) return "Failed " + s.answer.cause().getClass().getSimpleName() + " after " + s.calls.size() + " calls";
        Ack ack = s.answer.result();
        if (ack instanceof Ack.Reply r) return "Reply " + r.reply().id() + " " + r.reply().body().whole();
        if (ack instanceof Ack.Accepted a) return "Accepted " + a.messageId();
        return "Nack retryable=" + ((Ack.Nack) ack).retryable();
    }

    private long maxRequests(String flow) {
        return SimRun.SYNC.equals(flow) ? config.syncMaxInFlight() : config.aloMaxInFlight();
    }

    private long maxBytes(String flow) {
        return SimRun.SYNC.equals(flow) ? config.syncMaxInFlightBytes() : config.aloMaxInFlightBytes();
    }

    private void count(Check check, long n) {
        compared.merge(check, n, Long::sum);
    }

    private void fail(Check check, String detail) {
        if (failed.merge(check, 1, Integer::sum) <= KEPT_PER_CHECK) failures.add(new Failure(check, tick.getAsLong(), detail));
    }
}
