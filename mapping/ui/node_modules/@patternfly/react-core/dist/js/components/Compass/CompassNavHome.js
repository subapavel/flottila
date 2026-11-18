"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CompassNavHome = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const compass_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Compass/compass"));
const react_styles_1 = require("@patternfly/react-styles");
const Button_1 = require("../Button");
const Tooltip_1 = require("../Tooltip");
const CompassHomeIcon = () => ((0, jsx_runtime_1.jsxs)("svg", { width: "1em", height: "1em", className: "pf-v6-svg", viewBox: "0 0 20 20", fill: "none", xmlns: "http://www.w3.org/2000/svg", "aria-hidden": "true", children: [(0, jsx_runtime_1.jsx)("path", { d: "M8.33268 13.334H11.666", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" }), (0, jsx_runtime_1.jsx)("path", { d: "M1.66602 6.66602L9.73102 2.63351C9.89994 2.54905 10.0988 2.54905 10.2677 2.63351L18.3327 6.66602", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" }), (0, jsx_runtime_1.jsx)("path", { d: "M16.6673 9.16602V15.4993C16.6673 16.6039 15.7719 17.4993 14.6673 17.4993H5.33398C4.22941 17.4993 3.33398 16.6039 3.33398 15.4993V9.16602", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" })] }));
const CompassNavHome = (_a) => {
    var { 'aria-label': ariaLabel = 'Home', tooltipContent = 'Home', className, onClick } = _a, props = tslib_1.__rest(_a, ['aria-label', "tooltipContent", "className", "onClick"]);
    const buttonRef = (0, react_1.useRef)(null);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compassNav + '-home', className) }, props, { children: (0, jsx_runtime_1.jsx)(Tooltip_1.Tooltip, { content: tooltipContent, position: "left", aria: "none", "aria-live": "off", triggerRef: buttonRef, children: (0, jsx_runtime_1.jsx)(Button_1.Button, { isCircle: true, variant: "plain", icon: (0, jsx_runtime_1.jsx)(CompassHomeIcon, {}), "aria-label": ariaLabel, onClick: onClick, ref: buttonRef }) }) })));
};
exports.CompassNavHome = CompassNavHome;
exports.CompassNavHome.displayName = 'CompassNavHome';
//# sourceMappingURL=CompassNavHome.js.map