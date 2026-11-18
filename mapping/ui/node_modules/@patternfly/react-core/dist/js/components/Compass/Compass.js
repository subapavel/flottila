"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Compass = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const Drawer_1 = require("../Drawer");
const compass_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Compass/compass"));
const react_styles_1 = require("@patternfly/react-styles");
const inert_1 = require("../../helpers/inert");
const Compass = (_a) => {
    var { className, masthead, dock, isDockExpanded, isDockOverlay, header, isHeaderExpanded = true, sidebarStart, isSidebarStartExpanded = true, main, sidebarEnd, isSidebarEndExpanded = true, footer, isFooterExpanded = true, drawerContent, drawerProps } = _a, props = tslib_1.__rest(_a, ["className", "masthead", "dock", "isDockExpanded", "isDockOverlay", "header", "isHeaderExpanded", "sidebarStart", "isSidebarStartExpanded", "main", "sidebarEnd", "isSidebarEndExpanded", "footer", "isFooterExpanded", "drawerContent", "drawerProps"]);
    const hasDrawer = drawerContent !== undefined;
    const compassContent = ((0, jsx_runtime_1.jsxs)("div", { className: (0, react_styles_1.css)(compass_1.default.compassContainer), children: [dock && masthead, dock && ((0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(`${compass_1.default.compass}__dock`, isDockExpanded && compass_1.default.modifiers.expanded, isDockOverlay && compass_1.default.modifiers.overlay), children: dock })), header && ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compassHeader, isHeaderExpanded && 'pf-m-expanded') }, (!isHeaderExpanded && { inert: inert_1.IS_INERT }), { children: header }))), sidebarStart && ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compassSidebar, compass_1.default.modifiers.start, isSidebarStartExpanded && 'pf-m-expanded') }, (!isSidebarStartExpanded && { inert: inert_1.IS_INERT }), { children: sidebarStart }))), main && (0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(compass_1.default.compassMain), children: main }), sidebarEnd && ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compassSidebar, compass_1.default.modifiers.end, isSidebarEndExpanded && 'pf-m-expanded') }, (!isSidebarEndExpanded && { inert: inert_1.IS_INERT }), { children: sidebarEnd }))), footer && ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compassFooter, isFooterExpanded && 'pf-m-expanded') }, (!isFooterExpanded && { inert: inert_1.IS_INERT }), { children: footer })))] }));
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compass, dock && compass_1.default.modifiers.docked, className) }, props, { children: hasDrawer ? ((0, jsx_runtime_1.jsx)(Drawer_1.Drawer, Object.assign({ isPill: true }, drawerProps, { children: (0, jsx_runtime_1.jsx)(Drawer_1.DrawerContent, { panelContent: drawerContent, children: (0, jsx_runtime_1.jsx)(Drawer_1.DrawerContentBody, { children: compassContent }) }) }))) : (compassContent) })));
};
exports.Compass = Compass;
exports.Compass.displayName = 'Compass';
//# sourceMappingURL=Compass.js.map