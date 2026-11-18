import { createIconBase } from '../createIcon.js';

export const SearchIconConfig = {
  name: 'SearchIcon',
  icon: {"xOffset":0,"yOffset":0,"width":512,"height":512,"svgPathData":"M505 442.7L405.3 343c-4.5-4.5-10.6-7-17-7H372c27.6-35.3 44-79.7 44-128C416 93.1 322.9 0 208 0S0 93.1 0 208s93.1 208 208 208c48.3 0 92.7-16.4 128-44v16.3c0 6.4 2.5 12.5 7 17l99.7 99.7c9.4 9.4 24.6 9.4 33.9 0l28.3-28.3c9.4-9.4 9.4-24.6.1-34zM208 336c-70.7 0-128-57.2-128-128 0-70.7 57.2-128 128-128 70.7 0 128 57.2 128 128 0 70.7-57.2 128-128 128z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"m30.796 29.205-8.557-8.557A11.945 11.945 0 0 0 25 13c0-6.617-5.383-12-12-12S1 6.383 1 13s5.383 12 12 12c2.904 0 5.57-1.038 7.648-2.761l8.556 8.556a1.122 1.122 0 0 0 1.592 0 1.127 1.127 0 0 0 0-1.591ZM3 13C3 7.486 7.486 3 13 3s10 4.486 10 10-4.486 10-10 10S3 18.514 3 13Z"},
};

export const SearchIcon = createIconBase(SearchIconConfig);

export default SearchIcon;