import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/Compass/compass.mjs';
import { css } from '@patternfly/react-styles';
export const CompassHero = (_a) => {
    var { className, children } = _a, props = __rest(_a, ["className", "children"]);
    return (_jsx("div", Object.assign({ className: css(`${styles.compass}__hero`, className) }, props, { children: children })));
};
CompassHero.displayName = 'CompassHero';
//# sourceMappingURL=CompassHero.js.map