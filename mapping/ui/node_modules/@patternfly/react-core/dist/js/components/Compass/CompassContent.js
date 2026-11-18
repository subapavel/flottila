"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CompassContent = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const Drawer_1 = require("../Drawer");
const compass_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Compass/compass"));
const react_styles_1 = require("@patternfly/react-styles");
const CompassContent = (_a) => {
    var { children, className, drawerProps, drawerContent } = _a, props = tslib_1.__rest(_a, ["children", "className", "drawerProps", "drawerContent"]);
    const hasDrawer = drawerContent !== undefined;
    const compassContent = ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(compass_1.default.compassContent, className) }, props, { children: children })));
    if (hasDrawer) {
        return ((0, jsx_runtime_1.jsx)(Drawer_1.Drawer, Object.assign({ isPill: true }, drawerProps, { children: (0, jsx_runtime_1.jsx)(Drawer_1.DrawerContent, { panelContent: drawerContent, children: compassContent }) })));
    }
    return compassContent;
};
exports.CompassContent = CompassContent;
exports.CompassContent.displayName = 'CompassContent';
//# sourceMappingURL=CompassContent.js.map