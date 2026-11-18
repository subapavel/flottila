import { createIconBase } from '../createIcon.js';

export const CrossIconConfig = {
  name: 'CrossIcon',
  icon: {"xOffset":0,"yOffset":0,"width":384,"height":512,"svgPathData":"M352 128h-96V32c0-17.67-14.33-32-32-32h-64c-17.67 0-32 14.33-32 32v96H32c-17.67 0-32 14.33-32 32v64c0 17.67 14.33 32 32 32h96v224c0 17.67 14.33 32 32 32h64c17.67 0 32-14.33 32-32V256h96c17.67 0 32-14.33 32-32v-64c0-17.67-14.33-32-32-32z"},
  rhUiIcon: null,
};

export const CrossIcon = createIconBase(CrossIconConfig);

export default CrossIcon;