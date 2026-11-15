package io.flottila.mapping.serializer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes one XML document as UTF-8 bytes (ADR-0022): the declaration {@code <?xml version="1.0"
 * encoding="UTF-8"?>}, then the elements without indentation and without a trailing newline. A
 * prefix is bound on the first element that needs it, for its own name or an attribute's, where it
 * is not already bound to the same namespace; a default namespace is never declared. In text,
 * {@code &}, {@code <} and {@code >} are escaped and CR is written {@code &#13;}; in an attribute
 * value {@code "} is escaped too, and LF and TAB are written {@code &#10;} and {@code &#9;} (#102).
 * An element without content is self-closing.
 *
 * <p>The serializer writes what it is told and never invents a prefix; the prefixes come from
 * lowering. It holds the state of one document and is not thread-safe.
 *
 * <p>It encodes UTF-8 itself, into one buffer per document, and writes the same bytes as the JDK's
 * {@code OutputStreamWriter} did before #194, which allocated per write: a surrogate pair is one
 * character even when two writes split it, and a lone surrogate is written {@code ?}.
 */
public final class XmlSerializer {

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

  /** The bytes the buffer holds before they go to the output stream. */
  private static final int BUFFER = 8192;

  private final OutputStream out;

  /** The encoded bytes not yet written to {@link #out}: the first {@link #buffered}. */
  private final byte[] buffer = new byte[BUFFER];

  private int buffered;

  /**
   * The high surrogate written last, whose low surrogate may come with the next write, or {@code
   * 0}: it is written only with the character after it.
   */
  private char high;

  /**
   * An open element: its prefix and local name, and the number of {@link #bindings} in scope at its
   * start, which its end restores.
   */
  private record Open(String prefix, String localName, int bindings) {}

  /** The open elements, outermost first. */
  private final List<Open> open = new ArrayList<>();

  /**
   * The prefix bindings the open elements declared, outermost first: per binding its prefix, then
   * its namespace. An element's own and its attributes' bindings go on the element (#102).
   */
  private final List<String> bindings = new ArrayList<>();

  /** Whether the last start tag still waits for its {@code >} or {@code />}. */
  private boolean startTagOpen;

  /**
   * A serializer onto {@code out}. {@link #endDocument()} flushes; closing {@code out} is the
   * caller's.
   */
  public XmlSerializer(OutputStream out) {
    this.out = out;
  }

  /** Writes the XML declaration. */
  public void startDocument() throws IOException {
    write(DECLARATION);
  }

  /**
   * Starts an element.
   *
   * @param prefix the prefix of {@code namespace}; {@code ""} for a name without a namespace
   * @param namespace the element's namespace, or {@code ""}
   * @param localName the element's local name
   * @throws IllegalArgumentException for a namespace without a prefix: it would need a default
   *     namespace or an invented prefix
   */
  public void startElement(String prefix, String namespace, String localName) throws IOException {
    if (prefix.isEmpty() && !namespace.isEmpty()) {
      throw new IllegalArgumentException(
          "the namespace " + namespace + " of " + localName + " has no prefix");
    }
    closeStartTag();
    write('<');
    writeName(prefix, localName);
    open.add(new Open(prefix, localName, bindings.size()));
    startTagOpen = true;
    declare(prefix, namespace);
  }

  /**
   * Writes an attribute of the element whose start tag is open (#102), after the declaration of its
   * prefix when the prefix is not bound to its namespace there. The binding then holds for the
   * element's content.
   *
   * @param prefix the prefix of {@code namespace}; {@code ""} for a name without a namespace
   * @param namespace the attribute's namespace, or {@code ""}
   * @param localName the attribute's local name
   * @param value the attribute's value, written with {@code &}, {@code <}, {@code >}, {@code "},
   *     CR, LF and TAB escaped
   * @throws IllegalStateException when no start tag is open: before the first element, after the
   *     open element's content has begun, or after it ended
   */
  public void attribute(String prefix, String namespace, String localName, String value)
      throws IOException {
    if (!startTagOpen) {
      throw new IllegalStateException(
          "the attribute " + localName + " comes where no element's start tag is open");
    }
    declare(prefix, namespace);
    write(' ');
    writeName(prefix, localName);
    write("=\"");
    escape(value, true);
    write('"');
  }

  /**
   * Writes the declaration of {@code prefix} into the open start tag, and records its binding
   * there, unless it is {@code ""} or already bound to {@code namespace}.
   */
  private void declare(String prefix, String namespace) throws IOException {
    if (!prefix.isEmpty() && !bound(prefix, namespace)) {
      write(" xmlns:");
      write(prefix);
      write("=\"");
      escape(namespace, true);
      write('"');
      bindings.add(prefix);
      bindings.add(namespace);
    }
  }

  /** Writes {@code text} as content of the open element; {@code ""} writes nothing. */
  public void text(String text) throws IOException {
    if (text.isEmpty()) {
      return;
    }
    closeStartTag();
    escape(text, false);
  }

  /** Ends the element started last: {@code />} if it has no content, else its end tag. */
  public void endElement() throws IOException {
    Open top = open.removeLast();
    if (startTagOpen) {
      startTagOpen = false;
      write("/>");
    } else {
      write("</");
      writeName(top.prefix(), top.localName());
      write('>');
    }
    while (bindings.size() > top.bindings()) {
      bindings.removeLast();
    }
  }

  /** Ends the document and flushes its bytes to the output stream. */
  public void endDocument() throws IOException {
    drain();
    out.flush();
  }

  private void closeStartTag() throws IOException {
    if (startTagOpen) {
      startTagOpen = false;
      write('>');
    }
  }

  private void writeName(String prefix, String localName) throws IOException {
    if (!prefix.isEmpty()) {
      write(prefix);
      write(':');
    }
    write(localName);
  }

  /**
   * Whether {@code prefix} is bound to {@code namespace} at the innermost open element: the binding
   * declared last for that prefix.
   */
  private boolean bound(String prefix, String namespace) {
    for (int k = bindings.size() - 2; k >= 0; k -= 2) {
      if (bindings.get(k).equals(prefix)) {
        return bindings.get(k + 1).equals(namespace);
      }
    }
    return false;
  }

  /**
   * Writes {@code text} with {@code &}, {@code <}, {@code >} and CR escaped, and in an attribute
   * value {@code "}, LF and TAB as well, which a parser would otherwise normalise (XML 1.0 §3.3.3).
   * Every other character is written as it is.
   */
  private void escape(String text, boolean attribute) throws IOException {
    for (int k = 0; k < text.length(); k++) {
      char c = text.charAt(k);
      String reference =
          switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '\r' -> "&#13;";
            case '"' -> attribute ? "&quot;" : null;
            case '\n' -> attribute ? "&#10;" : null;
            case '\t' -> attribute ? "&#9;" : null;
            default -> null;
          };
      if (reference != null) {
        write(reference);
      } else {
        write(c);
      }
    }
  }

  private void write(String text) throws IOException {
    for (int k = 0; k < text.length(); k++) {
      write(text.charAt(k));
    }
  }

  /**
   * Encodes {@code c}, one UTF-16 unit, into the buffer: a high surrogate waits for the next
   * character, with which a low surrogate makes one character of four bytes; any other surrogate is
   * {@code ?}, as UTF-8 encodes none.
   */
  private void write(char c) throws IOException {
    if (high != 0) {
      char pending = high;
      high = 0;
      if (Character.isLowSurrogate(c)) {
        int codePoint = Character.toCodePoint(pending, c);
        put(0xF0 | (codePoint >>> 18));
        put(0x80 | ((codePoint >>> 12) & 0x3F));
        put(0x80 | ((codePoint >>> 6) & 0x3F));
        put(0x80 | (codePoint & 0x3F));
        return;
      }
      put('?');
    }
    if (c < 0x80) {
      put(c);
    } else if (c < 0x800) {
      put(0xC0 | (c >>> 6));
      put(0x80 | (c & 0x3F));
    } else if (Character.isHighSurrogate(c)) {
      high = c;
    } else if (Character.isLowSurrogate(c)) {
      put('?');
    } else {
      put(0xE0 | (c >>> 12));
      put(0x80 | ((c >>> 6) & 0x3F));
      put(0x80 | (c & 0x3F));
    }
  }

  /** Puts the byte {@code b} into the buffer, which is drained first when it is full. */
  private void put(int b) throws IOException {
    if (buffered == BUFFER) {
      drain();
    }
    buffer[buffered++] = (byte) b;
  }

  /** Writes the buffered bytes to the output stream. */
  private void drain() throws IOException {
    out.write(buffer, 0, buffered);
    buffered = 0;
  }
}
