import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { css } from '@patternfly/react-styles';
import styles from '@patternfly/react-styles/css/components/Alert/alert.mjs';
import RhUiCheckCircleFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-check-circle-fill-icon';
import RhUiErrorFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-error-fill-icon';
import RhUiWarningFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-warning-fill-icon';
import RhUiInformationFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-information-fill-icon';
import RhUiNotificationFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-notification-fill-icon';
export const variantIcons = {
    success: RhUiCheckCircleFillIcon,
    danger: RhUiErrorFillIcon,
    warning: RhUiWarningFillIcon,
    info: RhUiInformationFillIcon,
    custom: RhUiNotificationFillIcon
};
export const AlertIcon = (_a) => {
    var { variant, customIcon, className = '' } = _a, props = __rest(_a, ["variant", "customIcon", "className"]);
    const Icon = variantIcons[variant];
    return Icon ? (_jsx("div", Object.assign({}, props, { className: css(styles.alertIcon, className), children: customIcon || _jsx(Icon, {}) }))) : null;
};
//# sourceMappingURL=AlertIcon.js.map