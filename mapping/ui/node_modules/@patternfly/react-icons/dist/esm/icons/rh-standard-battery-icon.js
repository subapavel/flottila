import { createIconBase } from '../createIcon.js';

export const RhStandardBatteryIconConfig = {
  name: 'RhStandardBatteryIcon',
  icon: {"xOffset":0,"yOffset":0,"width":36,"height":36,"svgClassName":"pf-v6-icon-rh-standard","svgPathData":[{"path":"M31 14.375h-1.375V12a.625.625 0 0 0-.625-.625H5a.625.625 0 0 0-.625.625v12c0 .345.28.625.625.625h24c.345 0 .625-.28.625-.625v-2.375H31c.345 0 .625-.28.625-.625v-6a.625.625 0 0 0-.625-.625Zm-2.625 9H5.625v-10.75h22.75v10.75Zm2-3h-.75v-4.75h.75v4.75Z"},{"path":"M25 17.375h-1.375V16a.625.625 0 0 0-1.25 0v1.375H21a.625.625 0 0 0 0 1.25h1.375V20a.625.625 0 0 0 1.25 0v-1.375H25a.625.625 0 0 0 0-1.25Zm-12 0H9a.625.625 0 0 0 0 1.25h4a.625.625 0 0 0 0-1.25Z"}]},
  rhUiIcon: null,
};

export const RhStandardBatteryIcon = createIconBase(RhStandardBatteryIconConfig);

export default RhStandardBatteryIcon;