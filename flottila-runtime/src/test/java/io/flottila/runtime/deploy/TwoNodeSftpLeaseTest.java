package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Timer;
import io.flottila.components.sftp.EmbeddedSftp;
import io.flottila.components.sftp.SftpComponent;
import io.flottila.runtime.deploy.LeaseLog.Hold;
import io.flottila.runtime.lease.InMemoryPollerLeases;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeIds;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static io.flottila.runtime.deploy.LeaseLog.quiet;
import static io.flottila.runtime.deploy.LeaseLog.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02L done-when (D-083, BACKLOG restated): two nodes on one sftp endpoint, each with the real sftp consumer under its own
 * {@code LeasedConsumer} and its own timer, one lease store between them (in memory, on the shared fake clock), against an
 * embedded SFTP server. Only the lease holder polls: the standby never opens a session. Killing the holder — its timer frozen:
 * nothing of it fires again — moves polling to the other node at most ttl + renew period after the holder's last hold, never
 * while that hold's validity runs. Closing the holder releases its lease: the other takes it at its next tick.
 *
 * <p>ttl 30 s, renew 10 s, sftp {@code intervalMs} 1 s. A's ticks come at T0, T0+10, T0+20, …; B starts 3 s later, so its ticks
 * (T0+3, T0+13, …) never coincide with A's lease running out. A tick's hold is answered by the store at once; a case waits for
 * the hold it expects and for its tick's thread to be quiet before it looks at what polled.
 */
@Timeout(120)
class TwoNodeSftpLeaseTest {

    static final Instant T0 = FakeRuntime.T0;
    static final Duration TTL = Duration.ofSeconds(30);
    static final Duration RENEW = Duration.ofSeconds(10);
    /** The sftp poll interval: a round every second of fake time, or (the kill case) round 0 only. */
    static final Duration EVERY_SECOND = Duration.ofSeconds(1);
    static final Duration HOURLY = Duration.ofHours(1);

    @TempDir Path root;

    final FakeClock clock = FakeClock.at(T0);
    final List<String> log = new CopyOnWriteArrayList<>();
    final LeaseLog leases = new LeaseLog(clock, new InMemoryPollerLeases(clock), log::add);
    final List<Node> nodes = new ArrayList<>();
    EmbeddedSftp sftp;

    @BeforeEach
    void up() throws IOException {
        sftp = EmbeddedSftp.start(root);
        sftp.mkdir("/inbox");
    }

    @AfterEach
    void down() throws Exception {
        try {
            for (Node n : nodes) {
                n.timer.frozen = false;
                if (!n.stopped) OnVirtualThread.join(OnVirtualThread.start(() -> n.leased.stop().await()));
            }
        } finally {
            sftp.close();
        }
    }

    // --- fixture

    /**
     * A node's timer over its own FakeTimer on the shared clock; frozen, it drops every task that comes due (a killed node). It
     * records every delay asked: an sftp round, as it ends, schedules the next one {@code intervalMs} later.
     */
    static final class Freezable implements Timer {
        final FakeTimer fake;
        final List<Duration> delays = new CopyOnWriteArrayList<>();
        volatile boolean frozen;

        Freezable(FakeTimer fake) { this.fake = fake; }

        @Override
        public Cancellable schedule(Duration delay, Runnable task) {
            Objects.requireNonNull(task, "task");
            delays.add(delay);
            return fake.schedule(delay, () -> {
                if (!frozen) task.run();
            });
        }
    }

    /** One node: its timer, its sftp component, a handler recording each file it took and when, its leased consumer. */
    final class Node {
        final String owner;
        final Freezable timer;
        final List<String> took = new CopyOnWriteArrayList<>();
        final List<Instant> tookAt = new CopyOnWriteArrayList<>();
        final LeasedConsumer leased;
        volatile boolean stopped;

        Node(String name, Duration interval) {
            EndpointConfig inbox = sftp.endpoint("in", "/inbox", Map.of("intervalMs", interval.toMillis(), "flow", "shop/feed"));
            owner = name + "#1";
            timer = new Freezable(new FakeTimer(clock));
            FakeRuntime rt = FakeRuntime.create();
            SftpComponent component = new SftpComponent(rt.blobs(), new FakeIds(name), timer, clock);
            Function<Envelope, Future<Ack>> handler = e -> {
                took.add(e.get(SftpComponent.FILE).orElse("<no sftp.file>"));
                tookAt.add(clock.instant());
                return Future.succeededFuture(new Ack.Accepted(e.id()));
            };
            leased = new LeasedConsumer(leases, key(), owner, TTL, RENEW, timer, clock, component.consumer(inbox, handler),
                    () -> component.consumer(inbox, handler));
            nodes.add(this);
        }

        List<Hold> holds() { return leases.holdsBy(owner); }

        void stop() throws Exception {
            stopped = true;
            OnVirtualThread.join(OnVirtualThread.start(() -> leased.stop().await()));
        }
    }

    /** Moves the clock to {@code at} (what comes due on any node's timer fires) and waits until {@code holds} holds were made and their ticks are quiet. */
    void stepTo(Instant at, int holds) {
        clock.set(at);
        Hold last = leases.hold(holds);
        for (Hold h : leases.holds()) quiet(h.thread(), "the tick of hold #" + h.n() + " (" + h.owner() + " at " + h.at() + ")");
        assertEquals(holds, leases.holds().size(), "the holds by " + at + ": " + log + "; last " + last);
    }

    static Instant t(long seconds) { return T0.plusSeconds(seconds); }

    /**
     * The endpoint's lease as Deployments names it: {@code <package>/<flow>/<scheme>://<endpoint id>} (P2-02L [A] 2) — here
     * {@code shop/feed/sftp://in}, the id of the endpoint {@code in}; the server's host, port and directory are options, never
     * part of the key.
     */
    String key() { return "shop/feed/sftp://in"; }

    void drop(String name) {
        sftp.drop("/inbox", name, Buffer.buffer("<order id='" + name + "'/>"));
    }

    /** Starts A and lets it take the lease at T0, then starts B at T0+3 (its tick 0: refused), A's round 0 polling then. */
    void aHoldsBStandsBy(Node a, Node b) {
        a.leased.start();
        stepTo(T0, 1);
        assertEquals(Boolean.TRUE, a.holds().getFirst().result(), "fixture: A holds");
        b.leased.start();
        stepTo(t(3), 2);
        assertEquals(Boolean.FALSE, b.holds().getFirst().result(), "fixture: B stands by");
    }

    // --- cases

    /** While A holds the lease only A polls: it takes every file, B none, and B never opens a session. */
    @Test
    void onlyTheLeaseHolderPolls() {
        Node a = new Node("node-a", EVERY_SECOND);
        Node b = new Node("node-b", EVERY_SECOND);
        drop("f1.xml");
        drop("f2.xml");

        aHoldsBStandsBy(a, b);
        until(() -> a.took.size() == 2, "A's round takes both files: " + a.took);
        // round 0 ends by scheduling round 1 a second later on A's timer; stepping the clock before that would leave round 1 due
        // after the last step (no lease tick asks for a 1 s delay: TTL 30 s, renew 10 s)
        until(() -> a.timer.delays.contains(EVERY_SECOND), "A's round 0 ended: its next round is scheduled, 1 s on");
        drop("f3.xml");
        int holds = 2;
        for (long s = 10; s <= 60; s += 10) {
            stepTo(t(s), ++holds);       // A renews
            stepTo(t(s + 3), ++holds);   // B is refused
        }
        until(() -> a.took.size() == 3, "A takes the file dropped later: " + a.took);

        assertEquals(List.of("f1.xml", "f2.xml", "f3.xml"), a.took);
        assertEquals(List.of(), b.took, "the standby polls nothing");
        assertTrue(a.holds().stream().allMatch(h -> Boolean.TRUE.equals(h.result())), "A renewed every time: " + log);
        assertTrue(b.holds().stream().allMatch(h -> Boolean.FALSE.equals(h.result())), "B was refused every time: " + log);
        assertEquals(1, sftp.sessionsCreated(), "one SFTP session: A's; B never connected");
    }

    /**
     * A's last hold is at T0+20 (valid until T0+50); then A is killed, no round of it in flight (it polls hourly: round 0 at
     * T0+3, ended). A file dropped then waits: B is refused at T0+23, +33 and +43, and takes the lease at T0+53, polling at
     * once — ≤ T0+20 + ttl + renew, never while A's validity ran.
     */
    @Test
    void killingTheHolderMovesPollingToTheOther() {
        Node a = new Node("node-a", HOURLY);
        Node b = new Node("node-b", HOURLY);
        aHoldsBStandsBy(a, b);
        until(() -> a.timer.delays.contains(HOURLY), "A's round 0 ended (it scheduled the next one)");
        stepTo(t(10), 3);    // A renews
        stepTo(t(13), 4);    // B refused
        stepTo(t(20), 5);    // A's last hold
        Instant lastHold = a.holds().getLast().at();
        assertEquals(t(20), lastHold);

        a.timer.frozen = true;   // killed: no round in flight, nothing of A fires again
        drop("late.xml");
        stepTo(t(23), 6);
        stepTo(t(33), 7);
        stepTo(t(43), 8);
        assertEquals(List.of("late.xml"), List.copyOf(sftp.files("/inbox")), "nobody polled while A's validity ran: A is dead, B refused");
        stepTo(t(53), 9);
        clock.advance(Duration.ZERO);   // B's round 0, scheduled at once by its consumer's start
        until(() -> b.took.contains("late.xml"), "B polls once it holds the lease: " + log);

        Hold taken = b.holds().stream().filter(h -> Boolean.TRUE.equals(h.result())).findFirst().orElseThrow();
        assertTrue(!taken.at().isBefore(lastHold.plus(TTL)), "B never holds while A's last hold is valid (until " + lastHold.plus(TTL) + "): " + taken.at());
        assertTrue(!b.tookAt.getFirst().isAfter(lastHold.plus(TTL).plus(RENEW)), "B polls ≤ ttl + renew after A's last hold: " + b.tookAt);
        assertEquals(List.of(), a.took, "A, dead, took nothing");
    }

    /** Closing A (its consumer stops, then it releases) lets B take the lease at its very next tick: T0+33, while A's lease would run to T0+50. */
    @Test
    void closingTheHolderMovesPollingAtTheOthersNextTick() throws Exception {
        Node a = new Node("node-a", EVERY_SECOND);
        Node b = new Node("node-b", EVERY_SECOND);
        aHoldsBStandsBy(a, b);
        stepTo(t(10), 3);
        stepTo(t(13), 4);
        stepTo(t(20), 5);    // A's last hold: valid until T0+50
        stepTo(t(23), 6);
        clock.set(t(25));

        a.stop();

        assertEquals(List.of(key() + " " + a.owner), leases.releases().stream().map(r -> r.key() + " " + r.owner()).toList(), "A released its lease");
        drop("after-close.xml");
        stepTo(t(33), 7);
        assertEquals(Boolean.TRUE, b.holds().getLast().result(), "B holds at its next tick after A closed: " + log);
        clock.advance(Duration.ZERO);   // B's round 0
        until(() -> b.took.contains("after-close.xml"), "B polls: " + log);
        assertEquals(List.of(), a.took, "A took nothing");
    }
}
