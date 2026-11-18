"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.statusIcons = exports.globalHeightBreakpoints = exports.globalWidthBreakpoints = exports.KeyTypes = exports.ValidatedOptions = exports.KEYHANDLER_DIRECTION = exports.SIDE = void 0;
const tslib_1 = require("tslib");
const t_global_breakpoint_sm_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_sm'));
const t_global_breakpoint_md_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_md'));
const t_global_breakpoint_lg_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_lg'));
const t_global_breakpoint_xl_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_xl'));
const t_global_breakpoint_2xl_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_2xl'));
const t_global_breakpoint_height_sm_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_height_sm'));
const t_global_breakpoint_height_md_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_height_md'));
const t_global_breakpoint_height_lg_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_height_lg'));
const t_global_breakpoint_height_xl_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_height_xl'));
const t_global_breakpoint_height_2xl_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/t_global_breakpoint_height_2xl'));
const rh_ui_check_circle_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-check-circle-fill-icon'));
const rh_ui_error_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-error-fill-icon'));
const rh_ui_warning_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-warning-fill-icon'));
const rh_ui_information_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-information-fill-icon'));
const rh_ui_notification_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-notification-fill-icon'));
exports.SIDE = { RIGHT: 'right', LEFT: 'left', BOTH: 'both', NONE: 'none' };
exports.KEYHANDLER_DIRECTION = { UP: 'up', DOWN: 'down', RIGHT: 'right', LEFT: 'left' };
var ValidatedOptions;
(function (ValidatedOptions) {
    ValidatedOptions["success"] = "success";
    ValidatedOptions["error"] = "error";
    ValidatedOptions["warning"] = "warning";
    ValidatedOptions["default"] = "default";
})(ValidatedOptions || (exports.ValidatedOptions = ValidatedOptions = {}));
exports.KeyTypes = {
    Tab: 'Tab',
    Space: ' ',
    Escape: 'Escape',
    Enter: 'Enter',
    ArrowUp: 'ArrowUp',
    ArrowDown: 'ArrowDown',
    ArrowLeft: 'ArrowLeft',
    ArrowRight: 'ArrowRight'
};
exports.globalWidthBreakpoints = {
    sm: parseInt(t_global_breakpoint_sm_1.default.value) * 16,
    md: parseInt(t_global_breakpoint_md_1.default.value) * 16,
    lg: parseInt(t_global_breakpoint_lg_1.default.value) * 16,
    xl: parseInt(t_global_breakpoint_xl_1.default.value) * 16,
    '2xl': parseInt(t_global_breakpoint_2xl_1.default.value) * 16
};
exports.globalHeightBreakpoints = {
    sm: parseInt(t_global_breakpoint_height_sm_1.default.value) * 16,
    md: parseInt(t_global_breakpoint_height_md_1.default.value) * 16,
    lg: parseInt(t_global_breakpoint_height_lg_1.default.value) * 16,
    xl: parseInt(t_global_breakpoint_height_xl_1.default.value) * 16,
    '2xl': parseInt(t_global_breakpoint_height_2xl_1.default.value) * 16
};
exports.statusIcons = {
    success: rh_ui_check_circle_fill_icon_1.default,
    danger: rh_ui_error_fill_icon_1.default,
    warning: rh_ui_warning_fill_icon_1.default,
    info: rh_ui_information_fill_icon_1.default,
    custom: rh_ui_notification_fill_icon_1.default
};
//# sourceMappingURL=constants.js.map