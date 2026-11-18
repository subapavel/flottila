package dev.rail8.runtime.sample;

import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.sample.HmacSignPlugin.HmacSign;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.FakeServices;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample step as a plugin author tests it, with {@link FakeServices}: it signs the body with the day's key derived from its
 * secret, sets the header and the business key, derives the key once a day from the cache (a secret rotated during the day
 * signs from the next day on), and refuses a key option that is not a reference.
 */
class HmacSignTest {

    static final String REFERENCE = "secret://partner-hmac";

    final FakeRuntime rt = FakeRuntime.create();
    final FakeServices services = FakeServices.create(rt.clock()).secret(REFERENCE, "first-secret");
    final StepConfig step = new StepConfig(1, HmacSign.TYPE, "sign", false, Map.of("key", REFERENCE));

    static String expected(String secret, String day, String body) {
        byte[] dayKey = HmacSign.hmac(secret.getBytes(StandardCharsets.UTF_8), day);
        return Base64.getEncoder().encodeToString(HmacSign.hmac(dayKey, body.getBytes(StandardCharsets.UTF_8)));
    }

    Envelope sign(Stage stage, String body) throws Exception {
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer(body));
        return OnVirtualThread.call(() -> stage.apply(in, e -> e));
    }

    @Test
    void itSignsTheBodyAndSetsTheHeaderAndTheBusinessKey() throws Exception {
        Stage stage = new HmacSign().create(step, rt.stepResources(Path.of("."), services));
        Envelope out = sign(stage, "<order id='4711'/>");
        assertEquals(expected("first-secret", "2026-01-01", "<order id='4711'/>"), out.header(HmacSign.HEADER).orElseThrow());
        assertEquals("2026-01-01", out.key(HmacSign.KEY).orElseThrow());
    }

    @Test
    void theDaysKeyIsDerivedOnceADay() throws Exception {
        Stage stage = new HmacSign().create(step, rt.stepResources(Path.of("."), services));
        sign(stage, "a");
        services.secret(REFERENCE, "rotated-secret");
        assertEquals(expected("first-secret", "2026-01-01", "b"), sign(stage, "b").header(HmacSign.HEADER).orElseThrow(),
                "the day's key came from the cache");
        rt.clock().advance(Duration.ofDays(1));
        assertEquals(expected("rotated-secret", "2026-01-02", "c"), sign(stage, "c").header(HmacSign.HEADER).orElseThrow(),
                "the next day's key is derived from the secret's value then");
    }

    @Test
    void aKeyThatIsNotAReferenceIsRefused() {
        StepConfig literal = new StepConfig(1, HmacSign.TYPE, "sign", false, Map.of("key", "plain-text-key"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new HmacSign().create(literal, rt.stepResources(Path.of("."), services)));
        assertTrue(e.getMessage().contains("step 'sign'") && !e.getMessage().contains("plain-text-key"), e.getMessage());
    }
}
