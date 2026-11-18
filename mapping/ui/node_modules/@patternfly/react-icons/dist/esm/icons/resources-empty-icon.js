import { createIconBase } from '../createIcon.js';

export const ResourcesEmptyIconConfig = {
  name: 'ResourcesEmptyIcon',
  icon: {"width":1024,"height":1024,"svgPathData":"M512,896 C300.2,896 128,723.9 128,512 C128,300.3 300.2,128 512,128 C723.7,128 896,300.2 896,512 C896,723.8 723.7,896 512,896 L512,896 Z M512.1,0 C229.7,0 0,229.8 0,512 C0,794.3 229.8,1024 512.1,1024 C794.4,1024 1024,794.3 1024,512 C1024,229.7 794.4,0 512.1,0 L512.1,0 Z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M16 31C7.729 31 1 24.271 1 16S7.729 1 16 1s15 6.729 15 15-6.729 15-15 15Zm0-28C8.832 3 3 8.832 3 16s5.832 13 13 13 13-5.832 13-13S23.168 3 16 3Z"},
};

export const ResourcesEmptyIcon = createIconBase(ResourcesEmptyIconConfig);

export default ResourcesEmptyIcon;