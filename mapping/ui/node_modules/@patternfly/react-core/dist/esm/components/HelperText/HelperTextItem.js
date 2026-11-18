import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/HelperText/helper-text.mjs';
import { css } from '@patternfly/react-styles';
import RhUiMinusIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-minus-icon';
import RhUiWarningFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-warning-fill-icon';
import RhUiCheckCircleFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-check-circle-fill-icon';
import RhUiErrorFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-error-fill-icon';
export var HelperTextItemVariant;
(function (HelperTextItemVariant) {
    HelperTextItemVariant["default"] = "default";
    HelperTextItemVariant["warning"] = "warning";
    HelperTextItemVariant["error"] = "error";
    HelperTextItemVariant["success"] = "success";
})(HelperTextItemVariant || (HelperTextItemVariant = {}));
const defaultVariantIcons = {
    indeterminate: _jsx(RhUiMinusIcon, {}),
    warning: _jsx(RhUiWarningFillIcon, {}),
    success: _jsx(RhUiCheckCircleFillIcon, {}),
    error: _jsx(RhUiErrorFillIcon, {})
};
export const HelperTextItem = (_a) => {
    var { children, className, component = 'div', variant = 'default', icon, id, screenReaderText = `${variant} status` } = _a, props = __rest(_a, ["children", "className", "component", "variant", "icon", "id", "screenReaderText"]);
    const Component = component;
    const isNotDefaultVariant = variant !== 'default';
    const defaultIcon = isNotDefaultVariant && defaultVariantIcons[variant];
    const shouldRenderSRText = isNotDefaultVariant && screenReaderText && screenReaderText !== '';
    return (_jsxs(Component, Object.assign({ className: css(styles.helperTextItem, isNotDefaultVariant && styles.modifiers[variant], className), id: id }, props, { children: [(defaultIcon || icon) && _jsx("span", { className: css(styles.helperTextItemIcon), children: icon || defaultIcon }), _jsxs("span", { className: css(styles.helperTextItemText), children: [children, shouldRenderSRText && _jsxs("span", { className: "pf-v6-screen-reader", children: [": ", screenReaderText, ";"] })] })] })));
};
HelperTextItem.displayName = 'HelperTextItem';
//# sourceMappingURL=HelperTextItem.js.map