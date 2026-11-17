package dev.rail8.mapping.mapping;

/**
 * The position of one box, or of its target field mapping's target, in a layout. The engine ignores
 * it.
 *
 * @param x the horizontal coordinate, an integer in [-1,000,000, 1,000,000]
 * @param y the vertical coordinate, an integer in [-1,000,000, 1,000,000]
 */
public record BoxPosition(int x, int y) {}
