"use strict"
exports.__esModule = true;
exports.DesktopIconConfig = {
  name: 'DesktopIcon',
  icon: {"xOffset":0,"yOffset":0,"width":576,"height":512,"svgPathData":"M528 0H48C21.5 0 0 21.5 0 48v320c0 26.5 21.5 48 48 48h192l-16 48h-72c-13.3 0-24 10.7-24 24s10.7 24 24 24h272c13.3 0 24-10.7 24-24s-10.7-24-24-24h-72l-16-48h192c26.5 0 48-21.5 48-48V48c0-26.5-21.5-48-48-48zm-16 352H64V64h448v288z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M29.5 3h-27C1.673 3 1 3.673 1 4.5v17c0 .827.673 1.5 1.5 1.5H12v4H8a1 1 0 0 0 0 2h16a1 1 0 0 0 0-2h-4v-4h9.5c.827 0 1.5-.673 1.5-1.5v-17c0-.827-.673-1.5-1.5-1.5ZM18 27h-4v-4h4v4Zm11-6H3V5h26v16Z"},
};
exports.DesktopIcon = require('../createIcon').createIconBase(exports.DesktopIconConfig);
exports["default"] = exports.DesktopIcon;