import { createIconBase } from '../createIcon.js';

export const PlusCircleIconConfig = {
  name: 'PlusCircleIcon',
  icon: {"xOffset":0,"yOffset":0,"width":512,"height":512,"svgPathData":"M256 8C119 8 8 119 8 256s111 248 248 248 248-111 248-248S393 8 256 8zm144 276c0 6.6-5.4 12-12 12h-92v92c0 6.6-5.4 12-12 12h-56c-6.6 0-12-5.4-12-12v-92h-92c-6.6 0-12-5.4-12-12v-56c0-6.6 5.4-12 12-12h92v-92c0-6.6 5.4-12 12-12h56c6.6 0 12 5.4 12 12v92h92c6.6 0 12 5.4 12 12v56z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M16 1C7.729 1 1 7.729 1 16s6.729 15 15 15 15-6.729 15-15S24.271 1 16 1Zm7 16.125h-5.875V23a1.125 1.125 0 0 1-2.25 0v-5.875H9a1.125 1.125 0 0 1 0-2.25h5.875V9a1.125 1.125 0 0 1 2.25 0v5.875H23a1.125 1.125 0 0 1 0 2.25Z"},
};

export const PlusCircleIcon = createIconBase(PlusCircleIconConfig);

export default PlusCircleIcon;