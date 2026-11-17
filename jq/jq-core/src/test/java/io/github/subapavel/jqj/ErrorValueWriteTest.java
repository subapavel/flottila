package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Writing an error value (#34 design section 7): the string a filter error carries is built at run time, and #35's
 * writer arm prints it with jq's re-escape (its design K3 ended the Stub this test held until then). Exported types
 * only.
 */
class ErrorValueWriteTest {

  @Test
  void writingABuiltStringIsJqsText() {
    // jq-ref 1.8.2, observed 2026-11-12: printf '[1]' | jq-ref -c 'try .a catch .'
    //   -> "Cannot index array with string (\"a\")"   (rc 0): the error value, its quotes escaped.
    Workspace workspace = newWorkspace();
    JqValue root = read(workspace, "[1]").root();
    JqFilterException e =
        assertThatExceptionOfType(JqFilterException.class)
            .as("run of .a on [1]")
            .isThrownBy(() -> JqEngine.builder().build().compile(".a").run(root, Bindings.builder().build(), v -> true, workspace))
            .actual();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JqSink sink = JsonWriter.sink(workspace, out, OutputOptions.compact());
    assertThat(sink.emit(e.value())).as("emit's answer for the error value").isTrue();
    assertThat(out.toString(UTF_8)).as("bytes written for the error value of .a on [1]").isEqualTo("\"Cannot index array with string (\\\"a\\\")\"\n");
    assertThat(e.value().toJson()).as("toJson() of the error value").isEqualTo("\"Cannot index array with string (\\\"a\\\")\"");

    // The same sink then writes a document's value after it.
    sink.emit(root);
    assertThat(out.toString(UTF_8)).as("bytes written for the error value and [1]").isEqualTo("\"Cannot index array with string (\\\"a\\\")\"\n[1]\n");
  }
}
