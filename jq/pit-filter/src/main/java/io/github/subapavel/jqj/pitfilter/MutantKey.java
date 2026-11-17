package io.github.subapavel.jqj.pitfilter;

import java.util.List;
import org.pitest.mutationtest.engine.MutationDetails;

/**
 * A mutant's identity across PIT runs over the same classes: the six fields {@code scripts/ratchet.java} joins for its
 * union ({@code MUTANT_ID}), as PIT 1.30.2's {@code XMLReportListener} writes them, unescaped.
 */
record MutantKey(String mutatedClass, String mutatedMethod, String methodDescription, int lineNumber, String mutator,
    List<Integer> indexes) {

  static MutantKey of(MutationDetails d) {
    return new MutantKey(d.getClassName().asJavaName(), d.getMethod(), d.getId().getLocation().getMethodDesc(),
        d.getLineNumber(), d.getMutator(), d.getId().getIndexes());
  }
}
