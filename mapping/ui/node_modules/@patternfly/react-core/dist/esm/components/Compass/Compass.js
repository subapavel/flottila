import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Drawer, DrawerContent, DrawerContentBody } from '../Drawer';
import styles from '@patternfly/react-styles/css/components/Compass/compass.mjs';
import { css } from '@patternfly/react-styles';
import { IS_INERT } from '../../helpers/inert';
export const Compass = (_a) => {
    var { className, masthead, dock, isDockExpanded, isDockOverlay, header, isHeaderExpanded = true, sidebarStart, isSidebarStartExpanded = true, main, sidebarEnd, isSidebarEndExpanded = true, footer, isFooterExpanded = true, drawerContent, drawerProps } = _a, props = __rest(_a, ["className", "masthead", "dock", "isDockExpanded", "isDockOverlay", "header", "isHeaderExpanded", "sidebarStart", "isSidebarStartExpanded", "main", "sidebarEnd", "isSidebarEndExpanded", "footer", "isFooterExpanded", "drawerContent", "drawerProps"]);
    const hasDrawer = drawerContent !== undefined;
    const compassContent = (_jsxs("div", { className: css(styles.compassContainer), children: [dock && masthead, dock && (_jsx("div", { className: css(`${styles.compass}__dock`, isDockExpanded && styles.modifiers.expanded, isDockOverlay && styles.modifiers.overlay), children: dock })), header && (_jsx("div", Object.assign({ className: css(styles.compassHeader, isHeaderExpanded && 'pf-m-expanded') }, (!isHeaderExpanded && { inert: IS_INERT }), { children: header }))), sidebarStart && (_jsx("div", Object.assign({ className: css(styles.compassSidebar, styles.modifiers.start, isSidebarStartExpanded && 'pf-m-expanded') }, (!isSidebarStartExpanded && { inert: IS_INERT }), { children: sidebarStart }))), main && _jsx("div", { className: css(styles.compassMain), children: main }), sidebarEnd && (_jsx("div", Object.assign({ className: css(styles.compassSidebar, styles.modifiers.end, isSidebarEndExpanded && 'pf-m-expanded') }, (!isSidebarEndExpanded && { inert: IS_INERT }), { children: sidebarEnd }))), footer && (_jsx("div", Object.assign({ className: css(styles.compassFooter, isFooterExpanded && 'pf-m-expanded') }, (!isFooterExpanded && { inert: IS_INERT }), { children: footer })))] }));
    return (_jsx("div", Object.assign({ className: css(styles.compass, dock && styles.modifiers.docked, className) }, props, { children: hasDrawer ? (_jsx(Drawer, Object.assign({ isPill: true }, drawerProps, { children: _jsx(DrawerContent, { panelContent: drawerContent, children: _jsx(DrawerContentBody, { children: compassContent }) }) }))) : (compassContent) })));
};
Compass.displayName = 'Compass';
//# sourceMappingURL=Compass.js.map