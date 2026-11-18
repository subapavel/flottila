import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/FormControl/form-control.mjs';
import { css } from '@patternfly/react-styles';
import RhUiCheckCircleFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-check-circle-fill-icon';
import RhUiErrorFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-error-fill-icon';
import RhUiWarningFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-warning-fill-icon';
export const statusIcons = {
    success: RhUiCheckCircleFillIcon,
    error: RhUiErrorFillIcon,
    warning: RhUiWarningFillIcon
};
export const FormControlIcon = (_a) => {
    var { status, customIcon, className } = _a, props = __rest(_a, ["status", "customIcon", "className"]);
    const StatusIcon = status && statusIcons[status];
    return (_jsx("span", Object.assign({ className: css(styles.formControlIcon, status && styles.modifiers.status, className) }, props, { children: customIcon || _jsx(StatusIcon, {}) })));
};
//# sourceMappingURL=FormControlIcon.js.map