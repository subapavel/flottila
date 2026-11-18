import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { useEffect, useState } from 'react';
import { Button, ButtonVariant } from '../Button';
import RhUiAttentionBellFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-attention-bell-fill-icon';
import RhUiNotificationFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-notification-fill-icon';
import styles from '@patternfly/react-styles/css/components/Button/button.mjs';
import { css } from '@patternfly/react-styles';
export var NotificationBadgeVariant;
(function (NotificationBadgeVariant) {
    NotificationBadgeVariant["read"] = "read";
    NotificationBadgeVariant["unread"] = "unread";
    NotificationBadgeVariant["attention"] = "attention";
    NotificationBadgeVariant["plain"] = "plain";
})(NotificationBadgeVariant || (NotificationBadgeVariant = {}));
export const NotificationBadge = (_a) => {
    var { children, variant = NotificationBadgeVariant.read, count = 0, attentionIcon = _jsx(RhUiAttentionBellFillIcon, {}), icon = _jsx(RhUiNotificationFillIcon, {}), className, isExpanded = false, shouldNotify = false, onAnimationEnd } = _a, props = __rest(_a, ["children", "variant", "count", "attentionIcon", "icon", "className", "isExpanded", "shouldNotify", "onAnimationEnd"]);
    const [isAnimating, setIsAnimating] = useState(shouldNotify);
    const hasCount = count > 0;
    const hasChildren = children !== undefined;
    const isAttention = variant === NotificationBadgeVariant.attention;
    const isPlain = variant === NotificationBadgeVariant.plain;
    const _buttonState = isPlain ? undefined : variant;
    const notificationIcon = isAttention ? attentionIcon : icon;
    let notificationContent = null;
    if (hasCount) {
        notificationContent = count;
    }
    else if (hasChildren) {
        notificationContent = children;
    }
    const buttonClassName = isAnimating ? css(className, styles.modifiers.notify) : className;
    useEffect(() => {
        setIsAnimating(shouldNotify);
    }, [shouldNotify]);
    const handleAnimationEnd = (event) => {
        onAnimationEnd === null || onAnimationEnd === void 0 ? void 0 : onAnimationEnd(event);
        setIsAnimating(false);
    };
    return (_jsx(Button, Object.assign({ variant: isPlain ? ButtonVariant.plain : ButtonVariant.stateful, className: buttonClassName, "aria-expanded": isExpanded, state: _buttonState, isClicked: isExpanded, icon: notificationIcon, onAnimationEnd: handleAnimationEnd }, props, { children: notificationContent })));
};
NotificationBadge.displayName = 'NotificationBadge';
//# sourceMappingURL=NotificationBadge.js.map