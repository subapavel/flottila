"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Drawer = exports.DrawerContext = exports.DrawerColorVariant = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const drawer_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Drawer/drawer"));
const react_styles_1 = require("@patternfly/react-styles");
const helpers_1 = require("../../helpers");
var DrawerColorVariant;
(function (DrawerColorVariant) {
    DrawerColorVariant["default"] = "default";
    DrawerColorVariant["secondary"] = "secondary";
    /**
     * @deprecated `DrawerColorVariant.noBackground` is deprecated. Use the `isPlain` prop on `DrawerPanelContent` and the `DrawerSection`instead.
     */
    DrawerColorVariant["noBackground"] = "no-background";
})(DrawerColorVariant || (exports.DrawerColorVariant = DrawerColorVariant = {}));
exports.DrawerContext = (0, react_1.createContext)({
    isExpanded: false,
    isStatic: false,
    onExpand: () => { },
    position: 'end',
    drawerRef: null,
    drawerContentRef: null,
    isInline: false,
    isViewport: false
});
const Drawer = (_a) => {
    var { className = '', children, isExpanded = false, isInline = false, isPill = false, isViewport = false, isStatic = false, position = 'end', onExpand = () => { }, ouiaId, ouiaSafe = true } = _a, props = tslib_1.__rest(_a, ["className", "children", "isExpanded", "isInline", "isPill", "isViewport", "isStatic", "position", "onExpand", "ouiaId", "ouiaSafe"]);
    const ouiaProps = (0, helpers_1.useOUIAProps)(exports.Drawer.displayName, ouiaId, ouiaSafe);
    const drawerRef = (0, react_1.useRef)(undefined);
    const drawerContentRef = (0, react_1.useRef)(undefined);
    return ((0, jsx_runtime_1.jsx)(exports.DrawerContext.Provider, { value: { isExpanded, isStatic, onExpand, position, drawerRef, drawerContentRef, isInline, isViewport }, children: (0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(drawer_1.default.drawer, isExpanded && drawer_1.default.modifiers.expanded, isInline && drawer_1.default.modifiers.inline, isPill && drawer_1.default.modifiers.pill, isViewport && drawer_1.default.modifiers.viewport, isStatic && drawer_1.default.modifiers.static, (position === 'left' || position === 'start') && drawer_1.default.modifiers.panelLeft, position === 'bottom' && drawer_1.default.modifiers.panelBottom, className), ref: drawerRef }, props, ouiaProps, { children: children })) }));
};
exports.Drawer = Drawer;
exports.Drawer.displayName = 'Drawer';
//# sourceMappingURL=Drawer.js.map