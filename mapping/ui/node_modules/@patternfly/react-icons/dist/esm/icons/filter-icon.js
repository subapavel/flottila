import { createIconBase } from '../createIcon.js';

export const FilterIconConfig = {
  name: 'FilterIcon',
  icon: {"xOffset":0,"yOffset":0,"width":512,"height":512,"svgPathData":"M487.976 0H24.028C2.71 0-8.047 25.866 7.058 40.971L192 225.941V432c0 7.831 3.821 15.17 10.237 19.662l80 55.98C298.02 518.69 320 507.493 320 487.98V225.941l184.947-184.97C520.021 25.896 509.338 0 487.976 0z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M28.5 2h-25C2.673 2 2 2.673 2 3.5V7c0 .265.105.52.293.707L12 17.414V30a1 1 0 0 0 1.316.949l6-2A1 1 0 0 0 20 28V17.414l9.707-9.707A1 1 0 0 0 30 7V3.5c0-.827-.673-1.5-1.5-1.5ZM28 6.586l-9.707 9.707A1 1 0 0 0 18 17v10.279l-4 1.333V16.999a.997.997 0 0 0-.293-.707L4 6.586V4h24v2.586Z"},
};

export const FilterIcon = createIconBase(FilterIconConfig);

export default FilterIcon;