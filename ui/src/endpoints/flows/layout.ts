import type { FlowNode } from './types';

/**
 * What the layout places: a node; a column of blocks one below the other; a fork of columns side by side, between the
 * node that leaves into them and the one they join at; a node with a second one beside it to the right, the first one
 * kept on the line; and a frame around a block, a lane or a split's frame, whose node is the frame itself.
 */
export type Block =
  | { kind: 'node'; node: FlowNode }
  | { kind: 'column'; blocks: Block[] }
  | { kind: 'fork'; columns: Block[] }
  | { kind: 'beside'; main: FlowNode; side: FlowNode }
  | { kind: 'frame'; node: FlowNode; inner: Block };

/** The gap between blocks of a column, between columns, inside a frame and under its title, and between lanes. */
export const GAP_Y = 40;
export const GAP_X = 32;
export const PAD = 16;
export const HEADER = 32;
export const LANE_GAP = 80;

interface Size {
  width: number;
  height: number;
}

function measure(block: Block, sizes: Map<Block, Size>): Size {
  const known = sizes.get(block);
  if (known !== undefined) return known;
  let size: Size;
  switch (block.kind) {
    case 'node':
      size = { width: block.node.width, height: block.node.height };
      break;
    case 'column': {
      const parts = block.blocks.map((b) => measure(b, sizes));
      size = {
        width: Math.max(0, ...parts.map((p) => p.width)),
        height: parts.reduce((sum, p) => sum + p.height, 0) + GAP_Y * Math.max(0, parts.length - 1),
      };
      break;
    }
    case 'fork': {
      const parts = block.columns.map((b) => measure(b, sizes));
      size = {
        width: parts.reduce((sum, p) => sum + p.width, 0) + GAP_X * Math.max(0, parts.length - 1),
        height: Math.max(0, ...parts.map((p) => p.height)),
      };
      break;
    }
    case 'beside': {
      const half = block.main.width / 2 + GAP_X + block.side.width;
      size = { width: 2 * Math.ceil(half), height: Math.max(block.main.height, block.side.height) };
      break;
    }
    case 'frame': {
      const inner = measure(block.inner, sizes);
      size = { width: inner.width + 2 * PAD, height: inner.height + HEADER + PAD };
      break;
    }
  }
  sizes.set(block, size);
  return size;
}

/** Places `block` centred in a column `width` wide whose left edge is at `x`, its top at `y`. */
function place(block: Block, x: number, y: number, width: number, sizes: Map<Block, Size>) {
  const size = measure(block, sizes);
  const left = x + Math.floor((width - size.width) / 2);
  switch (block.kind) {
    case 'node':
      block.node.x = left;
      block.node.y = y;
      break;
    case 'column': {
      let top = y;
      for (const b of block.blocks) {
        place(b, left, top, size.width, sizes);
        top += measure(b, sizes).height + GAP_Y;
      }
      break;
    }
    case 'fork': {
      let at = left;
      for (const b of block.columns) {
        const w = measure(b, sizes).width;
        place(b, at, y, w, sizes);
        at += w + GAP_X;
      }
      break;
    }
    case 'beside': {
      const centre = left + size.width / 2;
      block.main.x = Math.floor(centre - block.main.width / 2);
      block.main.y = y;
      block.side.x = Math.floor(centre + block.main.width / 2 + GAP_X);
      block.side.y = y + Math.floor((block.main.height - block.side.height) / 2);
      break;
    }
    case 'frame':
      block.node.x = left;
      block.node.y = y;
      block.node.width = size.width;
      block.node.height = size.height;
      place(block.inner, left + PAD, y + HEADER, size.width - 2 * PAD, sizes);
      break;
  }
}

/**
 * Sets the position of every node of `lanes`, and the size of every frame: the lanes side by side from the left, their
 * tops at 0, each block centred in its column. The same blocks always give the same whole numbers.
 */
export function layout(lanes: readonly Block[]): void {
  const sizes = new Map<Block, Size>();
  let x = 0;
  for (const lane of lanes) {
    const { width } = measure(lane, sizes);
    place(lane, x, 0, width, sizes);
    x += width + LANE_GAP;
  }
}
