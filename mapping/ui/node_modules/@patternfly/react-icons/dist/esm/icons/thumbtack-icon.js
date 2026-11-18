import { createIconBase } from '../createIcon.js';

export const ThumbtackIconConfig = {
  name: 'ThumbtackIcon',
  icon: {"xOffset":0,"yOffset":0,"width":384,"height":512,"svgPathData":"M298.028 214.267L285.793 96H328c13.255 0 24-10.745 24-24V24c0-13.255-10.745-24-24-24H56C42.745 0 32 10.745 32 24v48c0 13.255 10.745 24 24 24h42.207L85.972 214.267C37.465 236.82 0 277.261 0 328c0 13.255 10.745 24 24 24h136v104.007c0 1.242.289 2.467.845 3.578l24 48c2.941 5.882 11.364 5.893 14.311 0l24-48a8.008 8.008 0 0 0 .845-3.578V352h136c13.255 0 24-10.745 24-24-.001-51.183-37.983-91.42-85.973-113.733z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M21 11.522V3h2a1 1 0 1 0 0-2H9a1 1 0 1 0 0 2h2v8.522A8.998 8.998 0 0 0 7 19a1 1 0 0 0 1 1h7v10a1 1 0 1 0 2 0V20h7a1 1 0 0 0 1-1 8.995 8.995 0 0 0-4-7.478ZM13 3h6v7.525c-.94-.334-1.947-.525-3-.525s-2.06.191-3 .525V3ZM9.071 18c.486-3.388 3.408-6 6.929-6s6.442 2.612 6.929 6H9.072Z"},
};

export const ThumbtackIcon = createIconBase(ThumbtackIconConfig);

export default ThumbtackIcon;