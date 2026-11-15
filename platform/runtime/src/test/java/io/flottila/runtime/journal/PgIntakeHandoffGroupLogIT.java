package io.flottila.runtime.journal;

import io.flottila.runtime.store.PgIntake;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static io.flottila.runtime.journal.JournalWorld.settled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16a review r1 R4, on {@link PgIntakeHandoffGroupIT}'s fixtures (its two cases run here too): the slot call that throws is
 * never swallowed silently — {@link PgIntake} logs it at WARNING, naming the message, what failed and the throw.
 */
class PgIntakeHandoffGroupLogIT extends PgIntakeHandoffGroupIT {

    static final Logger INTAKE = Logger.getLogger(PgIntake.class.getName());   // a strong reference: JUL holds loggers weakly
    final NodeJournalIT.Logs logs = new NodeJournalIT.Logs();

    @BeforeEach
    void attach() { INTAKE.addHandler(logs); }

    @AfterEach
    void detach() { INTAKE.removeHandler(logs); }

    @Test
    void aSlotRunThatThrowsIsLoggedAtWarningAndTheOtherIsNot() throws Exception {
        Plug plug = plug();
        Future<String> m1 = call("m-1", JournalWorld.FLOW);
        Future<String> m2 = call("m-2", JournalWorld.FLOW);
        timer.refuseNext(LEASE.dividedBy(3));   // the first slot's run schedules its renewal: refused

        unplug(plug);
        assertTrue(settled(m1).succeeded() && settled(m2).succeeded(), "both answered: " + m1.cause() + ", " + m2.cause());
        PgIntakeHandoffIT.until(() -> !logs.warningsNaming("the hand-off slot of message").isEmpty(), () -> "the throw logged; logs " + logs.all());
        List<String> warned = logs.warningsNaming("the hand-off slot of message");
        assertEquals(1, warned.size(), "one slot threw, one is logged: " + logs.all());
        assertTrue(warned.get(0).contains("failed to run"), warned.get(0));
        assertTrue(warned.get(0).contains(RefusingTimer.REFUSAL.getMessage()), "with the throw: " + warned.get(0));
        assertTrue(warned.get(0).contains("message m-1 ") || warned.get(0).contains("message m-2 "), "naming the message: " + warned.get(0));
    }
}
