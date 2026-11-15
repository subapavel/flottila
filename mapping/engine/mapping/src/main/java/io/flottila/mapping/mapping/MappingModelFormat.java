package io.flottila.mapping.mapping;

import com.github.erosb.jsonsKema.IJsonValue;
import io.flottila.mapping.mapping.internal.CanonicalWriter;
import io.flottila.mapping.mapping.internal.ModelCheck;
import io.flottila.mapping.mapping.internal.ModelReader;
import io.flottila.mapping.mapping.internal.SchemaStage;
import io.flottila.mapping.mapping.internal.StrictJsonReader;
import java.util.List;

/**
 * Loads and saves mapping models in {@code formatVersion} 1 (ADR-0010, ADR-0011). Loading runs
 * validation stages 1 (schema) and 2 (model check); saving writes the canonical form, the only
 * spelling of a model.
 */
public final class MappingModelFormat {

  private MappingModelFormat() {}

  /**
   * Reads a mapping model. A refusal is a result: stage 1 refuses a document the schema does not
   * accept, or a newer {@code formatVersion}, and stage 2 refuses a model that fails the model
   * check. Stage 2 runs only on a document that stage 1 accepts.
   *
   * @param json the document's bytes
   * @return the model, or the diagnostics of the stage that refused it
   * @throws MalformedJsonException if the bytes are no JSON document (ADR-0011: a transport error)
   */
  public static LoadResult load(byte[] json) throws MalformedJsonException {
    IJsonValue document = StrictJsonReader.read(json);
    List<Diagnostic> schema = SchemaStage.check(document);
    if (!schema.isEmpty()) {
      return new Refused(schema);
    }
    MappingModel model = ModelReader.read(document);
    List<Diagnostic> check = ModelCheck.check(model);
    return check.isEmpty() ? new Loaded(model) : new Refused(check);
  }

  /** The canonical form of {@code model}: the bytes a save writes. */
  public static byte[] save(MappingModel model) {
    return CanonicalWriter.write(model);
  }
}
