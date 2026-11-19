package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.NOTE_AT_10_UTC;
import static dev.rail8.mapping.studio.Fixtures.notesInTheRunsZone;
import static dev.rail8.mapping.studio.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * A test run's time zone is its clock's: +01:00 here, so a test run writes a date at +01:00. Under
 * UTC the same date is written as it is read, 10:00 Z.
 */
class TestRunTimeZoneTest {

  /** The calls' instant in a time zone one hour ahead of UTC. */
  private static final Calls CALLS = Calls.studio().at(Calls.UTC.withZone(ZoneOffset.ofHours(1)));

  @Test
  void aTestRunWritesADateInTheConfiguredZone() {
    Calls.Answer reply =
        CALLS.post("test", testRequest(notesInTheRunsZone(), NOTE_AT_10_UTC), Workspace.mappings());

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("output");
    assertThat(result.path("output").textValue()).contains("<Note>11:00 +01:00</Note>");
  }
}
