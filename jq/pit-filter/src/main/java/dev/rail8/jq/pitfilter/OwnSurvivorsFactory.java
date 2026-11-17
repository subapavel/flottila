package dev.rail8.jq.pitfilter;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.plugin.Feature;
import org.pitest.plugin.FeatureParameter;

/**
 * PIT feature {@code OWN_SURVIVORS}: a crossModule run mutates only the mutants that the mutated modules' own runs left
 * alive (F-84). Each {@code report} parameter names one module's own {@code mutations.xml}.
 */
public final class OwnSurvivorsFactory implements MutationInterceptorFactory {

  static final FeatureParameter REPORT =
      FeatureParameter.named("report").withDescription("a module's own PIT mutations.xml; repeat once per module");

  @Override
  public String description() {
    return "Keeps only the mutants a module's own PIT run did not kill (not KILLED, TIMED_OUT or MEMORY_ERROR)";
  }

  @Override
  public Feature provides() {
    return Feature.named("OWN_SURVIVORS").withOnByDefault(false).withDescription(description()).withParameter(REPORT);
  }

  @Override
  public MutationInterceptor createInterceptor(InterceptorParameters params) {
    List<String> reports = params.getList(REPORT);
    if (reports.isEmpty()) {
      throw new IllegalStateException("OWN_SURVIVORS needs a report[<path of an own mutations.xml>] parameter");
    }
    Set<MutantKey> survivors = new HashSet<>();
    for (String report : reports) {
      survivors.addAll(OwnReport.survivors(Path.of(report)));
    }
    return new OwnSurvivorsFilter(survivors);
  }
}
