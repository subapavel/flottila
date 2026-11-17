package dev.rail8.jq.query.internal.interpret;

/**
 * The links between frames: a closure's call activation that binds slots runs in a frame whose cell 0 links the frame
 * the closure was written in, its defining frame.
 */
final class Frames {
  private Frames() {}

  /** The frame {@code level} links out from {@code frame}: {@code frame} itself for 0. */
  static Object[] out(Object[] frame, int level) {
    Object[] f = frame;
    for (int i = 0; i < level; i++) {
      f = (Object[]) f[0];
    }
    return f;
  }
}
