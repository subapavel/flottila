"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.FormControlIcon = exports.statusIcons = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const form_control_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/FormControl/form-control"));
const react_styles_1 = require("@patternfly/react-styles");
const rh_ui_check_circle_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-check-circle-fill-icon'));
const rh_ui_error_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-error-fill-icon'));
const rh_ui_warning_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-warning-fill-icon'));
exports.statusIcons = {
    success: rh_ui_check_circle_fill_icon_1.default,
    error: rh_ui_error_fill_icon_1.default,
    warning: rh_ui_warning_fill_icon_1.default
};
const FormControlIcon = (_a) => {
    var { status, customIcon, className } = _a, props = tslib_1.__rest(_a, ["status", "customIcon", "className"]);
    const StatusIcon = status && exports.statusIcons[status];
    return ((0, jsx_runtime_1.jsx)("span", Object.assign({ className: (0, react_styles_1.css)(form_control_1.default.formControlIcon, status && form_control_1.default.modifiers.status, className) }, props, { children: customIcon || (0, jsx_runtime_1.jsx)(StatusIcon, {}) })));
};
exports.FormControlIcon = FormControlIcon;
//# sourceMappingURL=FormControlIcon.js.map