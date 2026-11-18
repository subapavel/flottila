import { createIconBase } from '../createIcon.js';

export const StopIconConfig = {
  name: 'StopIcon',
  icon: {"xOffset":0,"yOffset":0,"width":448,"height":512,"svgPathData":"M400 32H48C21.5 32 0 53.5 0 80v352c0 26.5 21.5 48 48 48h352c26.5 0 48-21.5 48-48V80c0-26.5-21.5-48-48-48z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M26 30H6.007c-2.206 0-4-1.794-4-4V6.007c0-2.206 1.794-4 4-4H26c2.206 0 4 1.794 4 4V26c0 2.206-1.794 4-4 4ZM6.007 4.007c-1.103 0-2 .897-2 2V26c0 1.103.897 2 2 2H26c1.103 0 2-.897 2-2V6.007c0-1.103-.897-2-2-2H6.007Z"},
};

export const StopIcon = createIconBase(StopIconConfig);

export default StopIcon;