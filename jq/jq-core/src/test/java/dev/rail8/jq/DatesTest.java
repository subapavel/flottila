package dev.rail8.jq;

import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.steps;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.JqTermination.Budget;
import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.host.Host;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The date natives: {@code now} reads the host's clock; {@code localtime}, {@code strflocaltime}, and {@code strftime}
 * and {@code strptime} for {@code %s}, the host's zone; a local time that New York's change of offset skips or repeats
 * converts as jq 1.8.2 does. {@code strftime} and {@code strptime} tick once per started KiB of the longer of their
 * input and their output, the others not at all. Expected values are jq-ref 1.8.2's under the same {@code TZ}; the
 * tick counts are differences between two filters that differ only there, each found as the fewest steps its run
 * completes with. Every input is a constant folded at parse time.
 */
class DatesTest {
  private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
  private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

  @Test
  void nowReadsTheHostsClockInWholeMicroseconds() {
    Host host = Host.builder().clock(InstantSource.fixed(Instant.ofEpochSecond(1_425_599_507L, 123_456_789))).build();
    assertThat(results(host, "now")).containsExactly("1425599507.123456");
    assertThat(results(host, "now | todate")).containsExactly("\"2015-03-05T23:51:47Z\"");
  }

  @Test
  void theLocalNativesReadTheHostsZone() {
    Host tokyo = Host.builder().zone(TOKYO).build();
    assertThat(results(tokyo, "1425599507 | localtime")).containsExactly("[2015,2,6,8,51,47,5,64]");
    assertThat(results(tokyo, "1425599507 | strflocaltime(\"%c %Z %z\")")).containsExactly("\"Fri Mar  6 08:51:47 2015 JST +0900\"");
    assertThat(results(tokyo, "0 | strftime(\"%s\")")).containsExactly("\"-32400\"");
    assertThat(results(tokyo, "\"1425599507\" | strptime(\"%s\")")).containsExactly("[2015,2,6,8,51,47,5,64]");
  }

  @Test
  void theOtherNativesAreUtcWhateverTheZone() {
    Host tokyo = Host.builder().zone(TOKYO).build();
    assertThat(results(tokyo, "1425599507 | gmtime")).containsExactly("[2015,2,5,23,51,47,4,63]");
    assertThat(results(tokyo, "1425599507 | todate")).containsExactly("\"2015-03-05T23:51:47Z\"");
    assertThat(results(tokyo, "\"2015-03-05T23:51:47Z\" | fromdate")).containsExactly("1425599507");
    assertThat(results(tokyo, "\"1425599507\" | strptime(\"%s\") | mktime")).containsExactly("1425631907");
  }

  @Test
  void theSandboxedHostsZoneIsUtc() {
    assertThat(results(Host.sandboxed(), "1425599507 | localtime")).containsExactly("[2015,2,5,23,51,47,4,63]");
    assertThat(results(Host.sandboxed(), "0 | strflocaltime(\"%Z %z\")")).containsExactly("\"UTC +0000\"");
  }

  @Test
  void aSkippedLocalTimeMovesOnByTheGap() {
    Host newYork = Host.builder().zone(NEW_YORK).build();
    assertThat(results(newYork, "[2021,2,14,2,30,0,0,0] | strflocaltime(\"%H:%M %Z %s\")")).containsExactly("\"03:30 EDT 1615707000\"");
  }

  @Test
  void aRepeatedLocalTimeTakesTheOffsetTheRunsPreviousConversionFound() {
    Host newYork = Host.builder().zone(NEW_YORK).build();
    JqEngine engine = JqEngine.builder().host(newYork).build();
    JqFilter filter =
        engine.compile("[2021,10,7,1,30,0,0,0] | strflocaltime(\"%Z %s\"), ([2021,0,1,0,0,0,0,0] | strflocaltime(\"%Z\")), "
            + "([2021,10,7,1,30,0,0,0] | strflocaltime(\"%Z %s\"))");
    Workspace workspace = engine.newWorkspace();
    for (int run = 0; run < 2; run++) {
      // Each run starts afresh on the same Workspace: the first conversion takes daylight saving time.
      assertThat(results(filter, workspace)).as("run %s", run).containsExactly("\"EDT 1636263000\"", "\"EST\"", "\"EST 1636266600\"");
    }
  }

  @Test
  void strptimeTicksOncePerStartedKibOfTheLongerOfItsInputAndFormat() {
    long input = fewestSteps("(\"1\" + \" \" * 1023) | strptime(\"%d\")");
    assertThat(fewestSteps("(\"1\" + \" \" * 1024) | strptime(\"%d\")")).as("an input of 1025 bytes").isEqualTo(input + 1);
    long format = fewestSteps("\"1\" | strptime(\"%d\" + \" \" * 1022)");
    assertThat(fewestSteps("\"1\" | strptime(\"%d\" + \" \" * 1023)")).as("a format of 1025 bytes").isEqualTo(format + 1);
    // With an input of 1025 bytes, a format of 1024 or 1025 bytes ticks alike; each reads all the input's spaces.
    assertThat(fewestSteps("(\"1\" + \" \" * 1024) | strptime(\"%d\" + \" \" * 1023)"))
        .as("a format of 1025 bytes on an input of 1025")
        .isEqualTo(fewestSteps("(\"1\" + \" \" * 1024) | strptime(\"%d\" + \" \" * 1022)"));
  }

  @Test
  void strftimeTicksOncePerStartedKibOfTheLongerOfItsFormatAndOutput() {
    // Both formats are 1020 bytes; the first prints 1020 bytes, the second 1042.
    long kib = fewestSteps("0 | strftime(\"x\" * 1018 + \"%y\")");
    assertThat(fewestSteps("0 | strftime(\"x\" * 1018 + \"%c\")")).as("an output of 1042 bytes").isEqualTo(kib + 1);
    assertThat(fewestSteps("0 | strftime(\"x\" * 1025)")).as("a format of 1025 bytes").isEqualTo(kib + 1);
  }

  @Test
  void theOtherDateNativesTickAlikeWhateverTheirInput() {
    assertThat(fewestSteps("1e15 | gmtime")).as("gmtime of a far instant").isEqualTo(fewestSteps("0 | gmtime"));
    assertThat(fewestSteps("1e15 | localtime")).as("localtime of a far instant").isEqualTo(fewestSteps("0 | localtime"));
    assertThat(fewestSteps("[1e9,0,1] | mktime")).as("mktime of a far year").isEqualTo(fewestSteps("[1970,0,1] | mktime"));
  }

  /** The compact text of each result of {@code filter} on null, on an engine with {@code host}. */
  private static List<String> results(Host host, String filter) {
    JqEngine engine = JqEngine.builder().host(host).build();
    return results(engine.compile(filter), engine.newWorkspace());
  }

  private static List<String> results(JqFilter filter, Workspace workspace) {
    Results all = new Results();
    assertThat(filter.run(JqNull.NULL, NONE, all, workspace)).as("run's answer").isTrue();
    return all.values.stream().map(JqValue::toJson).toList();
  }

  /** The fewest steps {@code filter} on null completes with. */
  private static long fewestSteps(String filter) {
    long low = 0;
    long high = 1;
    while (!completes(filter, high)) {
      low = high;
      high *= 2;
    }
    while (high - low > 1) {
      long mid = (low + high) >>> 1;
      if (completes(filter, mid)) {
        high = mid;
      } else {
        low = mid;
      }
    }
    return high;
  }

  /** Whether {@code filter} on null completes with {@code limit} steps. */
  private static boolean completes(String filter, long limit) {
    JqEngine engine = steps(limit);
    try {
      return engine.compile(filter).run(JqNull.NULL, NONE, new Results(), engine.newWorkspace());
    } catch (JqTermination t) {
      assertThat(t.budget()).as("the budget %s ends with", filter).isEqualTo(Budget.STEPS);
      return false;
    }
  }
}
