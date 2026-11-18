"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DrawerCloseButton = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const drawer_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Drawer/drawer"));
const react_styles_1 = require("@patternfly/react-styles");
const Button_1 = require("../Button");
const rh_microns_close_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-microns-close-icon'));
const DrawerCloseButton = (_a) => {
    var { className = '', onClose = () => undefined, 'aria-label': ariaLabel = 'Close drawer panel', buttonProps } = _a, props = tslib_1.__rest(_a, ["className", "onClose", 'aria-label', "buttonProps"]);
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const _b = (buttonProps !== null && buttonProps !== void 0 ? buttonProps : {}), { onClick: _onClick } = _b, restButtonProps = tslib_1.__rest(_b, ["onClick"]);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(drawer_1.default.drawerClose, className) }, props, { children: (0, jsx_runtime_1.jsx)(Button_1.Button, Object.assign({ variant: "plain", onClick: onClose, "aria-label": ariaLabel, icon: (0, jsx_runtime_1.jsx)(rh_microns_close_icon_1.default, {}) }, restButtonProps)) })));
};
exports.DrawerCloseButton = DrawerCloseButton;
exports.DrawerCloseButton.displayName = 'DrawerCloseButton';
//# sourceMappingURL=DrawerCloseButton.js.map