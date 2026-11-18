import { __rest } from "tslib";
import { jsx as _jsx, Fragment as _Fragment, jsxs as _jsxs } from "react/jsx-runtime";
import { Component } from 'react';
import styles from '@patternfly/react-styles/css/components/Radio/radio.mjs';
import { css } from '@patternfly/react-styles';
import { getOUIAProps } from '../../helpers';
import { SSRSafeIds } from '../../helpers/SSRSafeIds/SSRSafeIds';
class Radio extends Component {
    constructor(props) {
        super(props);
        this.handleChange = (event) => {
            this.props.onChange(event, event.currentTarget.checked);
        };
        if (!props.label && !props['aria-label']) {
            // eslint-disable-next-line no-console
            console.error('Radio:', 'Radio requires an aria-label to be specified');
        }
    }
    render() {
        const _a = this.props, { 'aria-label': ariaLabel, 'aria-describedby': ariaDescribedBy, checked, className, inputClassName, defaultChecked, isLabelWrapped, labelPosition = 'end', isChecked, isDisabled, isValid, label, 
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        onChange, description, body, ouiaId, ouiaSafe = true, component } = _a, props = __rest(_a, ['aria-label', 'aria-describedby', "checked", "className", "inputClassName", "defaultChecked", "isLabelWrapped", "labelPosition", "isChecked", "isDisabled", "isValid", "label", "onChange", "description", "body", "ouiaId", "ouiaSafe", "component"]);
        if (!props.id) {
            // eslint-disable-next-line no-console
            console.error('Radio:', 'id is required to make input accessible');
        }
        return (_jsx(SSRSafeIds, { prefix: "pf-radio-description-", ouiaComponentType: "Radio", children: (descriptionId, generatedOuiaId) => {
                let ariaDescribedByValue;
                if (ariaDescribedBy !== undefined) {
                    ariaDescribedByValue = ariaDescribedBy === '' ? undefined : ariaDescribedBy;
                }
                else if (description) {
                    ariaDescribedByValue = descriptionId;
                }
                const inputRendered = (_jsx("input", Object.assign({}, props, { className: css(styles.radioInput, inputClassName), type: "radio", onChange: this.handleChange, "aria-invalid": !isValid, "aria-describedby": ariaDescribedByValue, disabled: isDisabled, checked: checked || isChecked }, (checked === undefined && { defaultChecked }), (!label && { 'aria-label': ariaLabel }), getOUIAProps(Radio.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId, ouiaSafe))));
                const wrapWithLabel = (isLabelWrapped && !component) || component === 'label';
                const Label = wrapWithLabel ? 'span' : 'label';
                const labelRendered = label ? (_jsx(Label, { className: css(styles.radioLabel, isDisabled && styles.modifiers.disabled), htmlFor: !wrapWithLabel ? props.id : undefined, children: label })) : null;
                const WrapperComponent = component !== null && component !== void 0 ? component : (wrapWithLabel ? 'label' : 'div');
                return (_jsxs(WrapperComponent, { className: css(styles.radio, !label && styles.modifiers.standalone, className), htmlFor: wrapWithLabel ? props.id : undefined, children: [labelPosition === 'start' ? (_jsxs(_Fragment, { children: [labelRendered, inputRendered] })) : (_jsxs(_Fragment, { children: [inputRendered, labelRendered] })), description && (_jsx("span", { id: descriptionId, className: css(styles.radioDescription), children: description })), body && _jsx("span", { className: css(styles.radioBody), children: body })] }));
            } }));
    }
}
Radio.displayName = 'Radio';
Radio.defaultProps = {
    className: '',
    isDisabled: false,
    isValid: true,
    onChange: () => { }
};
export { Radio };
//# sourceMappingURL=Radio.js.map