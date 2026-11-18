"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Checkbox = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const check_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Check/check"));
const react_styles_1 = require("@patternfly/react-styles");
const helpers_1 = require("../../helpers");
const SSRSafeIds_1 = require("../../helpers/SSRSafeIds/SSRSafeIds");
const htmlConstants_1 = require("../../helpers/htmlConstants");
// tslint:disable-next-line:no-empty
const defaultOnChange = () => { };
class Checkbox extends react_1.Component {
    constructor() {
        super(...arguments);
        this.handleChange = (event) => {
            this.props.onChange(event, event.currentTarget.checked);
        };
    }
    render() {
        const _a = this.props, { 'aria-label': ariaLabel, 'aria-describedby': ariaDescribedBy, className, inputClassName, onChange, isLabelWrapped, labelPosition = 'end', isValid, isDisabled, isRequired, isChecked, label, checked, defaultChecked, description, body, ouiaId, ouiaSafe, component } = _a, props = tslib_1.__rest(_a, ['aria-label', 'aria-describedby', "className", "inputClassName", "onChange", "isLabelWrapped", "labelPosition", "isValid", "isDisabled", "isRequired", "isChecked", "label", "checked", "defaultChecked", "description", "body", "ouiaId", "ouiaSafe", "component"]);
        if (!props.id) {
            // eslint-disable-next-line no-console
            console.error('Checkbox:', 'id is required to make input accessible');
        }
        const checkedProps = {};
        if ([true, false].includes(checked) || isChecked === true) {
            checkedProps.checked = checked || isChecked;
        }
        if (onChange !== defaultOnChange) {
            checkedProps.checked = isChecked;
        }
        if ([false, true].includes(defaultChecked)) {
            checkedProps.defaultChecked = defaultChecked;
        }
        return ((0, jsx_runtime_1.jsx)(SSRSafeIds_1.SSRSafeIds, { prefix: "pf-checkbox-description-", ouiaComponentType: "Checkbox", children: (descriptionId, generatedOuiaId) => {
                let ariaDescribedByValue;
                if (ariaDescribedBy !== undefined) {
                    ariaDescribedByValue = ariaDescribedBy === '' ? undefined : ariaDescribedBy;
                }
                else if (description) {
                    ariaDescribedByValue = descriptionId;
                }
                const inputRendered = ((0, jsx_runtime_1.jsx)("input", Object.assign({}, props, { className: (0, react_styles_1.css)(check_1.default.checkInput, inputClassName), type: "checkbox", onChange: this.handleChange, "aria-invalid": !isValid, "aria-label": ariaLabel, "aria-describedby": ariaDescribedByValue, disabled: isDisabled, required: isRequired, ref: (elem) => {
                        elem && (elem.indeterminate = isChecked === null);
                    } }, checkedProps, (0, helpers_1.getOUIAProps)(Checkbox.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId, ouiaSafe))));
                const wrapWithLabel = (isLabelWrapped && !component) || component === 'label';
                const Label = wrapWithLabel ? 'span' : 'label';
                const labelRendered = label ? ((0, jsx_runtime_1.jsxs)(Label, { className: (0, react_styles_1.css)(check_1.default.checkLabel, isDisabled && check_1.default.modifiers.disabled), htmlFor: !wrapWithLabel ? props.id : undefined, children: [label, isRequired && ((0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(check_1.default.checkLabelRequired), "aria-hidden": "true", children: htmlConstants_1.ASTERISK }))] })) : null;
                const WrapperComponent = component !== null && component !== void 0 ? component : (wrapWithLabel ? 'label' : 'div');
                checkedProps.checked = checkedProps.checked === null ? false : checkedProps.checked;
                return ((0, jsx_runtime_1.jsxs)(WrapperComponent, { className: (0, react_styles_1.css)(check_1.default.check, !label && check_1.default.modifiers.standalone, className), htmlFor: wrapWithLabel ? props.id : undefined, children: [labelPosition === 'start' ? ((0, jsx_runtime_1.jsxs)(jsx_runtime_1.Fragment, { children: [labelRendered, inputRendered] })) : ((0, jsx_runtime_1.jsxs)(jsx_runtime_1.Fragment, { children: [inputRendered, labelRendered] })), description && ((0, jsx_runtime_1.jsx)("span", { id: descriptionId, className: (0, react_styles_1.css)(check_1.default.checkDescription), children: description })), body && (0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(check_1.default.checkBody), children: body })] }));
            } }));
    }
}
exports.Checkbox = Checkbox;
Checkbox.displayName = 'Checkbox';
Checkbox.defaultProps = {
    className: '',
    isLabelWrapped: false,
    isValid: true,
    isDisabled: false,
    isRequired: false,
    isChecked: false,
    onChange: defaultOnChange,
    ouiaSafe: true
};
//# sourceMappingURL=Checkbox.js.map