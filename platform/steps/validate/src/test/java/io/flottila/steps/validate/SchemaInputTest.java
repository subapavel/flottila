package io.flottila.steps.validate;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code LSInput} the confined resolver hands the schema parser (FIX-6): the open byte stream of the included
 * file and that file's URI as its system id, nothing else, and read-only.
 */
class SchemaInputTest {

    private static final String SYSTEM_ID = "file:///flow/a.xsd";

    @Test
    void carriesTheByteStreamAndTheSystemIdAndNothingElse() {
        InputStream bytes = new ByteArrayInputStream("<xs:schema/>".getBytes(StandardCharsets.UTF_8));

        SchemaInput input = new SchemaInput(bytes, SYSTEM_ID);

        assertSame(bytes, input.getByteStream());
        assertEquals(SYSTEM_ID, input.getSystemId());
        // Xerces reads a character stream or string data before the byte stream: these must be null for our bytes to count.
        assertNull(input.getCharacterStream());
        assertNull(input.getStringData());
        assertNull(input.getPublicId());
        assertNull(input.getBaseURI());
        assertNull(input.getEncoding());
        assertFalse(input.getCertifiedText());
    }

    @Test
    void isReadOnly() {
        InputStream bytes = new ByteArrayInputStream("<xs:schema/>".getBytes(StandardCharsets.UTF_8));
        SchemaInput input = new SchemaInput(bytes, SYSTEM_ID);

        assertThrows(UnsupportedOperationException.class,
                () -> input.setByteStream(new ByteArrayInputStream(new byte[0])));
        assertThrows(UnsupportedOperationException.class,
                () -> input.setCharacterStream(new StringReader("<other/>")));
        assertThrows(UnsupportedOperationException.class, () -> input.setStringData("<other/>"));
        assertThrows(UnsupportedOperationException.class, () -> input.setSystemId("file:///elsewhere/b.xsd"));
        assertThrows(UnsupportedOperationException.class, () -> input.setPublicId("-//OTHER//EN"));
        assertThrows(UnsupportedOperationException.class, () -> input.setBaseURI("file:///elsewhere/"));
        assertThrows(UnsupportedOperationException.class, () -> input.setEncoding("UTF-16"));
        assertThrows(UnsupportedOperationException.class, () -> input.setCertifiedText(true));

        assertSame(bytes, input.getByteStream());
        assertEquals(SYSTEM_ID, input.getSystemId());
        assertNull(input.getCharacterStream());
        assertNull(input.getStringData());
        assertFalse(input.getCertifiedText());
    }
}
