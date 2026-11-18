"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Avatar = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const avatar_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Avatar/avatar"));
const react_styles_1 = require("@patternfly/react-styles");
const Avatar = (_a) => {
    var { children, className, src = '', alt, isBordered, size, color, initials } = _a, props = tslib_1.__rest(_a, ["children", "className", "src", "alt", "isBordered", "size", "color", "initials"]);
    const avatarClasses = (0, react_styles_1.css)(avatar_1.default.avatar, avatar_1.default.modifiers[size], isBordered && avatar_1.default.modifiers.bordered, className);
    const isAltEmptyString = alt === '';
    if (initials || children) {
        return ((0, jsx_runtime_1.jsxs)("div", Object.assign({ className: (0, react_styles_1.css)(avatarClasses, color && avatar_1.default.modifiers.colorful, color && avatar_1.default.modifiers[color]), role: "img", "aria-hidden": isAltEmptyString || undefined }, (!isAltEmptyString && { 'aria-label': alt }), props, { children: [initials && ((0, jsx_runtime_1.jsx)("span", { className: "pf-v6-c-avatar__initials", "aria-hidden": "true", children: initials })), children && children] })));
    }
    return (0, jsx_runtime_1.jsx)("img", Object.assign({ src: src, alt: alt, className: avatarClasses }, props));
};
exports.Avatar = Avatar;
exports.Avatar.displayName = 'Avatar';
//# sourceMappingURL=Avatar.js.map