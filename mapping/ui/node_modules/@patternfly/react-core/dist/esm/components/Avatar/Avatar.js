import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/Avatar/avatar.mjs';
import { css } from '@patternfly/react-styles';
export const Avatar = (_a) => {
    var { children, className, src = '', alt, isBordered, size, color, initials } = _a, props = __rest(_a, ["children", "className", "src", "alt", "isBordered", "size", "color", "initials"]);
    const avatarClasses = css(styles.avatar, styles.modifiers[size], isBordered && styles.modifiers.bordered, className);
    const isAltEmptyString = alt === '';
    if (initials || children) {
        return (_jsxs("div", Object.assign({ className: css(avatarClasses, color && styles.modifiers.colorful, color && styles.modifiers[color]), role: "img", "aria-hidden": isAltEmptyString || undefined }, (!isAltEmptyString && { 'aria-label': alt }), props, { children: [initials && (_jsx("span", { className: "pf-v6-c-avatar__initials", "aria-hidden": "true", children: initials })), children && children] })));
    }
    return _jsx("img", Object.assign({ src: src, alt: alt, className: avatarClasses }, props));
};
Avatar.displayName = 'Avatar';
//# sourceMappingURL=Avatar.js.map