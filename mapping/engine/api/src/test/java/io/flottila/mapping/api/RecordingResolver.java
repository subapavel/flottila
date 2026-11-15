package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.flottila.mapping.structure.importer.ReferenceResolver;
import io.flottila.mapping.structure.importer.ResolvedFile;
import io.flottila.mapping.structure.importer.UnresolvedReferenceException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A test-scope reference resolver over files held in memory (ADR-0014 "Reference resolver shape").
 * It records every reference it is asked for, in order. A reference marked as a package reference
 * is refused as {@code PACKAGE_REFERENCE_UNSUPPORTED}; any other reference it does not hold is
 * {@code UNREADABLE}.
 */
final class RecordingResolver implements ReferenceResolver {

  private final Map<String, byte[]> files = new HashMap<>();
  private final Set<String> packageReferences = new HashSet<>();
  private final List<String> calls = new ArrayList<>();

  /** Holds {@code content} under {@code reference}, as the bundle spells it. */
  RecordingResolver file(String reference, byte[] content) {
    files.put(reference, content.clone());
    return this;
  }

  RecordingResolver file(String reference, String content) {
    return file(reference, content.getBytes(UTF_8));
  }

  /** Refuses {@code reference} as a package reference only a host could read. */
  RecordingResolver packageReference(String reference) {
    packageReferences.add(reference);
    return this;
  }

  /** The references asked for, in order. */
  List<String> calls() {
    return List.copyOf(calls);
  }

  @Override
  public ResolvedFile resolve(String reference) throws UnresolvedReferenceException {
    calls.add(reference);
    if (packageReferences.contains(reference)) {
      throw new UnresolvedReferenceException(
          UnresolvedReferenceException.Reason.PACKAGE_REFERENCE_UNSUPPORTED, reference);
    }
    byte[] content = files.get(reference);
    if (content == null) {
      throw new UnresolvedReferenceException(
          UnresolvedReferenceException.Reason.UNREADABLE, reference);
    }
    return new ResolvedFile(reference, content.clone());
  }
}
