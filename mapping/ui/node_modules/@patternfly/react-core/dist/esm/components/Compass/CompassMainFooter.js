import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/Compass/compass.mjs';
import { css } from '@patternfly/react-styles';
export const CompassMainFooter = (_a) => {
    var { className, children, isExpanded = true } = _a, props = __rest(_a, ["className", "children", "isExpanded"]);
    return (_jsx("div", Object.assign({ className: css(styles.compassMainFooter, isExpanded && 'pf-m-expanded', className) }, props, { children: children })));
};
CompassMainFooter.displayName = 'CompassMainFooter';
//# sourceMappingURL=CompassMainFooter.js.map