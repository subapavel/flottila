package dev.rail8.components.http;

import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an http endpoint file may set, as its settings description judges it. An in endpoint must set its address on the
 * node's HTTP port; an out endpoint must set {@code maxBodyBytes} and may set a URL, a timeout and a pool size. A key of the
 * other direction, or one the description does not name, is refused. On an in endpoint the node's own {@code flow} and
 * {@code maxBodyBytes} are left alone.
 */
class HttpSettingsTest {

    static final Settings SETTINGS = HttpComponent.SETTINGS;

    @Test
    void anInEndpointMustSetItsAddressOnTheHttpPort() {
        assertEquals(List.of(new Settings.Problem("/address", "is required")), SETTINGS.check(Map.of(), Direction.IN));
        assertEquals(List.of(), SETTINGS.check(Map.of("address", "/invoice/async"), Direction.IN));
        assertEquals(List.of(), SETTINGS.check(Map.of("address", "/in", "flow", "invoice", "maxBodyBytes", 1_048_576), Direction.IN),
                "flow and maxBodyBytes are the node's on an in endpoint");
        for (String bad : List.of("in", "/", "/in/", "/a//b", "/../in", "/in?x=1", "/Ü"))
            assertEquals(List.of(new Settings.Problem("/address", "must match " + HttpIngress.ADDRESS_PATTERN)),
                    SETTINGS.check(Map.of("address", bad), Direction.IN), bad);
        for (String gone : List.of("host", "port", "path", "shutdownTimeoutMs"))
            assertEquals(List.of(new Settings.Problem("/" + gone, "no such setting")),
                    SETTINGS.check(Map.of("address", "/in", gone, 1), Direction.IN), gone + ": the node's HTTP port has replaced it");
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
                SETTINGS.check(Map.of("address", "/in", "timeoutMs", 5), Direction.IN));
        assertEquals(List.of(new Settings.Problem("/address", "a setting of in endpoints only")),
                SETTINGS.check(Map.of("maxBodyBytes", 1, "address", "/x"), Direction.OUT));
        assertEquals(List.of(new Settings.Problem("/flow", "no such setting")),
                SETTINGS.check(Map.of("maxBodyBytes", 1, "flow", "f"), Direction.OUT), "the node sets flow on in endpoints only");
        assertEquals(List.of(new Settings.Problem("/proxy", "no such setting")), SETTINGS.check(Map.of("address", "/in", "proxy", "p"), Direction.IN));
    }
}
