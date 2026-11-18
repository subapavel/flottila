"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CompassNavSearch = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const compass_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Compass/compass"));
const react_styles_1 = require("@patternfly/react-styles");
const Button_1 = require("../Button");
const Tooltip_1 = require("../Tooltip");
const CompassSearchIcon = () => ((0, jsx_runtime_1.jsxs)("svg", { width: "1em", height: "1em", className: "pf-v6-svg", viewBox: "0 0 20 20", fill: "none", xmlns: "http://www.w3.org/2000/svg", "aria-hidden": "true", children: [(0, jsx_runtime_1.jsx)("path", { d: "M14.166 14.166L17.4993 17.4993", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" }), (0, jsx_runtime_1.jsx)("path", { d: "M2.5 9.16667C2.5 12.8486 5.48477 15.8333 9.16667 15.8333C11.0108 15.8333 12.6801 15.0846 13.887 13.8744C15.0897 12.6685 15.8333 11.0044 15.8333 9.16667C15.8333 5.48477 12.8486 2.5 9.16667 2.5C5.48477 2.5 2.5 5.48477 2.5 9.16667Z", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" })] }));
const CompassNavSearch = (_a) => {
    var { 'aria-label': ariaLabel = 'Search', tooltipContent = 'Search', className, onClick } = _a, props = tslib_1.__rest(_a, ['aria-label', "tooltipContent", "className", "onClick"]);
    const buttonRef = (0, react_1.useRef)(null);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compassNav + '-search', className) }, props, { children: (0, jsx_runtime_1.jsx)(Tooltip_1.Tooltip, { content: tooltipContent, aria: "none", "aria-live": "off", triggerRef: buttonRef, children: (0, jsx_runtime_1.jsx)(Button_1.Button, { isCircle: true, variant: "plain", icon: (0, jsx_runtime_1.jsx)(CompassSearchIcon, {}), "aria-label": ariaLabel, onClick: onClick, ref: buttonRef }) }) })));
};
exports.CompassNavSearch = CompassNavSearch;
exports.CompassNavSearch.displayName = 'CompassNavSearch';
//# sourceMappingURL=CompassNavSearch.js.map