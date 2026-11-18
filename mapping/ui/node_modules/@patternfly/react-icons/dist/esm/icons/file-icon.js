import { createIconBase } from '../createIcon.js';

export const FileIconConfig = {
  name: 'FileIcon',
  icon: {"xOffset":0,"yOffset":0,"width":384,"height":512,"svgPathData":"M224 136V0H24C10.7 0 0 10.7 0 24v464c0 13.3 10.7 24 24 24h336c13.3 0 24-10.7 24-24V160H248c-13.2 0-24-10.8-24-24zm160-14.1v6.1H256V0h6.1c6.4 0 12.5 2.5 17 7l97.9 98c4.5 4.5 7 10.6 7 16.9z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M26.5 1H12a1.001 1.001 0 0 0-.708.294L4.294 8.292A1.001 1.001 0 0 0 4 9v20.5c0 .827.673 1.5 1.5 1.5h21c.827 0 1.5-.673 1.5-1.5v-27c0-.827-.673-1.5-1.5-1.5ZM11 4.414V8H7.414L11 4.414ZM26 29H6V10h5.5c.827 0 1.5-.673 1.5-1.5V3h13v26Z"},
};

export const FileIcon = createIconBase(FileIconConfig);

export default FileIcon;