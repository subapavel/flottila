import { createIconBase } from '../createIcon.js';

export const TableIconConfig = {
  name: 'TableIcon',
  icon: {"xOffset":0,"yOffset":0,"width":512,"height":512,"svgPathData":"M464 32H48C21.49 32 0 53.49 0 80v352c0 26.51 21.49 48 48 48h416c26.51 0 48-21.49 48-48V80c0-26.51-21.49-48-48-48zM224 416H64v-96h160v96zm0-160H64v-96h160v96zm224 160H288v-96h160v96zm0-160H288v-96h160v96z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M5 19a1 1 0 0 1 1-1h9v-4a1 1 0 1 1 2 0v4h9a1 1 0 1 1 0 2h-9v4a1 1 0 1 1-2 0v-4H6a1 1 0 0 1-1-1ZM31 4.5v23c0 .827-.673 1.5-1.5 1.5h-27c-.827 0-1.5-.673-1.5-1.5v-23C1 3.673 1.673 3 2.5 3h27c.827 0 1.5.673 1.5 1.5ZM29 11H3v16h26V11Z"},
};

export const TableIcon = createIconBase(TableIconConfig);

export default TableIcon;