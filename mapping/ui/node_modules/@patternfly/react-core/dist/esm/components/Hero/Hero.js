import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/Hero/hero.mjs';
import { css } from '@patternfly/react-styles';
import heroBackgroundImageLight from '@patternfly/react-tokens/dist/esm/c_hero_BackgroundImage_light';
import heroBackgroundImageDark from '@patternfly/react-tokens/dist/esm/c_hero_BackgroundImage_dark';
import heroGradientStop1Light from '@patternfly/react-tokens/dist/esm/c_hero_gradient_stop_1_light';
import heroGradientStop2Light from '@patternfly/react-tokens/dist/esm/c_hero_gradient_stop_2_light';
import heroGradientStop3Light from '@patternfly/react-tokens/dist/esm/c_hero_gradient_stop_3_light';
import heroGradientStop1Dark from '@patternfly/react-tokens/dist/esm/c_hero_gradient_stop_1_dark';
import heroGradientStop2Dark from '@patternfly/react-tokens/dist/esm/c_hero_gradient_stop_2_dark';
import heroGradientStop3Dark from '@patternfly/react-tokens/dist/esm/c_hero_gradient_stop_3_dark';
import heroBodyWidth from '@patternfly/react-tokens/dist/esm/c_hero__body_Width';
import heroBodyMaxWidth from '@patternfly/react-tokens/dist/esm/c_hero__body_MaxWidth';
export const Hero = (_a) => {
    var { className, children, backgroundSrcLight, backgroundSrcDark, gradientLight, gradientDark, isGlass = false, bodyWidth, bodyMaxWidth } = _a, props = __rest(_a, ["className", "children", "backgroundSrcLight", "backgroundSrcDark", "gradientLight", "gradientDark", "isGlass", "bodyWidth", "bodyMaxWidth"]);
    const customStyles = {};
    if (backgroundSrcLight) {
        customStyles[heroBackgroundImageLight.name] = `url(${backgroundSrcLight})`;
    }
    if (backgroundSrcDark) {
        customStyles[heroBackgroundImageDark.name] = `url(${backgroundSrcDark})`;
    }
    if (gradientLight === null || gradientLight === void 0 ? void 0 : gradientLight.stop1) {
        customStyles[heroGradientStop1Light.name] = gradientLight.stop1;
    }
    if (gradientLight === null || gradientLight === void 0 ? void 0 : gradientLight.stop2) {
        customStyles[heroGradientStop2Light.name] = gradientLight.stop2;
    }
    if (gradientLight === null || gradientLight === void 0 ? void 0 : gradientLight.stop3) {
        customStyles[heroGradientStop3Light.name] = gradientLight.stop3;
    }
    if (gradientDark === null || gradientDark === void 0 ? void 0 : gradientDark.stop1) {
        customStyles[heroGradientStop1Dark.name] = gradientDark.stop1;
    }
    if (gradientDark === null || gradientDark === void 0 ? void 0 : gradientDark.stop2) {
        customStyles[heroGradientStop2Dark.name] = gradientDark.stop2;
    }
    if (gradientDark === null || gradientDark === void 0 ? void 0 : gradientDark.stop3) {
        customStyles[heroGradientStop3Dark.name] = gradientDark.stop3;
    }
    if (bodyWidth) {
        customStyles[heroBodyWidth.name] = bodyWidth;
    }
    if (bodyMaxWidth) {
        customStyles[heroBodyMaxWidth.name] = bodyMaxWidth;
    }
    return (_jsx("div", Object.assign({ className: css(styles.hero, isGlass && styles.modifiers.glass, className), style: Object.assign(Object.assign({}, props.style), customStyles) }, props, { children: _jsx("div", { className: css(styles.heroBody), children: children }) })));
};
Hero.displayName = 'Hero';
//# sourceMappingURL=Hero.js.map