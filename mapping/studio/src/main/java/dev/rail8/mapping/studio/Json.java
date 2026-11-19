package dev.rail8.mapping.studio;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The studio's one JSON reader and writer.
 *
 * <p>A request binds only as its contract says. A key twice in one object fails it, so a test run's
 * working copy reaches the engine as the client wrote it. An unknown member, a missing required
 * member, a null member or a null list entry fails, and a number or a boolean is no string. A
 * required member is a component marked {@code @JsonProperty(required = true)}, whose rule comes
 * first and names it missing; the null rule alone would refuse it as a null one. A component marked
 * {@code @JsonSetter(nulls = Nulls.SET)} may be missing or null: both are null. A null inside a
 * test run's model is the model's: the tree reader binds no record.
 *
 * <p>An answer's member that is null is written only when it is required; each optional one is
 * marked to be left out.
 */
final class Json {

  private Json() {}

  static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .defaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.FAIL, Nulls.FAIL))
          .withCoercionConfig(
              LogicalType.Textual,
              coercion ->
                  coercion
                      .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
          .build();

  /** {@code value} as JSON text. */
  static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      // every answer is a record of strings, numbers, booleans, lists, maps and trees
      throw new IllegalStateException("an answer cannot be written", e);
    }
  }

  /**
   * {@code bytes} bound to {@code type}; an empty body and the body {@code null} bind to no
   * request.
   *
   * @throws InvalidRequestException when the bytes bind to no request
   * @throws JsonProcessingException when they are no JSON document or bind to no {@code type}
   */
  static <T> T read(byte[] bytes, Class<T> type)
      throws InvalidRequestException, JsonProcessingException {
    T request = null;
    if (bytes.length > 0) {
      try {
        request = MAPPER.readValue(bytes, type);
      } catch (JsonProcessingException e) {
        throw e;
      } catch (IOException e) {
        // the bytes are in memory: only Jackson's own exceptions are left
        throw new UncheckedIOException(e);
      }
    }
    if (request == null) {
      throw new InvalidRequestException("the request body is empty or null");
    }
    return request;
  }
}
