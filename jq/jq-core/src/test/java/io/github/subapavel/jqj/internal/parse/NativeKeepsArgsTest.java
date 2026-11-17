package io.github.subapavel.jqj.internal.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Each built native's {@code keepsArgs} (ADR-0005 as amended by ADR-0022: the result may share a container or string
 * with an argument or the input, at any depth), declared before spec 3's {@code keepsArgsIsSound} checks it. The ones
 * that keep: {@code tostring} gives a string input back, {@code last/1} its generator's last value, {@code keys} and
 * {@code keys_unsorted} an object's key strings, {@code to_entries} its input's keys and members (#85),
 * {@code flatten} its input's members, and #73's sort and its kin
 * their input's members (min and max one of them). The rest build their result afresh or give no container or string.
 */
class NativeKeepsArgsTest {
  @Test
  void everyNativeDeclaresWhetherItKeepsItsArguments() {
    Map<String, Boolean> declared = new TreeMap<>();
    Arrays.stream(Binder.Native.values()).forEach(n -> declared.put(n.name(), n.keepsArgs));
    Map<String, Boolean> expected = new TreeMap<>();
    for (String keeps :
        new String[] {
          "TOSTRING", "LAST", "KEYS", "KEYS_UNSORTED", "TO_ENTRIES", "FLATTEN", "FLATTEN_DEPTH", "SORT", "SORT_BY", "GROUP_BY",
          "UNIQUE", "UNIQUE_BY", "MIN", "MAX", "MIN_BY", "MAX_BY"
        }) {
      expected.put(keeps, true);
    }
    for (String fresh :
        new String[] {
          "EMPTY", "ERROR", "NOT", "RANGE", "LENGTH", "TYPE", "TOJSON", "TONUMBER", "HAS", "NAN", "INFINITE", "ISNAN",
          "ISINFINITE", "ISNORMAL", "HAVE_DECNUM", "HAVE_LITERAL_NUMBERS"
        }) {
      expected.put(fresh, false);
    }
    assertThat(declared).as("keepsArgs of each built native").isEqualTo(expected);
  }
}
