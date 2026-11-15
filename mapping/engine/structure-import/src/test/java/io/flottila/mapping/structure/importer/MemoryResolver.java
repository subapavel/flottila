package io.flottila.mapping.structure.importer;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A test-scope, in-memory reference resolver (ADR-0014 "Reference resolver shape", F-24): the files
 * it is given, by reference, and every reference it is asked for, in order. Any other reference is
 * {@code UNREADABLE}.
 */
final class MemoryResolver implements ReferenceResolver {

  private final Map<String, byte[]> files = new HashMap<>();
  private final List<String> calls = new ArrayList<>();

  MemoryResolver file(String reference, String content) {
    return file(reference, content.getBytes(UTF_8));
  }

  MemoryResolver file(String reference, byte[] content) {
    files.put(reference, content);
    return this;
  }

  @Override
  public ResolvedFile resolve(String reference) throws UnresolvedReferenceException {
    calls.add(reference);
    byte[] content = files.get(reference);
    if (content == null) {
      throw new UnresolvedReferenceException(
          UnresolvedReferenceException.Reason.UNREADABLE, reference);
    }
    return new ResolvedFile(reference, content);
  }

  /** The references asked for, in order. */
  List<String> calls() {
    return calls;
  }
}
