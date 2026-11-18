package dev.rail8.components.http;

import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an http endpoint file may set, as its settings description judges it. An in endpoint takes the server's host, port,
 * path and stop timeout; an out endpoint must set {@code maxBodyBytes} and may set a URL, a timeout and a pool size. A key
 * of the other direction, or one the description does not name, is refused. On an in endpoint the node's own
 * {@code flow} and {@code maxBodyBytes} are left alone.
 */
class HttpSettingsTest {

    static final Settings SETTINGS = HttpComponent.SETTINGS;

    @Test
    void anInEndpointTakesTheServersAddressAndStop() {
        assertEquals(List.of(), SETTINGS.check(Map.of(), Direction.IN), "every in setting has a default or may be left out");
        assertEquals(List.of(), SETTINGS.check(Map.of("host", "0.0.0.0", "port", 8080, "path", "/in", "shutdownTimeoutMs", 5_000), Direction.IN));
        assertEquals(List.of(), SETTINGS.check(Map.of("port", 0, "flow", "invoice", "maxBodyBytes", 1_048_576), Direction.IN),
                "port 0 is any free port; flow and maxBodyBytes are the node's on an in endpoint");
        assertEquals(List.of(new Settings.Problem("/port", "must be a whole number from 0 to 65535")),
                SETTINGS.check(Map.of("port", 65_536), Direction.IN));
    }

    @Test
    void anOutEndpointMustSetMaxBodyBytesAndNeedsNoUrl() {
        assertEquals(List.of(), SETTINGS.check(Map.of("maxBodyBytes", 1), Direction.OUT), "without a url the producer posts to the endpoint's URI");
        assertEquals(List.of(), SETTINGS.check(Map.of("url", "http://partner/in", "maxBodyBytes", 1_048_576, "timeoutMs", 5_000, "poolSize", 2), Direction.OUT));
        assertEquals(List.of(new Settings.Problem("/maxBodyBytes", "is required")), SETTINGS.check(Map.of(), Direction.OUT));
        assertEquals(List.of(new Settings.Problem("/maxBodyBytes", "must be a whole number of at least 1")),
                SETTINGS.check(Map.of("maxBodyBytes", 0), Direction.OUT));
    }

    @Test
    void aKeyOfTheOtherDirectionOrNoneIsRefused() {
        assertEquals(List.of(new Settings.Problem("/timeoutMs", "a setting of out endpoints only")),
                SETTINGS.check(Map.of("timeoutMs", 5), Direction.IN));
        assertEquals(List.of(new Settings.Problem("/host", "a setting of in endpoints only")),
                SETTINGS.check(Map.of("maxBodyBytes", 1, "host", "x"), Direction.OUT));
        assertEquals(List.of(new Settings.Problem("/flow", "no such setting")),
                SETTINGS.check(Map.of("maxBodyBytes", 1, "flow", "f"), Direction.OUT), "the node sets flow on in endpoints only");
        assertEquals(List.of(new Settings.Problem("/proxy", "no such setting")), SETTINGS.check(Map.of("proxy", "p"), Direction.IN));
    }
}
