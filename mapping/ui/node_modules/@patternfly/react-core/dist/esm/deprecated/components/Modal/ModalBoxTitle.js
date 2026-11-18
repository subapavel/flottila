import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useRef, useState } from 'react';
import modalStyles from '@patternfly/react-styles/css/components/ModalBox/modal-box.mjs';
import { css } from '@patternfly/react-styles';
import { capitalize } from '../../../helpers';
import { Tooltip } from '../../../components/Tooltip';
import RhUiCheckCircleFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-check-circle-fill-icon';
import RhUiErrorFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-error-fill-icon';
import RhUiWarningFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-warning-fill-icon';
import RhUiInformationFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-information-fill-icon';
import RhUiNotificationFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-notification-fill-icon';
import { useIsomorphicLayoutEffect } from '../../../helpers';
export const isVariantIcon = (icon) => ['success', 'danger', 'warning', 'info', 'custom'].includes(icon);
export const ModalBoxTitle = (_a) => {
    var { className = '', id, title, titleIconVariant, titleLabel = '' } = _a, props = __rest(_a, ["className", "id", "title", "titleIconVariant", "titleLabel"]);
    const [hasTooltip, setHasTooltip] = useState(false);
    const h1 = useRef(null);
    const label = titleLabel || (isVariantIcon(titleIconVariant) ? `${capitalize(titleIconVariant)} alert:` : titleLabel);
    const variantIcons = {
        success: _jsx(RhUiCheckCircleFillIcon, {}),
        danger: _jsx(RhUiErrorFillIcon, {}),
        warning: _jsx(RhUiWarningFillIcon, {}),
        info: _jsx(RhUiInformationFillIcon, {}),
        custom: _jsx(RhUiNotificationFillIcon, {})
    };
    const CustomIcon = !isVariantIcon(titleIconVariant) && titleIconVariant;
    useIsomorphicLayoutEffect(() => {
        setHasTooltip(h1.current && h1.current.offsetWidth < h1.current.scrollWidth);
    }, []);
    const content = (_jsxs("h1", Object.assign({ id: id, ref: h1, className: css(modalStyles.modalBoxTitle, titleIconVariant && modalStyles.modifiers.icon, className) }, props, { children: [titleIconVariant && (_jsx("span", { className: css(modalStyles.modalBoxTitleIcon), children: isVariantIcon(titleIconVariant) ? variantIcons[titleIconVariant] : _jsx(CustomIcon, {}) })), label && _jsx("span", { className: "pf-v6-screen-reader", children: label }), _jsx("span", { className: css(modalStyles.modalBoxTitleText), children: title })] })));
    return hasTooltip ? _jsx(Tooltip, { content: title, children: content }) : content;
};
ModalBoxTitle.displayName = 'ModalBoxTitle';
//# sourceMappingURL=ModalBoxTitle.js.map