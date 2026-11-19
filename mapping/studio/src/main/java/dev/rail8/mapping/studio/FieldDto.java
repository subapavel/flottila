package dev.rail8.mapping.studio;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.rail8.mapping.structure.Field;
import dev.rail8.mapping.structure.FieldPath;
import dev.rail8.mapping.structure.Occurrence;
import dev.rail8.mapping.structure.ValueType;
import java.util.Locale;

/**
 * A field of a structure type, on the wire. A member that is null is absent.
 *
 * @param kind {@code element}, {@code attribute} or {@code json-property}
 * @param step the last step of the field's path, for example {@code @id}, {@code Q{urn:a}Order} or,
 *     percent-encoded, {@code a%2Fb}
 * @param namespace the namespace, {@code ""} when the name has none
 * @param localName the local name
 * @param min the effective minimum occurrence
 * @param max the effective maximum occurrence; null when it is unbounded
 * @param valueType the XPath name of a leaf field's value type, for example {@code xs:date}; null
 *     for a structure field
 * @param nillable whether the field may carry {@code xsi:nil}
 * @param type the id of the structure type with a structure field's child fields; null for a leaf
 * @param refusal the field's own refusal, for a refused field, else null; a type's fields are
 *     shared by every field of that type, so a field below a refused field does not carry its
 *     ancestor's here: the editor's tree derives the refusal nearest the root
 */
record FieldDto(
    String kind,
    String step,
    String namespace,
    String localName,
    long min,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long max,
    @JsonInclude(JsonInclude.Include.NON_NULL) String valueType,
    boolean nillable,
    @JsonInclude(JsonInclude.Include.NON_NULL) String type,
    @JsonInclude(JsonInclude.Include.NON_NULL) RefusalDto refusal) {

  static FieldDto of(Field field) {
    FieldPath.Step step = field.step();
    long max = field.occurrence().max();
    return new FieldDto(
        // ELEMENT is element, JSON_PROPERTY is json-property
        step.kind().name().toLowerCase(Locale.ROOT).replace('_', '-'),
        step.toString(),
        step.namespace(),
        step.localName(),
        field.occurrence().min(),
        max == Occurrence.UNBOUNDED ? null : max,
        field.valueType().map(ValueType::typeName).orElse(null),
        field.nillable(),
        field.type().orElse(null),
        field.refusal().map(RefusalDto::of).orElse(null));
  }
}
