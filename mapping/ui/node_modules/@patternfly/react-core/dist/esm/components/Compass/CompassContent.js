import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { Drawer, DrawerContent } from '../Drawer';
import styles from '@patternfly/react-styles/css/components/Compass/compass.mjs';
import { css } from '@patternfly/react-styles';
export const CompassContent = (_a) => {
    var { children, className, drawerProps, drawerContent } = _a, props = __rest(_a, ["children", "className", "drawerProps", "drawerContent"]);
    const hasDrawer = drawerContent !== undefined;
    const compassContent = (_jsx("div", Object.assign({ className: css(styles.compassContent, className) }, props, { children: children })));
    if (hasDrawer) {
        return (_jsx(Drawer, Object.assign({ isPill: true }, drawerProps, { children: _jsx(DrawerContent, { panelContent: drawerContent, children: compassContent }) })));
    }
    return compassContent;
};
CompassContent.displayName = 'CompassContent';
//# sourceMappingURL=CompassContent.js.map