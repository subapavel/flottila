"use strict"
exports.__esModule = true;
exports.PlayIconConfig = {
  name: 'PlayIcon',
  icon: {"xOffset":0,"yOffset":0,"width":448,"height":512,"svgPathData":"M424.4 214.7L72.4 6.6C43.8-10.3 0 6.1 0 47.9V464c0 37.5 40.7 60.1 72.4 41.3l352-208c31.4-18.5 31.5-64.1 0-82.6z"},
  rhUiIcon: {"xOffset":0,"yOffset":0,"width":32,"height":32,"svgPathData":"M3 30a1 1 0 0 1-1-1V3a1 1 0 0 1 1.447-.895l26 13a1 1 0 0 1 0 1.79l-26 13c-.141.07-.294.105-.447.105ZM4 4.618v22.764L26.764 16 4 4.618Z"},
};
exports.PlayIcon = require('../createIcon').createIconBase(exports.PlayIconConfig);
exports["default"] = exports.PlayIcon;