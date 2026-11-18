"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CompassNavMain = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const compass_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Compass/compass"));
const react_styles_1 = require("@patternfly/react-styles");
const CompassNavMain = (_a) => {
    var { children, className } = _a, props = tslib_1.__rest(_a, ["children", "className"]);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compassNavMain, className) }, props, { children: children })));
};
exports.CompassNavMain = CompassNavMain;
exports.CompassNavMain.displayName = 'CompassNavMain';
//# sourceMappingURL=CompassNavMain.js.map