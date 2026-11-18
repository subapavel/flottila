import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs, Fragment as _Fragment } from "react/jsx-runtime";
import { Component } from 'react';
import styles from '@patternfly/react-styles/css/components/Check/check.mjs';
import { css } from '@patternfly/react-styles';
import { getOUIAProps } from '../../helpers';
import { SSRSafeIds } from '../../helpers/SSRSafeIds/SSRSafeIds';
import { ASTERISK } from '../../helpers/htmlConstants';
// tslint:disable-next-line:no-empty
const defaultOnChange = () => { };
class Checkbox extends Component {
    constructor() {
        super(...arguments);
        this.handleChange = (event) => {
            this.props.onChange(event, event.currentTarget.checked);
        };
    }
    render() {
        const _a = this.props, { 'aria-label': ariaLabel, 'aria-describedby': ariaDescribedBy, className, inputClassName, onChange, isLabelWrapped, labelPosition = 'end', isValid, isDisabled, isRequired, isChecked, label, checked, defaultChecked, description, body, ouiaId, ouiaSafe, component } = _a, props = __rest(_a, ['aria-label', 'aria-describedby', "className", "inputClassName", "onChange", "isLabelWrapped", "labelPosition", "isValid", "isDisabled", "isRequired", "isChecked", "label", "checked", "defaultChecked", "description", "body", "ouiaId", "ouiaSafe", "component"]);
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
        return (_jsx(SSRSafeIds, { prefix: "pf-checkbox-description-", ouiaComponentType: "Checkbox", children: (descriptionId, generatedOuiaId) => {
                let ariaDescribedByValue;
                if (ariaDescribedBy !== undefined) {
                    ariaDescribedByValue = ariaDescribedBy === '' ? undefined : ariaDescribedBy;
                }
                else if (description) {
                    ariaDescribedByValue = descriptionId;
                }
                const inputRendered = (_jsx("input", Object.assign({}, props, { className: css(styles.checkInput, inputClassName), type: "checkbox", onChange: this.handleChange, "aria-invalid": !isValid, "aria-label": ariaLabel, "aria-describedby": ariaDescribedByValue, disabled: isDisabled, required: isRequired, ref: (elem) => {
                        elem && (elem.indeterminate = isChecked === null);
                    } }, checkedProps, getOUIAProps(Checkbox.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId, ouiaSafe))));
                const wrapWithLabel = (isLabelWrapped && !component) || component === 'label';
                const Label = wrapWithLabel ? 'span' : 'label';
                const labelRendered = label ? (_jsxs(Label, { className: css(styles.checkLabel, isDisabled && styles.modifiers.disabled), htmlFor: !wrapWithLabel ? props.id : undefined, children: [label, isRequired && (_jsx("span", { className: css(styles.checkLabelRequired), "aria-hidden": "true", children: ASTERISK }))] })) : null;
                const WrapperComponent = component !== null && component !== void 0 ? component : (wrapWithLabel ? 'label' : 'div');
                checkedProps.checked = checkedProps.checked === null ? false : checkedProps.checked;
                return (_jsxs(WrapperComponent, { className: css(styles.check, !label && styles.modifiers.standalone, className), htmlFor: wrapWithLabel ? props.id : undefined, children: [labelPosition === 'start' ? (_jsxs(_Fragment, { children: [labelRendered, inputRendered] })) : (_jsxs(_Fragment, { children: [inputRendered, labelRendered] })), description && (_jsx("span", { id: descriptionId, className: css(styles.checkDescription), children: description })), body && _jsx("span", { className: css(styles.checkBody), children: body })] }));
            } }));
    }
}
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
export { Checkbox };
//# sourceMappingURL=Checkbox.js.map