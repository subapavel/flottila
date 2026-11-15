package io.flottila.steps.validate;

import org.w3c.dom.ls.LSInput;

import java.io.InputStream;
import java.io.Reader;

/**
 * The value the confined resolver hands the schema parser: the included file's open stream and its URI as the system
 * id, the base its own includes resolve against. The nulls are behaviour: Xerces prefers a character stream or string
 * data over the byte stream, and would open the system id itself if everything were null. Read-only.
 */
final class SchemaInput implements LSInput {

    private final InputStream byteStream;
    private final String systemId;

    SchemaInput(InputStream byteStream, String systemId) {
        this.byteStream = byteStream;
        this.systemId = systemId;
    }

    @Override
    public InputStream getByteStream() {
        return byteStream;
    }

    @Override
    public String getSystemId() {
        return systemId;
    }

    @Override
    public Reader getCharacterStream() {
        return null;
    }

    @Override
    public String getStringData() {
        return null;
    }

    @Override
    public String getPublicId() {
        return null;
    }

    @Override
    public String getBaseURI() {
        return null;
    }

    @Override
    public String getEncoding() {
        return null;
    }

    @Override
    public boolean getCertifiedText() {
        return false;
    }

    @Override
    public void setByteStream(InputStream byteStream) {
        throw readOnly();
    }

    @Override
    public void setCharacterStream(Reader characterStream) {
        throw readOnly();
    }

    @Override
    public void setStringData(String stringData) {
        throw readOnly();
    }

    @Override
    public void setSystemId(String systemId) {
        throw readOnly();
    }

    @Override
    public void setPublicId(String publicId) {
        throw readOnly();
    }

    @Override
    public void setBaseURI(String baseURI) {
        throw readOnly();
    }

    @Override
    public void setEncoding(String encoding) {
        throw readOnly();
    }

    @Override
    public void setCertifiedText(boolean certifiedText) {
        throw readOnly();
    }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("a schema input is read-only");
    }
}
