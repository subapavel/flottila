package dev.rail8.mapping.ir;

/**
 * The optimiser. The IR has nothing to fold, so it runs zero passes and only verifies the document
 * again; a deploy and a test run of a field link run the same IR.
 */
public final class IrOptimiser {

  private IrOptimiser() {}

  /**
   * The optimised form of {@code document}.
   *
   * @throws IllegalArgumentException when the verifier refuses the document
   */
  public static TargetDocument optimise(TargetDocument document) {
    IrVerifier.verify(document);
    return document;
  }
}
