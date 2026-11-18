import { createIconBase } from '../createIcon.js';

export const BarsIconConfig = {
  name: 'BarsIcon',
  icon: {"xOffset":0,"yOffset":0,"width":448,"height":512,"svgPathData":"M16 132h416c8.837 0 16-7.163 16-16V76c0-8.837-7.163-16-16-16H16C7.163 60 0 67.163 0 76v40c0 8.837 7.163 16 16 16zm0 160h416c8.837 0 16-7.163 16-16v-40c0-8.837-7.163-16-16-16H16c-8.837 0-16 7.163-16 16v40c0 8.837 7.163 16 16 16zm0 160h416c8.837 0 16-7.163 16-16v-40c0-8.837-7.163-16-16-16H16c-8.837 0-16 7.163-16 16v40c0 8.837 7.163 16 16 16z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M31 16a1 1 0 0 1-1 1H2a1 1 0 1 1 0-2h28a1 1 0 0 1 1 1Zm-1 9H2a1 1 0 1 0 0 2h28a1 1 0 1 0 0-2ZM2 7h28a1 1 0 1 0 0-2H2a1 1 0 1 0 0 2Z"},
};

export const BarsIcon = createIconBase(BarsIconConfig);

export default BarsIcon;