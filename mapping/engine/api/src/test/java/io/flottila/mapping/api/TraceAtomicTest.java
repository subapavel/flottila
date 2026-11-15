package io.flottila.mapping.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AtomicType;
import org.junit.jupiter.api.Test;

/**
 * An atomic trace entry names its type by its XML Schema name (ADR-0004), so a caller that does not
 * reach the IR, as the service, can show it (ADR-0015 {@code {atomic: {type, value}}}, #83).
 */
class TraceAtomicTest {

  @Test
  void theTypeNameIsTheXmlSchemaName() {
    assertThat(new Trace.Atomic(AtomicType.STRING, "k").typeName()).isEqualTo("xs:string");
    assertThat(new Trace.Atomic(AtomicType.INTEGER, "1").typeName()).isEqualTo("xs:integer");
  }
}
