"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CompassHero = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const compass_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Compass/compass"));
const react_styles_1 = require("@patternfly/react-styles");
const CompassHero = (_a) => {
    var { className, children } = _a, props = tslib_1.__rest(_a, ["className", "children"]);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(`${compass_1.default.compass}__hero`, className) }, props, { children: children })));
};
exports.CompassHero = CompassHero;
exports.CompassHero.displayName = 'CompassHero';
//# sourceMappingURL=CompassHero.js.map