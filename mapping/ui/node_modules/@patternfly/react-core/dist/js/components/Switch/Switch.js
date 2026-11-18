"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Switch = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const switch_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Switch/switch"));
const react_styles_1 = require("@patternfly/react-styles");
const rh_microns_checkmark_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-microns-checkmark-icon'));
const helpers_1 = require("../../helpers");
const SSRSafeIds_1 = require("../../helpers/SSRSafeIds/SSRSafeIds");
class Switch extends react_1.Component {
    constructor(props) {
        super(props);
        if (!props.label && !props['aria-label'] && !props['aria-labelledby']) {
            // eslint-disable-next-line no-console
            console.error('Switch: Switch requires at least one of label, aria-labelledby, or aria-label props to be specified');
        }
    }
    render() {
        const _a = this.props, { id: idProp, className, label, isChecked, defaultChecked, hasCheckIcon, isDisabled, onChange, isReversed, ouiaId, ouiaSafe, 'aria-label': ariaLabel, 'aria-labelledby': ariaLabelledBy } = _a, props = tslib_1.__rest(_a, ["id", "className", "label", "isChecked", "defaultChecked", "hasCheckIcon", "isDisabled", "onChange", "isReversed", "ouiaId", "ouiaSafe", 'aria-label', 'aria-labelledby']);
        return ((0, jsx_runtime_1.jsx)(SSRSafeIds_1.SSRSafeIds, { prefix: "pf-switch-", ouiaComponentType: "Switch", children: (generatedId, generatedOuiaId) => {
                const id = idProp || generatedId;
                const hasAccessibleName = label || ariaLabel || ariaLabelledBy;
                const isAriaLabelledBy = hasAccessibleName && (!ariaLabel || ariaLabelledBy);
                const useDefaultAriaLabelledBy = !ariaLabelledBy && !ariaLabel;
                const ariaLabelledByIds = ariaLabelledBy !== null && ariaLabelledBy !== void 0 ? ariaLabelledBy : `${id}-label`;
                return ((0, jsx_runtime_1.jsxs)("label", Object.assign({ className: (0, react_styles_1.css)(switch_1.default.switch, isReversed && switch_1.default.modifiers.reverse, className), htmlFor: id }, (0, helpers_1.getOUIAProps)(Switch.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId, ouiaSafe), { children: [(0, jsx_runtime_1.jsx)("input", Object.assign({ id: id, className: (0, react_styles_1.css)(switch_1.default.switchInput), type: "checkbox", role: "switch", onChange: (event) => onChange(event, event.target.checked) }, (defaultChecked !== undefined ? { defaultChecked } : { checked: isChecked }), { disabled: isDisabled, "aria-labelledby": isAriaLabelledBy ? ariaLabelledByIds : null, "aria-label": ariaLabel }, props)), label !== undefined ? ((0, jsx_runtime_1.jsxs)(react_1.Fragment, { children: [(0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(switch_1.default.switchToggle), children: hasCheckIcon && ((0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(switch_1.default.switchToggleIcon), children: (0, jsx_runtime_1.jsx)(rh_microns_checkmark_icon_1.default, {}) })) }), (0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(switch_1.default.switchLabel), id: isAriaLabelledBy && useDefaultAriaLabelledBy ? `${id}-label` : null, "aria-hidden": "true", children: label })] })) : ((0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(switch_1.default.switchToggle), children: (0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(switch_1.default.switchToggleIcon), children: (0, jsx_runtime_1.jsx)(rh_microns_checkmark_icon_1.default, {}) }) }))] })));
            } }));
    }
}
exports.Switch = Switch;
Switch.displayName = 'Switch';
Switch.defaultProps = {
    isChecked: true,
    isDisabled: false,
    isReversed: false,
    'aria-label': undefined,
    'aria-labelledby': undefined,
    onChange: () => undefined
};
//# sourceMappingURL=Switch.js.map