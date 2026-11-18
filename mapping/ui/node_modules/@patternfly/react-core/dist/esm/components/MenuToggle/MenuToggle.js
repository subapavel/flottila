import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs, Fragment as _Fragment } from "react/jsx-runtime";
import { Component, forwardRef, isValidElement } from 'react';
import styles from '@patternfly/react-styles/css/components/MenuToggle/menu-toggle.mjs';
import { css } from '@patternfly/react-styles';
import RhMicronsCaretDownFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-caret-down-fill-icon';
import RhUiSettingsFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-settings-fill-icon';
import RhUiCheckCircleFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-check-circle-fill-icon';
import RhUiErrorFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-error-fill-icon';
import RhUiWarningFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-warning-fill-icon';
import { getOUIAProps } from '../../helpers';
import { SSRSafeIds } from '../../helpers/SSRSafeIds/SSRSafeIds';
export var MenuToggleStatus;
(function (MenuToggleStatus) {
    MenuToggleStatus["success"] = "success";
    MenuToggleStatus["danger"] = "danger";
    MenuToggleStatus["warning"] = "warning";
})(MenuToggleStatus || (MenuToggleStatus = {}));
export var MenuToggleSize;
(function (MenuToggleSize) {
    MenuToggleSize["default"] = "default";
    MenuToggleSize["sm"] = "sm";
})(MenuToggleSize || (MenuToggleSize = {}));
class MenuToggleBase extends Component {
    constructor() {
        super(...arguments);
        this.displayName = 'MenuToggleBase';
    }
    render() {
        const _a = this.props, { children, className, icon, badge, isExpanded, isDisabled, isFullHeight, isFullWidth, isInForm, isPlaceholder, isCircle, isSettings, isDocked, splitButtonItems, variant, status, statusIcon, innerRef, onClick, 'aria-label': ariaLabel, 'aria-haspopup': ariaHasPopup, ouiaId, ouiaSafe, size } = _a, otherProps = __rest(_a, ["children", "className", "icon", "badge", "isExpanded", "isDisabled", "isFullHeight", "isFullWidth", "isInForm", "isPlaceholder", "isCircle", "isSettings", "isDocked", "splitButtonItems", "variant", "status", "statusIcon", "innerRef", "onClick", 'aria-label', 'aria-haspopup', "ouiaId", "ouiaSafe", "size"]);
        const isPlain = variant === 'plain';
        const isPlainText = variant === 'plainText';
        const isTypeahead = variant === 'typeahead';
        return (_jsx(SSRSafeIds, { prefix: "pf-", ouiaComponentType: `MenuToggle${variant ? `-${variant}` : ''}`, children: (_, generatedOuiaId) => {
                const ouiaProps = getOUIAProps(MenuToggle.displayName, ouiaId !== null && ouiaId !== void 0 ? ouiaId : generatedOuiaId, ouiaSafe);
                let _statusIcon = statusIcon;
                if (!statusIcon) {
                    switch (status) {
                        case MenuToggleStatus.success:
                            _statusIcon = _jsx(RhUiCheckCircleFillIcon, {});
                            break;
                        case MenuToggleStatus.warning:
                            _statusIcon = _jsx(RhUiWarningFillIcon, {});
                            break;
                        case MenuToggleStatus.danger:
                            _statusIcon = _jsx(RhUiErrorFillIcon, {});
                            break;
                    }
                }
                const toggleControls = (_jsxs("span", { className: css(styles.menuToggleControls), children: [status !== undefined && _jsx("span", { className: css(styles.menuToggleStatusIcon), children: _statusIcon }), _jsx("span", { className: css(styles.menuToggleToggleIcon), children: _jsx(RhMicronsCaretDownFillIcon, {}) })] }));
                const content = (_jsxs(_Fragment, { children: [(icon || isSettings) && (_jsx("span", { className: css(styles.menuToggleIcon), children: isSettings ? _jsx(RhUiSettingsFillIcon, {}) : icon })), isTypeahead ? children : children && _jsx("span", { className: css(styles.menuToggleText), children: children }), isValidElement(badge) && _jsx("span", { className: css(styles.menuToggleCount), children: badge }), isTypeahead ? (_jsx("button", Object.assign({ type: "button", className: css(styles.menuToggleButton), "aria-expanded": isExpanded, "aria-haspopup": ariaHasPopup, onClick: onClick, "aria-label": ariaLabel || 'Menu toggle', tabIndex: -1 }, ouiaProps, { children: toggleControls }))) : (!isPlain && toggleControls)] }));
                const commonStyles = css(styles.menuToggle, isExpanded && styles.modifiers.expanded, variant === 'primary' && styles.modifiers.primary, variant === 'secondary' && styles.modifiers.secondary, status && styles.modifiers[status], (isPlain || isPlainText) && styles.modifiers.plain, isPlainText && styles.modifiers.text, isFullHeight && styles.modifiers.fullHeight, isFullWidth && styles.modifiers.fullWidth, isInForm && styles.modifiers.form, isDisabled && styles.modifiers.disabled, isPlaceholder && styles.modifiers.placeholder, isSettings && styles.modifiers.settings, isDocked && styles.modifiers.docked, size === MenuToggleSize.sm && styles.modifiers.small, className);
                const componentProps = Object.assign(Object.assign({ children: content }, (isDisabled && { disabled: true })), otherProps);
                if (isTypeahead) {
                    return (_jsx("div", Object.assign({ ref: innerRef, className: css(commonStyles, styles.modifiers.typeahead) }, componentProps)));
                }
                if (splitButtonItems) {
                    return (_jsxs("div", { ref: innerRef, className: css(commonStyles, styles.modifiers.splitButton), children: [splitButtonItems, _jsxs("button", Object.assign({ className: css(styles.menuToggleButton, children && styles.modifiers.text), type: "button", "aria-expanded": isExpanded, "aria-haspopup": ariaHasPopup, "aria-label": ariaLabel, disabled: isDisabled, onClick: onClick }, otherProps, ouiaProps, { children: [children && _jsx("span", { className: css(styles.menuToggleText), children: children }), toggleControls] }))] }));
                }
                return (_jsx("button", Object.assign({ className: css(commonStyles, isCircle && isPlain && styles.modifiers.circle), type: "button", "aria-label": ariaLabel, "aria-haspopup": ariaHasPopup, "aria-expanded": isExpanded, ref: innerRef, disabled: isDisabled, onClick: onClick }, componentProps, ouiaProps)));
            } }));
    }
}
MenuToggleBase.defaultProps = {
    className: '',
    isExpanded: false,
    isDisabled: false,
    isFullWidth: false,
    isFullHeight: false,
    isInForm: false,
    isPlaceholder: false,
    isCircle: false,
    isDocked: false,
    size: 'default',
    ouiaSafe: true,
    'aria-haspopup': 'menu'
};
export const MenuToggle = forwardRef((props, ref) => (_jsx(MenuToggleBase, Object.assign({ innerRef: ref }, props))));
MenuToggle.displayName = 'MenuToggle';
//# sourceMappingURL=MenuToggle.js.map