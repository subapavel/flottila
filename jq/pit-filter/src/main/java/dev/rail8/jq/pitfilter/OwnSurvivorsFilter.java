package dev.rail8.jq.pitfilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.Mutater;

/**
 * Keeps a mutant only if an own report names it with a status outside {@link OwnReport#KILLED}; every other mutant,
 * killed there or named by no own report, is dropped.
 */
final class OwnSurvivorsFilter implements MutationInterceptor {

  private final Set<MutantKey> survivors;

  OwnSurvivorsFilter(Set<MutantKey> survivors) {
    this.survivors = survivors;
  }

  @Override
  public InterceptorType type() {
    return InterceptorType.FILTER;
  }

  @Override
  public void begin(ClassTree clazz) {
    // The survivors are known up front; nothing depends on the class.
  }

  @Override
  public Collection<MutationDetails> intercept(Collection<MutationDetails> mutations, Mutater mutater) {
    List<MutationDetails> kept = new ArrayList<>();
    for (MutationDetails d : mutations) {
      if (survivors.contains(MutantKey.of(d))) {
        kept.add(d);
      }
    }
    return kept;
  }

  @Override
  public void end() {
    // Nothing is held per class.
  }
}
