package io.flottila.mapping.api;

import io.flottila.mapping.mapping.LoadResult;
import io.flottila.mapping.mapping.Loaded;
import io.flottila.mapping.mapping.MalformedJsonException;
import io.flottila.mapping.mapping.MappingModel;
import io.flottila.mapping.mapping.MappingModelFormat;
import io.flottila.mapping.mapping.StructureRef;
import java.nio.file.Path;

/**
 * Stored fingerprints for the fixture models of tests that are not about the fingerprint check.
 * Compile refuses a stored fingerprint that is not the one its bundle gives (ADR-0009 as amended on
 * 2026-11-05, #91), so such a fixture stores a placeholder and is refreshed here before it is
 * compiled, as {@code POST /refresh} will do. {@code FingerprintPinTest} pins the real values.
 */
final class Fingerprints {

  private Fingerprints() {}

  /**
   * {@code model} with each side's fingerprint the one its bundle, read under {@code base}, gives.
   * A model that stages 1 and 2 refuse is returned as it is: compile never reaches the check.
   *
   * @throws IllegalArgumentException when {@code model} is no JSON document, a broken fixture
   */
  static byte[] refreshed(byte[] model, Path base) {
    LoadResult result;
    try {
      result = MappingModelFormat.load(model);
    } catch (MalformedJsonException e) {
      throw new IllegalArgumentException("a fixture model that is no JSON document", e);
    }
    if (!(result instanceof Loaded(MappingModel loaded))) {
      return model;
    }
    return MappingModelFormat.save(
        new MappingModel(
            loaded.formatVersion(),
            loaded.description(),
            loaded.source().map(ref -> refreshed(ref, base)),
            loaded.target().map(ref -> refreshed(ref, base)),
            loaded.targetFieldMappings(),
            loaded.udfs()));
  }

  /**
   * {@code ref} with the fingerprint its bundle gives, or as it is when the bundle fails to load.
   */
  private static StructureRef refreshed(StructureRef ref, Path base) {
    return switch (Structures.load(DirectoryResolver.over(base), ref.bundle(), ref.root())) {
      case StructureResult.Loaded(var structure) ->
          new StructureRef(ref.bundle(), ref.root(), structure.fingerprint());
      case StructureResult.Refused refused -> ref;
    };
  }
}
