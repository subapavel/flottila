import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { createContext, useRef } from 'react';
import styles from '@patternfly/react-styles/css/components/Drawer/drawer.mjs';
import { css } from '@patternfly/react-styles';
import { useOUIAProps } from '../../helpers';
export var DrawerColorVariant;
(function (DrawerColorVariant) {
    DrawerColorVariant["default"] = "default";
    DrawerColorVariant["secondary"] = "secondary";
    /**
     * @deprecated `DrawerColorVariant.noBackground` is deprecated. Use the `isPlain` prop on `DrawerPanelContent` and the `DrawerSection`instead.
     */
    DrawerColorVariant["noBackground"] = "no-background";
})(DrawerColorVariant || (DrawerColorVariant = {}));
export const DrawerContext = createContext({
    isExpanded: false,
    isStatic: false,
    onExpand: () => { },
    position: 'end',
    drawerRef: null,
    drawerContentRef: null,
    isInline: false,
    isViewport: false
});
export const Drawer = (_a) => {
    var { className = '', children, isExpanded = false, isInline = false, isPill = false, isViewport = false, isStatic = false, position = 'end', onExpand = () => { }, ouiaId, ouiaSafe = true } = _a, props = __rest(_a, ["className", "children", "isExpanded", "isInline", "isPill", "isViewport", "isStatic", "position", "onExpand", "ouiaId", "ouiaSafe"]);
    const ouiaProps = useOUIAProps(Drawer.displayName, ouiaId, ouiaSafe);
    const drawerRef = useRef(undefined);
    const drawerContentRef = useRef(undefined);
    return (_jsx(DrawerContext.Provider, { value: { isExpanded, isStatic, onExpand, position, drawerRef, drawerContentRef, isInline, isViewport }, children: _jsx("div", Object.assign({ className: css(styles.drawer, isExpanded && styles.modifiers.expanded, isInline && styles.modifiers.inline, isPill && styles.modifiers.pill, isViewport && styles.modifiers.viewport, isStatic && styles.modifiers.static, (position === 'left' || position === 'start') && styles.modifiers.panelLeft, position === 'bottom' && styles.modifiers.panelBottom, className), ref: drawerRef }, props, ouiaProps, { children: children })) }));
};
Drawer.displayName = 'Drawer';
//# sourceMappingURL=Drawer.js.map