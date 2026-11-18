import { createIconBase } from '../createIcon.js';

export const PlusIconConfig = {
  name: 'PlusIcon',
  icon: {"xOffset":0,"yOffset":0,"width":448,"height":512,"svgPathData":"M416 208H272V64c0-17.67-14.33-32-32-32h-32c-17.67 0-32 14.33-32 32v144H32c-17.67 0-32 14.33-32 32v32c0 17.67 14.33 32 32 32h144v144c0 17.67 14.33 32 32 32h32c17.67 0 32-14.33 32-32V304h144c17.67 0 32-14.33 32-32v-32c0-17.67-14.33-32-32-32z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M29 15H17V3a1 1 0 0 0-2 0v12H3a1 1 0 0 0 0 2h12v12a1 1 0 0 0 2 0V17h12a1 1 0 0 0 0-2Z"},
};

export const PlusIcon = createIconBase(PlusIconConfig);

export default PlusIcon;