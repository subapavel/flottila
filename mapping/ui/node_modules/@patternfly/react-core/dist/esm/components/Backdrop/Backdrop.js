import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { css } from '@patternfly/react-styles';
import styles from '@patternfly/react-styles/css/components/Backdrop/backdrop.mjs';
import { useHasAnimations } from '../../helpers';
export const Backdrop = (_a) => {
    var { children = null, className = '', hasAnimations: hasAnimationsProp = true, isVisible = true } = _a, props = __rest(_a, ["children", "className", "hasAnimations", "isVisible"]);
    const hasAnimations = useHasAnimations(hasAnimationsProp);
    return (_jsx("div", Object.assign({}, props, { className: css(styles.backdrop, hasAnimations && styles.modifiers.animate, hasAnimations && isVisible && styles.modifiers.show, className), children: children })));
};
Backdrop.displayName = 'Backdrop';
//# sourceMappingURL=Backdrop.js.map