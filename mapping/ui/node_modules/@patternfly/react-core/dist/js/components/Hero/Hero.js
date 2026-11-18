"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Hero = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const hero_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Hero/hero"));
const react_styles_1 = require("@patternfly/react-styles");
const c_hero_BackgroundImage_light_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero_BackgroundImage_light'));
const c_hero_BackgroundImage_dark_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero_BackgroundImage_dark'));
const c_hero_gradient_stop_1_light_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero_gradient_stop_1_light'));
const c_hero_gradient_stop_2_light_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero_gradient_stop_2_light'));
const c_hero_gradient_stop_3_light_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero_gradient_stop_3_light'));
const c_hero_gradient_stop_1_dark_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero_gradient_stop_1_dark'));
const c_hero_gradient_stop_2_dark_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero_gradient_stop_2_dark'));
const c_hero_gradient_stop_3_dark_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero_gradient_stop_3_dark'));
const c_hero__body_Width_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero__body_Width'));
const c_hero__body_MaxWidth_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_hero__body_MaxWidth'));
const Hero = (_a) => {
    var { className, children, backgroundSrcLight, backgroundSrcDark, gradientLight, gradientDark, isGlass = false, bodyWidth, bodyMaxWidth } = _a, props = tslib_1.__rest(_a, ["className", "children", "backgroundSrcLight", "backgroundSrcDark", "gradientLight", "gradientDark", "isGlass", "bodyWidth", "bodyMaxWidth"]);
    const customStyles = {};
    if (backgroundSrcLight) {
        customStyles[c_hero_BackgroundImage_light_1.default.name] = `url(${backgroundSrcLight})`;
    }
    if (backgroundSrcDark) {
        customStyles[c_hero_BackgroundImage_dark_1.default.name] = `url(${backgroundSrcDark})`;
    }
    if (gradientLight === null || gradientLight === void 0 ? void 0 : gradientLight.stop1) {
        customStyles[c_hero_gradient_stop_1_light_1.default.name] = gradientLight.stop1;
    }
    if (gradientLight === null || gradientLight === void 0 ? void 0 : gradientLight.stop2) {
        customStyles[c_hero_gradient_stop_2_light_1.default.name] = gradientLight.stop2;
    }
    if (gradientLight === null || gradientLight === void 0 ? void 0 : gradientLight.stop3) {
        customStyles[c_hero_gradient_stop_3_light_1.default.name] = gradientLight.stop3;
    }
    if (gradientDark === null || gradientDark === void 0 ? void 0 : gradientDark.stop1) {
        customStyles[c_hero_gradient_stop_1_dark_1.default.name] = gradientDark.stop1;
    }
    if (gradientDark === null || gradientDark === void 0 ? void 0 : gradientDark.stop2) {
        customStyles[c_hero_gradient_stop_2_dark_1.default.name] = gradientDark.stop2;
    }
    if (gradientDark === null || gradientDark === void 0 ? void 0 : gradientDark.stop3) {
        customStyles[c_hero_gradient_stop_3_dark_1.default.name] = gradientDark.stop3;
    }
    if (bodyWidth) {
        customStyles[c_hero__body_Width_1.default.name] = bodyWidth;
    }
    if (bodyMaxWidth) {
        customStyles[c_hero__body_MaxWidth_1.default.name] = bodyMaxWidth;
    }
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(hero_1.default.hero, isGlass && hero_1.default.modifiers.glass, className), style: Object.assign(Object.assign({}, props.style), customStyles) }, props, { children: (0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(hero_1.default.heroBody), children: children }) })));
};
exports.Hero = Hero;
exports.Hero.displayName = 'Hero';
//# sourceMappingURL=Hero.js.map