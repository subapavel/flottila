import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/Drawer/drawer.mjs';
import { css } from '@patternfly/react-styles';
import { Button } from '../Button';
import RhMicronsCloseIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-close-icon';
export const DrawerCloseButton = (_a) => {
    var { className = '', onClose = () => undefined, 'aria-label': ariaLabel = 'Close drawer panel', buttonProps } = _a, props = __rest(_a, ["className", "onClose", 'aria-label', "buttonProps"]);
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const _b = (buttonProps !== null && buttonProps !== void 0 ? buttonProps : {}), { onClick: _onClick } = _b, restButtonProps = __rest(_b, ["onClick"]);
    return (_jsx("div", Object.assign({ className: css(styles.drawerClose, className) }, props, { children: _jsx(Button, Object.assign({ variant: "plain", onClick: onClose, "aria-label": ariaLabel, icon: _jsx(RhMicronsCloseIcon, {}) }, restButtonProps)) })));
};
DrawerCloseButton.displayName = 'DrawerCloseButton';
//# sourceMappingURL=DrawerCloseButton.js.map