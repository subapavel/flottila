package io.github.subapavel.jqj.query.run;

/**
 * What a {@code Collect} node makes of the results its body gave (ADR-0034 amendment): the node gathers them, charging
 * {@link Charges#CONTAINER} once and {@link Charges#ELEMENT} per result before it stores one (ADR-0035), and hands
 * them over here once its body has run out.
 */
@FunctionalInterface
public interface Collector {
  /**
   * The value of the results {@code [0, length)} of {@code elements}, in order. The array is the collector's to keep:
   * the node never writes it again.
   */
  Object collect(Object[] elements, int length);
}
