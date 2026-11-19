package dev.rail8.mapping.studio;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A POST call of the studio: {@code {"request": "<base64>", "files": {"<bundle reference>":
 * "<base64>"}, "escaping": ["<bundle reference>"], "reads": "stored" | "sent"}}. Only {@code
 * request} is required; {@code reads} is {@code canonical}'s, and required there.
 */
final class Call {

  /** The call as JSON. */
  private record Wire(
      @JsonProperty(required = true) String request,
      @JsonSetter(nulls = Nulls.SET) Map<String, String> files,
      @JsonSetter(nulls = Nulls.SET) List<String> escaping,
      @JsonSetter(nulls = Nulls.SET) String reads) {}

  private final byte[] request;
  private final Map<String, byte[]> files;
  private final Set<String> escaping;
  private final String reads;

  private Call(byte[] request, Map<String, byte[]> files, Set<String> escaping, String reads) {
    this.request = request;
    this.files = files;
    this.escaping = escaping;
    this.reads = reads;
  }

  /**
   * The call {@code json} makes of the operation {@code op}.
   *
   * @throws InvalidRequestException when it is not of the studio's shape: no JSON, an unknown or a
   *     missing member, base64 that is none, or {@code reads} where it does not belong
   */
  static Call read(String op, String json) throws InvalidRequestException {
    Wire wire;
    try {
      wire = Json.MAPPER.readValue(json, Wire.class);
    } catch (JsonProcessingException e) {
      throw new InvalidRequestException("not a call of the studio: " + e.getOriginalMessage());
    }
    if (wire == null) {
      throw new InvalidRequestException("not a call of the studio: null");
    }
    boolean canonical = op.equals("canonical");
    if (canonical != (wire.reads() != null)
        || canonical && !List.of("stored", "sent").contains(wire.reads())) {
      throw new InvalidRequestException(
          "a canonical call reads \"stored\" or \"sent\", and no other call has reads");
    }
    Map<String, byte[]> files = null;
    if (wire.files() != null) {
      files = new LinkedHashMap<>();
      for (Map.Entry<String, String> file : wire.files().entrySet()) {
        files.put(file.getKey(), base64(file.getValue(), "the file " + file.getKey()));
      }
    }
    Set<String> escaping = wire.escaping() == null ? Set.of() : Set.copyOf(wire.escaping());
    return new Call(base64(wire.request(), "the request"), files, escaping, wire.reads());
  }

  private static byte[] base64(String text, String what) throws InvalidRequestException {
    try {
      return Base64.getDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException(what + " is not base64: " + e.getMessage());
    }
  }

  /** The request: the browser's body, or the stored model. */
  byte[] request() {
    return request;
  }

  /** A resolver over the files the call sent, which notes what it reads when it sent none. */
  BundleFiles resolver() {
    return new BundleFiles(files, escaping);
  }

  /** Whether the call sent files: a call without them may answer {@code files-needed}. */
  boolean sentFiles() {
    return files != null;
  }

  /** Whether a canonical call reads a stored model, not a sent one. */
  boolean readsStored() {
    return "stored".equals(reads);
  }
}
