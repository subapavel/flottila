"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MenuToggle = exports.MenuToggleSize = exports.MenuToggleStatus = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const menu_toggle_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/MenuToggle/menu-toggle"));
const react_styles_1 = require("@patternfly/react-styles");
const rh_microns_caret_down_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-microns-caret-down-fill-icon'));
const rh_ui_settings_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-settings-fill-icon'));
const rh_ui_check_circle_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-check-circle-fill-icon'));
const rh_ui_error_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-error-fill-icon'));
const rh_ui_warning_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-warning-fill-icon'));
const helpers_1 = require("../../helpers");
const SSRSafeIds_1 = require("../../helpers/SSRSafeIds/SSRSafeIds");
var MenuToggleStatus;
(function (MenuToggleStatus) {
    MenuToggleStatus["success"] = "success";
    MenuToggleStatus["danger"] = "danger";
    MenuToggleStatus["warning"] = "warning";
})(MenuToggleStatus || (exports.MenuToggleStatus = MenuToggleStatus = {}));
var MenuToggleSize;
(function (MenuToggleSize) {
    MenuToggleSize["default"] = "default";
    MenuToggleSize["sm"] = "sm";
})(MenuToggleSize || (exports.MenuToggleSize = MenuToggleSize = {}));
class MenuToggleBase extends react_1.Component {
    constructor() {
        super(...arguments);
        this.displayName = 'MenuToggleBase';
    }
    render() {
        const _a = this.props, { children, className, icon, badge, isExpanded, isDisabled, isFullHeight, isFullWidth, isInForm, isPlaceholder, isCircle, isSettings, isDocked, splitButtonItems, variant, status, statusIcon, innerRef, onClick, 'aria-label': ariaLabel, 'aria-haspopup': ariaHasPopup, ouiaId, ouiaSafe, size } = _a, otherProps = tslib_1.__rest(_a, ["children", "className", "icon", "badge", "isExpanded", "isDisabled", "isFullHeight", "isFullWidth", "isInForm", "isPlaceholder", "isCircle", "isSettings", "isDocked", "splitButtonItems", "variant", "status", "statusIcon", "innerRef", "onClick", 'aria-label', 'aria-haspopup', "ouiaId", "ouiaSafe", "size"]);
        const isPlain = variant === 'plain';
        const isPlainText = variant === 'plainText';
        const isTypeahead = variant === 'typeahead';
        return ((0, jsx_runtime_1.jsx)(SSRSafeIds_1.SSRSafeIds, { prefix: "pf-", ouiaComponentType: `MenuToggle${variant ? `-${variant}` : ''}`, children: (_, generatedOuiaId) => {
                const ouiaProps = (0, helpers_1.getOUIAProps)(exports.MenuToggle.displayName, ouiaId !== null && ouiaId !== void 0 ? ouiaId : generatedOuiaId, ouiaSafe);
                let _statusIcon = statusIcon;
                if (!statusIcon) {
                    switch (status) {
                        case MenuToggleStatus.success:
                            _statusIcon = (0, jsx_runtime_1.jsx)(rh_ui_check_circle_fill_icon_1.default, {});
                            break;
                        case MenuToggleStatus.warning:
                            _statusIcon = (0, jsx_runtime_1.jsx)(rh_ui_warning_fill_icon_1.default, {});
                            break;
                        case MenuToggleStatus.danger:
                            _statusIcon = (0, jsx_runtime_1.jsx)(rh_ui_error_fill_icon_1.default, {});
                            break;
                    }
                }
                const toggleControls = ((0, jsx_runtime_1.jsxs)("span", { className: (0, react_styles_1.css)(menu_toggle_1.default.menuToggleControls), children: [status !== undefined && (0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(menu_toggle_1.default.menuToggleStatusIcon), children: _statusIcon }), (0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(menu_toggle_1.default.menuToggleToggleIcon), children: (0, jsx_runtime_1.jsx)(rh_microns_caret_down_fill_icon_1.default, {}) })] }));
                const content = ((0, jsx_runtime_1.jsxs)(jsx_runtime_1.Fragment, { children: [(icon || isSettings) && ((0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(menu_toggle_1.default.menuToggleIcon), children: isSettings ? (0, jsx_runtime_1.jsx)(rh_ui_settings_fill_icon_1.default, {}) : icon })), isTypeahead ? children : children && (0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(menu_toggle_1.default.menuToggleText), children: children }), (0, react_1.isValidElement)(badge) && (0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(menu_toggle_1.default.menuToggleCount), children: badge }), isTypeahead ? ((0, jsx_runtime_1.jsx)("button", Object.assign({ type: "button", className: (0, react_styles_1.css)(menu_toggle_1.default.menuToggleButton), "aria-expanded": isExpanded, "aria-haspopup": ariaHasPopup, onClick: onClick, "aria-label": ariaLabel || 'Menu toggle', tabIndex: -1 }, ouiaProps, { children: toggleControls }))) : (!isPlain && toggleControls)] }));
                const commonStyles = (0, react_styles_1.css)(menu_toggle_1.default.menuToggle, isExpanded && menu_toggle_1.default.modifiers.expanded, variant === 'primary' && menu_toggle_1.default.modifiers.primary, variant === 'secondary' && menu_toggle_1.default.modifiers.secondary, status && menu_toggle_1.default.modifiers[status], (isPlain || isPlainText) && menu_toggle_1.default.modifiers.plain, isPlainText && menu_toggle_1.default.modifiers.text, isFullHeight && menu_toggle_1.default.modifiers.fullHeight, isFullWidth && menu_toggle_1.default.modifiers.fullWidth, isInForm && menu_toggle_1.default.modifiers.form, isDisabled && menu_toggle_1.default.modifiers.disabled, isPlaceholder && menu_toggle_1.default.modifiers.placeholder, isSettings && menu_toggle_1.default.modifiers.settings, isDocked && menu_toggle_1.default.modifiers.docked, size === MenuToggleSize.sm && menu_toggle_1.default.modifiers.small, className);
                const componentProps = Object.assign(Object.assign({ children: content }, (isDisabled && { disabled: true })), otherProps);
                if (isTypeahead) {
                    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ ref: innerRef, className: (0, react_styles_1.css)(commonStyles, menu_toggle_1.default.modifiers.typeahead) }, componentProps)));
                }
                if (splitButtonItems) {
                    return ((0, jsx_runtime_1.jsxs)("div", { ref: innerRef, className: (0, react_styles_1.css)(commonStyles, menu_toggle_1.default.modifiers.splitButton), children: [splitButtonItems, (0, jsx_runtime_1.jsxs)("button", Object.assign({ className: (0, react_styles_1.css)(menu_toggle_1.default.menuToggleButton, children && menu_toggle_1.default.modifiers.text), type: "button", "aria-expanded": isExpanded, "aria-haspopup": ariaHasPopup, "aria-label": ariaLabel, disabled: isDisabled, onClick: onClick }, otherProps, ouiaProps, { children: [children && (0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(menu_toggle_1.default.menuToggleText), children: children }), toggleControls] }))] }));
                }
                return ((0, jsx_runtime_1.jsx)("button", Object.assign({ className: (0, react_styles_1.css)(commonStyles, isCircle && isPlain && menu_toggle_1.default.modifiers.circle), type: "button", "aria-label": ariaLabel, "aria-haspopup": ariaHasPopup, "aria-expanded": isExpanded, ref: innerRef, disabled: isDisabled, onClick: onClick }, componentProps, ouiaProps)));
            } }));
    }
}
MenuToggleBase.defaultProps = {
    className: '',
    isExpanded: false,
    isDisabled: false,
    isFullWidth: false,
    isFullHeight: false,
    isInForm: false,
    isPlaceholder: false,
    isCircle: false,
    isDocked: false,
    size: 'default',
    ouiaSafe: true,
    'aria-haspopup': 'menu'
};
exports.MenuToggle = (0, react_1.forwardRef)((props, ref) => ((0, jsx_runtime_1.jsx)(MenuToggleBase, Object.assign({ innerRef: ref }, props))));
exports.MenuToggle.displayName = 'MenuToggle';
//# sourceMappingURL=MenuToggle.js.map