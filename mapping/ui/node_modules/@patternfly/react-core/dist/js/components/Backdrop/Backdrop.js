"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Backdrop = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_styles_1 = require("@patternfly/react-styles");
const backdrop_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Backdrop/backdrop"));
const helpers_1 = require("../../helpers");
const Backdrop = (_a) => {
    var { children = null, className = '', hasAnimations: hasAnimationsProp = true, isVisible = true } = _a, props = tslib_1.__rest(_a, ["children", "className", "hasAnimations", "isVisible"]);
    const hasAnimations = (0, helpers_1.useHasAnimations)(hasAnimationsProp);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({}, props, { className: (0, react_styles_1.css)(backdrop_1.default.backdrop, hasAnimations && backdrop_1.default.modifiers.animate, hasAnimations && isVisible && backdrop_1.default.modifiers.show, className), children: children })));
};
exports.Backdrop = Backdrop;
exports.Backdrop.displayName = 'Backdrop';
//# sourceMappingURL=Backdrop.js.map