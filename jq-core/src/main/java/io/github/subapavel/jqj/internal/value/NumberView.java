package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.value.JqNumber;
import java.math.BigDecimal;

/**
 * A number of a document (ADR-0003, ADR-0012, ADR-0016): its document and tape index. Public only so the kind can
 * permit it.
 */
public record NumberView(Document doc, int index) implements JqNumber, Literal {

  /** jq's compact text (ADR-0012): a CANONICAL token's bytes, any other token's canonical form. */
  @Override
  public String toJson() {
    return Printer.json(doc, index);
  }

  /** ADR-0012 "Values", ADR-0028: an Infinity literal gives an infinity. */
  @Override
  public double doubleValue() {
    long entry = doc.live()[index];
    return CanonicalForm.toDouble(doc.bytes, Tape.off(entry), Tape.span(entry) & Tape.NUMBER_LENGTH);
  }

  @Override
  public boolean fitsLong() {
    return (Tape.span(doc.live()[index]) & Tape.FITS_LONG) != 0;
  }

  @Override
  public long longValue() {
    long[] tape = doc.live();
    int span = Tape.span(tape[index]);
    if ((span & Tape.FITS_LONG) == 0) {
      throw new ArithmeticException("the number is not an integer that a long holds");
    }
    byte[] bytes = doc.bytes;
    int p = Tape.off(tape[index]);
    int end = p + (span & Tape.NUMBER_LENGTH);
    boolean negative = bytes[p] == '-';
    if (negative) {
      p++;
    }
    // Accumulated below zero, so the smallest long needs no special case; FITS_LONG rules out an overflow.
    long value = 0;
    for (; p < end; p++) {
      value = value * 10 - (bytes[p] - '0');
    }
    return negative ? value : -value;
  }

  /** ADR-0028: the canonical form's value, its scale kept; an Infinity literal throws {@code ArithmeticException}. */
  @Override
  public BigDecimal decimalValue() {
    long entry = doc.live()[index];
    return CanonicalForm.toDecimal(doc.bytes, Tape.off(entry), Tape.span(entry) & Tape.NUMBER_LENGTH);
  }

  /** The document's bytes; {@link #tokenOffset()} checks the document first. */
  @Override
  public byte[] tokenBytes() {
    return doc.bytes;
  }

  @Override
  public int tokenOffset() {
    return Tape.off(doc.live()[index]);
  }

  @Override
  public int tokenLength() {
    return Tape.span(doc.live()[index]) & Tape.NUMBER_LENGTH;
  }

  /** ADR-0012: a CANONICAL token of at most 15 bytes. */
  @Override
  public boolean isShort() {
    int span = Tape.span(doc.live()[index]);
    return (span & Tape.CANONICAL) != 0 && (span & Tape.NUMBER_LENGTH) <= 15;
  }
}
