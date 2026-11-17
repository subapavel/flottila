package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ParseAssertions.ir;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.syntaxError;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chars of a FIELD name (#34 design section 4: {@code .} then {@code [A-Za-z_][A-Za-z0-9_]*}) at the edges of each
 * range, and the char just outside each edge.
 */
class FieldNameCharsTest {

  @Test
  void aNameTakesTheEdgesOfEachRange() {
    // jq-ref 1.8.2, observed 2026-11-11: .zZ09, ._, .Z, .z0 on {"zZ09":1,"_":2,"Z":3,"z0":4} -> 1, 2, 3, 4 (rc 0).
    for (String name : List.of("zZ09", "_", "Z", "z0", "a", "A")) {
      Intrinsic field = (Intrinsic) ir("." + name);
      assertThat(field.op().name()).as("the operation of .%s", name).isEqualTo("." + name);
      assertThat(field.source()).as("the chars of .%s", name).isEqualTo(new Source(0, name.length() + 1));
    }
  }

  @Test
  void theCharJustOutsideARangeEndsTheName() {
    // After .a each char would extend the name if a range took it, and .a<char> would then parse; after a bare dot it
    // would start one. Each is a syntax error (until #62 a Stub), at the char after .a (column 3) or after the dot
    // (column 2): diff/syntax.rec field-then-* and dot-*. After a bare dot, @, ` and { also give jq's recovery
    // message, a second block, whose text SyntaxErrorTest checks.
    syntaxError(".a@", "INVALID_CHARACTER", 1, 3, 1);
    syntaxError(".@", "INVALID_CHARACTER", 1, 2, 2);
    syntaxError(".a[", "end of file", 1, 3, 1);
    syntaxError(".[", "end of file", 1, 2, 1);
    syntaxError(".a`", "INVALID_CHARACTER", 1, 3, 1);
    syntaxError(".`", "INVALID_CHARACTER", 1, 2, 2);
    syntaxError(".a{", "'{'", 1, 3, 1);
    syntaxError(".{", "'{'", 1, 2, 2);
    syntaxError(".a/", "end of file", 1, 3, 1);
    syntaxError("./", "end of file", 1, 2, 1);
    syntaxError(".a:", "':'", 1, 3, 1);
    syntaxError(".:", "':'", 1, 2, 1);
  }
}
