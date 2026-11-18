import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Component, Fragment } from 'react';
import styles from '@patternfly/react-styles/css/components/Switch/switch.mjs';
import { css } from '@patternfly/react-styles';
import RhMicronsCheckmarkIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-checkmark-icon';
import { getOUIAProps } from '../../helpers';
import { SSRSafeIds } from '../../helpers/SSRSafeIds/SSRSafeIds';
class Switch extends Component {
    constructor(props) {
        super(props);
        if (!props.label && !props['aria-label'] && !props['aria-labelledby']) {
            // eslint-disable-next-line no-console
            console.error('Switch: Switch requires at least one of label, aria-labelledby, or aria-label props to be specified');
        }
    }
    render() {
        const _a = this.props, { id: idProp, className, label, isChecked, defaultChecked, hasCheckIcon, isDisabled, onChange, isReversed, ouiaId, ouiaSafe, 'aria-label': ariaLabel, 'aria-labelledby': ariaLabelledBy } = _a, props = __rest(_a, ["id", "className", "label", "isChecked", "defaultChecked", "hasCheckIcon", "isDisabled", "onChange", "isReversed", "ouiaId", "ouiaSafe", 'aria-label', 'aria-labelledby']);
        return (_jsx(SSRSafeIds, { prefix: "pf-switch-", ouiaComponentType: "Switch", children: (generatedId, generatedOuiaId) => {
                const id = idProp || generatedId;
                const hasAccessibleName = label || ariaLabel || ariaLabelledBy;
                const isAriaLabelledBy = hasAccessibleName && (!ariaLabel || ariaLabelledBy);
                const useDefaultAriaLabelledBy = !ariaLabelledBy && !ariaLabel;
                const ariaLabelledByIds = ariaLabelledBy !== null && ariaLabelledBy !== void 0 ? ariaLabelledBy : `${id}-label`;
                return (_jsxs("label", Object.assign({ className: css(styles.switch, isReversed && styles.modifiers.reverse, className), htmlFor: id }, getOUIAProps(Switch.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId, ouiaSafe), { children: [_jsx("input", Object.assign({ id: id, className: css(styles.switchInput), type: "checkbox", role: "switch", onChange: (event) => onChange(event, event.target.checked) }, (defaultChecked !== undefined ? { defaultChecked } : { checked: isChecked }), { disabled: isDisabled, "aria-labelledby": isAriaLabelledBy ? ariaLabelledByIds : null, "aria-label": ariaLabel }, props)), label !== undefined ? (_jsxs(Fragment, { children: [_jsx("span", { className: css(styles.switchToggle), children: hasCheckIcon && (_jsx("span", { className: css(styles.switchToggleIcon), children: _jsx(RhMicronsCheckmarkIcon, {}) })) }), _jsx("span", { className: css(styles.switchLabel), id: isAriaLabelledBy && useDefaultAriaLabelledBy ? `${id}-label` : null, "aria-hidden": "true", children: label })] })) : (_jsx("span", { className: css(styles.switchToggle), children: _jsx("div", { className: css(styles.switchToggleIcon), children: _jsx(RhMicronsCheckmarkIcon, {}) }) }))] })));
            } }));
    }
}
Switch.displayName = 'Switch';
Switch.defaultProps = {
    isChecked: true,
    isDisabled: false,
    isReversed: false,
    'aria-label': undefined,
    'aria-labelledby': undefined,
    onChange: () => undefined
};
export { Switch };
//# sourceMappingURL=Switch.js.map