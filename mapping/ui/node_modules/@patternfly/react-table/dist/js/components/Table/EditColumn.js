"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.EditColumn = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const Button_1 = require('@patternfly/react-core/dist/js/components/Button');
const rh_ui_edit_fill_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-edit-fill-icon'));
const rh_microns_checkmark_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-microns-checkmark-icon'));
const rh_microns_close_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-microns-close-icon'));
const inline_edit_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/InlineEdit/inline-edit"));
const react_styles_1 = require("@patternfly/react-styles");
const EditColumn = (_a) => {
    var { onClick = null, 
    /* eslint-disable @typescript-eslint/no-unused-vars */
    className = '', editing, valid, 
    /* eslint-enable @typescript-eslint/no-unused-vars */
    saveAriaLabel, cancelAriaLabel, editAriaLabel } = _a, props = tslib_1.__rest(_a, ["onClick", "className", "editing", "valid", "saveAriaLabel", "cancelAriaLabel", "editAriaLabel"]);
    return ((0, jsx_runtime_1.jsxs)(react_1.Fragment, { children: [(0, jsx_runtime_1.jsxs)("div", { className: (0, react_styles_1.css)(inline_edit_1.default.inlineEditGroup, inline_edit_1.default.modifiers.iconGroup, 'pf-m-action-group'), children: [(0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(inline_edit_1.default.inlineEditAction), children: (0, jsx_runtime_1.jsx)(Button_1.Button, Object.assign({ "aria-label": saveAriaLabel }, props, { onClick: (e) => onClick(e, 'save'), variant: "plain", icon: (0, jsx_runtime_1.jsx)(rh_microns_checkmark_icon_1.default, {}) })) }), (0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(inline_edit_1.default.inlineEditAction), children: (0, jsx_runtime_1.jsx)(Button_1.Button, Object.assign({ "aria-label": cancelAriaLabel }, props, { onClick: (e) => onClick(e, 'cancel'), variant: "plain", icon: (0, jsx_runtime_1.jsx)(rh_microns_close_icon_1.default, {}) })) })] }), (0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(inline_edit_1.default.inlineEditAction, inline_edit_1.default.modifiers.enableEditable), children: (0, jsx_runtime_1.jsx)(Button_1.Button, Object.assign({ "aria-label": editAriaLabel }, props, { onClick: (e) => onClick(e, 'edit'), variant: "plain", icon: (0, jsx_runtime_1.jsx)(rh_ui_edit_fill_icon_1.default, {}) })) })] }));
};
exports.EditColumn = EditColumn;
exports.EditColumn.displayName = 'EditColumn';
//# sourceMappingURL=EditColumn.js.map