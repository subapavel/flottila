package io.flottila.mapping.runtime;

/**
 * The codes of the runtime errors a mapping run raises from its data (ADR-0006, ADR-0015, ADR-0025
 * since #52): error QNames, the same names the export raises. A context function has one, {@code
 * po:<catalogue name upper-cased>}, whichever of its conditions fails; a standard function's data
 * error is the W3C code the export raises too ({@code err:FORG0001}, {@code err:FOAR0001}, ADR-0025
 * "Names and fidelity"). A test run's limit, {@code flottila:TIMEOUT}, is not one of them.
 */
public final class RuntimeErrors {

  /** Fewer created occurrences of a target field than its minimum, at its target field mapping. */
  public static final String MINOCCURS = "po:MINOCCURS";

  /** {@code createIf} meets a value that is no boolean, {@code ""} included, at its box. */
  public static final String CREATEIF = "po:CREATEIF";

  /** {@code formatByExample}'s inputs hold unequal numbers of values, at its box. */
  public static final String FORMATBYEXAMPLE = "po:FORMATBYEXAMPLE";

  /** {@code sort} meets a numeric key that is no {@code xs:decimal}, at its box. */
  public static final String SORT = "po:SORT";

  /** {@code sortByKey}'s keys and values do not pair up, or a numeric key fails, at its box. */
  public static final String SORTBYKEY = "po:SORTBYKEY";

  /** {@code useOneAsMany}'s inputs do not have the counts it needs, at its box. */
  public static final String USEONEASMANY = "po:USEONEASMANY";

  /** {@code fixValues} meets a key its table lacks, with {@code missing} {@code error}. */
  public static final String FIXVALUES = "po:FIXVALUES";

  /** A function meets a value that is no number (or date), or has no number as its result. */
  public static final String FORG0001 = "err:FORG0001";

  /** {@code divide} or {@code inv} by zero, at its box. */
  public static final String FOAR0001 = "err:FOAR0001";

  private RuntimeErrors() {}
}
