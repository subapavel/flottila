package dev.rail8.mapping.service;

import dev.rail8.mapping.structure.Field;
import dev.rail8.mapping.structure.FieldPath;
import dev.rail8.mapping.structure.Occurrence;
import dev.rail8.mapping.structure.ValueType;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A field of a structure type, on the wire.
 *
 * @param kind {@code element}, {@code attribute} or {@code json-property}
 * @param step the last step of the field's path, for example {@code @id}, {@code Q{urn:a}Order} or,
 *     percent-encoded, {@code a%2Fb}
 * @param namespace the namespace, {@code ""} when the name has none
 * @param localName the local name
 * @param min the effective minimum occurrence
 * @param max the effective maximum occurrence; absent when it is unbounded
 * @param valueType the XPath name of a leaf field's value type, for example {@code xs:date}
 * @param nillable whether the field may carry {@code xsi:nil}
 * @param type the id of the structure type with a structure field's child fields
 * @param refusal the field's own refusal, for a refused field; a type's fields are shared by every
 *     field of that type, so a field below a refused field does not carry its ancestor's here: the
 *     editor's tree derives the refusal nearest the root
 */
public record FieldDto(
    @Schema(
            required = true,
            enumeration = {"element", "attribute", "json-property"})
        String kind,
    @Schema(required = true) String step,
    @Schema(required = true) String namespace,
    @Schema(required = true) String localName,
    @Schema(required = true) long min,
    @Schema(nullable = false) Optional<Long> max,
    @Schema(nullable = false) Optional<String> valueType,
    @Schema(required = true) boolean nillable,
    @Schema(nullable = false) Optional<String> type,
    @Schema(nullable = false) Optional<RefusalDto> refusal) {

  static FieldDto of(Field field) {
    FieldPath.Step step = field.step();
    return new FieldDto(
        // ELEMENT is element, JSON_PROPERTY is json-property
        step.kind().name().toLowerCase(Locale.ROOT).replace('_', '-'),
        step.toString(),
        step.namespace(),
        step.localName(),
        field.occurrence().min(),
        Optional.of(field.occurrence().max()).filter(max -> max != Occurrence.UNBOUNDED),
        field.valueType().map(ValueType::typeName),
        field.nillable(),
        field.type(),
        field.refusal().map(RefusalDto::of));
  }
}
