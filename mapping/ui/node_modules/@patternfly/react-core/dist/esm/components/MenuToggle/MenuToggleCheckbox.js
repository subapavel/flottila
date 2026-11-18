import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Component } from 'react';
import styles from '@patternfly/react-styles/css/components/Check/check.mjs';
import { css } from '@patternfly/react-styles';
import { getOUIAProps } from '../../helpers';
import { SSRSafeIds } from '../../helpers/SSRSafeIds/SSRSafeIds';
class MenuToggleCheckbox extends Component {
    constructor() {
        super(...arguments);
        this.handleChange = (event) => {
            this.props.onChange(event.target.checked, event);
        };
        this.calculateChecked = () => {
            const { isChecked, defaultChecked } = this.props;
            if (isChecked === null) {
                // return false here and the indeterminate state will be set to true through the ref
                return false;
            }
            else if (isChecked !== undefined) {
                return isChecked;
            }
            return defaultChecked;
        };
    }
    render() {
        const _a = this.props, { className, isValid, isDisabled, isChecked, children, ouiaId, ouiaSafe, 
        /* eslint-disable @typescript-eslint/no-unused-vars */
        onChange, defaultChecked, id } = _a, props = __rest(_a, ["className", "isValid", "isDisabled", "isChecked", "children", "ouiaId", "ouiaSafe", "onChange", "defaultChecked", "id"]);
        const text = children && (_jsx("span", { className: css(styles.checkLabel, className), "aria-hidden": "true", id: id, children: children }));
        return (_jsx(SSRSafeIds, { prefix: "pf-", ouiaComponentType: MenuToggleCheckbox.displayName, children: (_, generatedOuiaId) => (_jsxs("label", { className: css(styles.check, !children && styles.modifiers.standalone, className), children: [_jsx("input", Object.assign({ className: css(styles.checkInput) }, (this.calculateChecked() !== undefined && { onChange: this.handleChange }), { name: id, type: "checkbox", ref: (elem) => {
                            elem && (elem.indeterminate = isChecked === null);
                        }, "aria-invalid": !isValid, disabled: isDisabled }, (defaultChecked !== undefined ? { defaultChecked } : { checked: this.calculateChecked() }), getOUIAProps(MenuToggleCheckbox.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId, ouiaSafe), props)), text] })) }));
    }
}
MenuToggleCheckbox.displayName = 'MenuToggleCheckbox';
MenuToggleCheckbox.defaultProps = {
    isValid: true,
    isDisabled: false,
    onChange: () => undefined
};
export { MenuToggleCheckbox };
//# sourceMappingURL=MenuToggleCheckbox.js.map