"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.AlertIcon = exports.variantIcons = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_styles_1 = require("@patternfly/react-styles");
const alert_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Alert/alert"));
const rh_ui_check_circle_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-check-circle-fill-icon'));
const rh_ui_error_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-error-fill-icon'));
const rh_ui_warning_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-warning-fill-icon'));
const rh_ui_information_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-information-fill-icon'));
const rh_ui_notification_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-notification-fill-icon'));
exports.variantIcons = {
    success: rh_ui_check_circle_fill_icon_1.default,
    danger: rh_ui_error_fill_icon_1.default,
    warning: rh_ui_warning_fill_icon_1.default,
    info: rh_ui_information_fill_icon_1.default,
    custom: rh_ui_notification_fill_icon_1.default
};
const AlertIcon = (_a) => {
    var { variant, customIcon, className = '' } = _a, props = tslib_1.__rest(_a, ["variant", "customIcon", "className"]);
    const Icon = exports.variantIcons[variant];
    return Icon ? ((0, jsx_runtime_1.jsx)("div", Object.assign({}, props, { className: (0, react_styles_1.css)(alert_1.default.alertIcon, className), children: customIcon || (0, jsx_runtime_1.jsx)(Icon, {}) }))) : null;
};
exports.AlertIcon = AlertIcon;
//# sourceMappingURL=AlertIcon.js.map