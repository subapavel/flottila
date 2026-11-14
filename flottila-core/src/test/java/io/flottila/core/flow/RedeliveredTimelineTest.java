package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.Key;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static io.flottila.core.flow.FlowWorkerTest.LEASE;
import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static io.flottila.core.flow.FlowWorkerTest.flow;
import static io.flottila.core.flow.FlowWorkerTest.policy;
import static io.flottila.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-131 — documents a defect, F-29: one message id run twice by the worker leaves a timeline stitched from both runs. The
 * row key of the message store is {@code (id, step)} (in memory {@code putIfAbsent}, in Postgres {@code on conflict (id, step)
 * do nothing}), so a rerun's rows at the steps its first run already recorded are dropped and only the steps the first run
 * never reached are kept. Run 1 fails at step 3 ({@code FAILED}, its park refused so the item is left to its lease), run 2
 * goes through to step 7: {@code timeline(id)} returns steps 1-3 from run 1 and steps 4-7 from run 2 in one sequence, a
 * {@code FAILED} step followed by {@code OK} steps, which no single run can produce. The only trace of the seam is the
 * {@link Envelope#ATTEMPT} prop on the rows run 2 wrote.
 *
 * <p>This test pins today's wrong behaviour so CONSIDER-12 (D-107, D-108) has a regression test to invert: when the row key
 * gains {@code attempt} — {@code (id, attempt, step)} — this test is to be inverted, each run's rows kept whole under its own
 * attempt. It is green on the defect, not on intended behaviour.
 */
@Timeout(30)
class RedeliveredTimelineTest {

    /**
     * The prop the {@code mark-run} step stamps with the run's number: the value each row's run is read from. A prop, not a
     * header: the {@code to} step hands on the partner's reply, whose headers are the partner's, while props are carried.
     */
    static final Key<Integer> RUN = new Key<>("x-run");

    FlowWorkerTest.World w;
    FlowWorkerTest.Logs logs;
    /** How many runs the {@code mark-run} step has started. */
    final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        w = new FlowWorkerTest.World();
        logs = FlowWorkerTest.Logs.attach();
    }

    @AfterEach
    void tearDown() {
        logs.detach();
        w.close();
    }

    /** Step 1: counts the run and stamps its number on the envelope it hands on. */
    final StepFactory markRun = ParkingTest.factory("mark-run", s -> Stage.of(e -> e.with(RUN, runs.incrementAndGet())));
    final StepFactory pass = ParkingTest.factory("pass", s -> Stage.of(e -> e));
    /** Throws a RuntimeException on run 1 only (recorded FAILED; the park this case refuses); every later run passes. */
    final StepFactory failFirstRun = ParkingTest.factory("fail-first-run", s -> Stage.of(e -> {
        if (e.get(RUN).orElseThrow() == 1) throw new IllegalStateException("refused at " + s.name() + " on run 1");
        return e;
    }));

    static Map<String, Object> step(String type, String name) {
        return Map.of("type", type, "name", name);
    }

    /** One row of the timeline as the test reads it: step, name, outcome, the run that wrote it (its {@code x-run} prop), its attempt prop. */
    static String row(Snapshot s) {
        return s.step() + " " + s.stepName() + " " + s.outcome() + " run=" + s.props().get(RUN).map(String::valueOf).orElse("none") + " attempt=" + s.props().get(Envelope.ATTEMPT).map(String::valueOf).orElse("none");
    }

    @Test
    void aRedeliveredMessagesTimelineIsStitchedFromTwoRunsWithNothingButTheAttemptPropMarkingTheSeam() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(
                step("mark-run", "mark"), step("pass", "enrich"), step("fail-first-run", "check"),
                step("pass", "map"), step("pass", "route"), step("pass", "audit"), TO_PARTNER)));
        FlowBuilder b = new FlowBuilder(w.rt.runtime(), List.of(markRun, pass, failFirstRun), List.of(BuiltInStepsTest.HEADERS), w.endpoints)
                .recording(w.store, Meter.system()).queue(w.queue).dlq(w.dlq);
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        w.dlq.failParks(new IllegalStateException("dlq down"));   // run 1's park is refused: its item is left to its lease

        // run 1: fails at step 3 (check), the park refused, the item left to its lease
        assertEquals(1, w1.poll().await(5, SECONDS), "run 1");
        until(() -> w.queue.claimsInFlight() == 0, () -> "run 1's claim answered");
        assertEquals(1, w.rows.size(), "fixture: run 1 failed and its item is left to its lease");
        assertEquals(List.of(), w.partner.sent(), "fixture: run 1 never reached the partner");
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "fixture: run 1 was not parked");
        String leftToLease = "message " + in.id() + " of flow orders failed at worker w1 (attempt 1 of 5) and is left to its lease";
        assertEquals(1, logs.at(Level.WARNING, leftToLease).size(), "fixture: run 1 left to its lease: " + logs.all());

        // run 2: one lease later, the same id claimed again as attempt 2, goes through to step 7 (partner)
        w.dlq.parksOk();
        w.rt.clock().advance(LEASE);
        assertEquals(1, w1.poll().await(5, SECONDS), "run 2");
        until(() -> w.queue.claimsInFlight() == 0, () -> "run 2's claim answered");
        assertEquals(List.of(1, 2), w.claimedAttempts(), "fixture: one item, claimed as attempts 1 and 2");
        assertEquals(2, runs.get(), "fixture: the pipeline ran twice");
        assertEquals(List.of(in.id()), w.sentIds(), "fixture: run 2 reached the partner, under the same id");
        assertEquals(0, w.rows.size(), "fixture: run 2 completed the item");
        for (String name : List.of("mark", "enrich", "check")) {
            assertEquals(2, w.store.recorded(name), "both runs handed the store a row for " + name + ": the second one collides on (id, step)");
        }
        for (String name : List.of("map", "route", "audit", "partner")) {
            assertEquals(1, w.store.recorded(name), "only run 2 handed the store a row for " + name);
        }

        assertEquals(1, w.store.recorded("ingress"), "one ingress row, the delivery's save: neither run writes step 0");
        List<Snapshot> timeline = w.store.timeline(in.id()).await();
        assertEquals(List.of(
                        "0 ingress OK run=none attempt=none",   // the delivery's save; neither run writes step 0
                        "1 mark OK run=1 attempt=none",          // run 1's row; run 2's row for step 1 was dropped
                        "2 enrich OK run=1 attempt=none",        // run 1's
                        "3 check FAILED run=1 attempt=none",     // run 1's failure; run 2 passed this step, its OK row dropped
                        "4 map OK run=2 attempt=2",              // run 2's from here on: steps run 1 never reached
                        "5 route OK run=2 attempt=2",
                        "6 audit OK run=2 attempt=2",
                        "7 partner OK run=2 attempt=2"),
                timeline.stream().map(RedeliveredTimelineTest::row).toList(),
                "the timeline of one id is stitched: steps 1-3 from run 1, steps 4-7 from run 2");

        // the stitch: a FAILED step 3 and OK steps past it, in one timeline — a sequence no single run produces
        Snapshot check = timeline.get(3);
        assertEquals(3, check.step());
        assertEquals(Snapshot.Outcome.FAILED, check.outcome());
        assertEquals(Optional.of("java.lang.IllegalStateException: refused at check on run 1"), check.error(), "run 1's failure");
        List<Snapshot> past = timeline.stream().filter(s -> s.step() > 3).toList();
        assertEquals(List.of(4, 5, 6, 7), past.stream().map(Snapshot::step).toList());
        assertTrue(past.stream().allMatch(s -> s.outcome() == Snapshot.Outcome.OK && s.error().isEmpty()), "every step past the FAILED one reads OK: " + past);

        // the only mark of two runs: ATTEMPT on the rows run 2 wrote, absent from every row run 1 (or the delivery) wrote
        assertEquals(List.of(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(2), Optional.of(2), Optional.of(2), Optional.of(2)),
                timeline.stream().map(s -> s.props().get(Envelope.ATTEMPT)).toList(), "attempt 2 on run 2's rows only");
    }

    /**
     * FIX-131, F-29, the worst shape: run 1 dies at step 3 with an {@link Error} (the {@code crashFirst} harness; a node dying
     * mid-run). Since FIX-133 {@code Recorder.wrap} catches {@code Throwable} (D-111), so run 1 leaves OK rows at steps 1-2 and
     * a FAILED row at step 3 naming the Error; run 2 (attempt 2, after one lease) goes through, its rows at steps 1-3 are
     * dropped on {@code (id, step)} and its rows 4-7 kept. The crash is visible: the timeline reads as case 1's, a FAILED
     * step 3 with OK steps past it, and only the attempt prop on steps 4-7 betrays the seam.
     *
     * <p>Two separate triggers change this case, and either may land first. <b>FIX-133</b> (record a FAILED row on an
     * {@code Error}, then rethrow; it does not wait for CONSIDER-12) flips the step-3 expectations, all of them deliberately:
     * run 1's interim timeline gains {@code "3 check FAILED run=1 attempt=none"}; {@code recorded("check")} becomes 1 after run 1
     * and 2 after run 2; the final list's step-3 row becomes run 1's FAILED row ({@code run=1 attempt=none}); the "no row is
     * FAILED" assertion, the step-3 {@code x-run} assertion and the step-3 entry of the ATTEMPT list flip with it — case 2 then
     * reads as case 1. FIX-133 has landed and those expectations are flipped below; the rest stands.
     * <b>CONSIDER-12</b> (the row key gains {@code attempt}) inverts the whole case together with case 1.
     */
    @Test
    void aMessageWhoseFirstRunCrashedShowsTheCrashAsAFailedRowStitchedBeforeTheRedeliveredRun() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(
                step("mark-run", "mark"), step("pass", "enrich"), step("crash-first", "check"),
                step("pass", "map"), step("pass", "route"), step("pass", "audit"), TO_PARTNER)));
        FlowBuilder b = new FlowBuilder(w.rt.runtime(), List.of(markRun, pass, w.crashFirst), List.of(BuiltInStepsTest.HEADERS), w.endpoints)
                .recording(w.store, Meter.system()).queue(w.queue).dlq(w.dlq);
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));

        // run 1: an Error at step 3 (check), never parked; the item is left to its lease
        assertEquals(1, w1.poll().await(5, SECONDS), "run 1");
        until(() -> w.queue.claimsInFlight() == 0, () -> "run 1's claim answered");
        assertEquals(1, w.rows.size(), "fixture: run 1 crashed and its item is left to its lease");
        assertEquals(List.of(), w.queue.completes(), "fixture: run 1 did not complete the item");
        assertEquals(List.of(), w.queue.holds(), "fixture: nor hold it");
        assertEquals(List.of(), w.partner.sent(), "fixture: run 1 never reached the partner");
        assertEquals(List.of(), w.parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "fixture: an Error is never parked");
        String leftToLease = "message " + in.id() + " of flow orders failed at worker w1 (attempt 1 of 5) and is left to its lease";
        List<String> warnings = logs.at(Level.WARNING, leftToLease);
        assertEquals(1, warnings.size(), "fixture: run 1 left to its lease: " + logs.all());
        assertTrue(warnings.get(0).contains("crashed at check on the first run"), "fixture: by the Error at check: " + warnings.get(0));
        assertEquals(List.of("0 ingress OK run=none attempt=none", "1 mark OK run=1 attempt=none", "2 enrich OK run=1 attempt=none",
                        "3 check FAILED run=1 attempt=none"),
                w.store.timeline(in.id()).await().stream().map(RedeliveredTimelineTest::row).toList(),
                "run 1's trail kept its rows up to the crash and a FAILED row at step 3 (D-111)");
        assertEquals(1, w.store.recorded("check"), "the Error left its row at step 3: run 1 handed the store one (D-111)");

        // run 2: one lease later, the same id claimed again as attempt 2, goes through to step 7 (partner)
        w.rt.clock().advance(LEASE);
        assertEquals(1, w1.poll().await(5, SECONDS), "run 2");
        until(() -> w.queue.claimsInFlight() == 0, () -> "run 2's claim answered");
        assertEquals(List.of(1, 2), w.claimedAttempts(), "fixture: one item, claimed as attempts 1 and 2");
        assertEquals(2, runs.get(), "fixture: the pipeline ran twice");
        assertEquals(List.of(in.id()), w.sentIds(), "fixture: run 2 reached the partner, under the same id");
        assertEquals(0, w.rows.size(), "fixture: run 2 completed the item");
        for (String name : List.of("mark", "enrich", "check")) {
            assertEquals(2, w.store.recorded(name), "both runs handed the store a row for " + name + ": the second one collides on (id, step)");
        }
        for (String name : List.of("map", "route", "audit", "partner")) {
            assertEquals(1, w.store.recorded(name), "only run 2 handed the store a row for " + name);
        }

        assertEquals(1, w.store.recorded("ingress"), "one ingress row, the delivery's save: neither run writes step 0");
        List<Snapshot> timeline = w.store.timeline(in.id()).await();
        assertEquals(List.of(
                        "0 ingress OK run=none attempt=none",   // the delivery's save
                        "1 mark OK run=1 attempt=none",          // run 1's; run 2's dropped
                        "2 enrich OK run=1 attempt=none",        // run 1's; run 2's dropped
                        "3 check FAILED run=1 attempt=none",     // run 1's crash (D-111); run 2 passed this step, its OK row dropped
                        "4 map OK run=2 attempt=2",
                        "5 route OK run=2 attempt=2",
                        "6 audit OK run=2 attempt=2",
                        "7 partner OK run=2 attempt=2"),
                timeline.stream().map(RedeliveredTimelineTest::row).toList(),
                "the timeline of one id is stitched: steps 1-3 from run 1, steps 4-7 from run 2");

        // the crash at step 3 is visible: its row is the only one FAILED or carrying an error, and it names the Error
        assertEquals(List.of(3), timeline.stream().filter(s -> s.outcome() != Snapshot.Outcome.OK || s.error().isPresent()).map(Snapshot::step).toList(),
                "step 3 is the one row FAILED or carrying an error");
        Snapshot check = timeline.get(3);
        assertEquals(3, check.step());
        assertEquals(Optional.of(1), check.props().get(RUN), "the step-3 row is run 1's record of its crash, not run 2's pass");
        assertTrue(check.error().orElse("").contains("crashed at check on the first run"), "and it names the Error: " + check.error());

        // the only mark of two runs: ATTEMPT on the rows run 2 wrote (4-7), absent from the delivery's and run 1's (0-3)
        assertEquals(List.of(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(2), Optional.of(2), Optional.of(2), Optional.of(2)),
                timeline.stream().map(s -> s.props().get(Envelope.ATTEMPT)).toList(), "attempt 2 on run 2's rows only");
    }
}
