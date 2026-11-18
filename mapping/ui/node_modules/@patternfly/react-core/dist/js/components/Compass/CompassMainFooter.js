"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CompassMainFooter = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const compass_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Compass/compass"));
const react_styles_1 = require("@patternfly/react-styles");
const CompassMainFooter = (_a) => {
    var { className, children, isExpanded = true } = _a, props = tslib_1.__rest(_a, ["className", "children", "isExpanded"]);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compassMainFooter, isExpanded && 'pf-m-expanded', className) }, props, { children: children })));
};
exports.CompassMainFooter = CompassMainFooter;
exports.CompassMainFooter.displayName = 'CompassMainFooter';
//# sourceMappingURL=CompassMainFooter.js.map