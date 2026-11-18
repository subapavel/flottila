"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ToolbarItem = exports.ToolbarItemVariant = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const toolbar_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Toolbar/toolbar"));
const react_styles_1 = require("@patternfly/react-styles");
const util_1 = require("../../helpers/util");
const Divider_1 = require("../Divider");
const PageContext_1 = require("../Page/PageContext");
const c_toolbar__item_Width_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_toolbar__item_Width'));
var ToolbarItemVariant;
(function (ToolbarItemVariant) {
    ToolbarItemVariant["separator"] = "separator";
    ToolbarItemVariant["pagination"] = "pagination";
    ToolbarItemVariant["label"] = "label";
    ToolbarItemVariant["label-group"] = "label-group";
    ToolbarItemVariant["expand-all"] = "expand-all";
})(ToolbarItemVariant || (exports.ToolbarItemVariant = ToolbarItemVariant = {}));
const ToolbarItem = (_a) => {
    var { className, variant, visibility, visibilityAtHeight, gap, columnGap, rowGap, rowWrap, flexGrow, widths, align, alignSelf, alignItems, id, children, isAllExpanded, isOverflowContainer, role, style } = _a, props = tslib_1.__rest(_a, ["className", "variant", "visibility", "visibilityAtHeight", "gap", "columnGap", "rowGap", "rowWrap", "flexGrow", "widths", "align", "alignSelf", "alignItems", "id", "children", "isAllExpanded", "isOverflowContainer", "role", "style"]);
    if (variant === ToolbarItemVariant.separator) {
        const isDividerRoleValid = role === 'separator' || role === 'presentation';
        return ((0, jsx_runtime_1.jsx)(Divider_1.Divider, Object.assign({ className: (0, react_styles_1.css)(className), orientation: { default: 'vertical' }, style: style }, props, (isDividerRoleValid && { role: role }))));
    }
    const responsiveWidths = widths ? (0, util_1.setBreakpointCssVars)(widths, c_toolbar__item_Width_1.default.name) : {};
    return ((0, jsx_runtime_1.jsx)(PageContext_1.PageContext.Consumer, { children: ({ width, getBreakpoint, height, getVerticalBreakpoint }) => ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(toolbar_1.default.toolbarItem, variant && toolbar_1.default.modifiers[(0, util_1.toCamel)(variant)], variant === ToolbarItemVariant['label-group'] && toolbar_1.default.modifiers.labelGroup, isAllExpanded && toolbar_1.default.modifiers.expanded, isOverflowContainer && toolbar_1.default.modifiers.overflowContainer, (0, util_1.formatBreakpointMods)(visibility, toolbar_1.default, '', getBreakpoint(width)), (0, util_1.formatBreakpointMods)(visibilityAtHeight, toolbar_1.default, '', getVerticalBreakpoint(height), true), (0, util_1.formatBreakpointMods)(align, toolbar_1.default, '', getBreakpoint(width)), (0, util_1.formatBreakpointMods)(gap, toolbar_1.default, '', getBreakpoint(width)), (0, util_1.formatBreakpointMods)(columnGap, toolbar_1.default, '', getBreakpoint(width)), (0, util_1.formatBreakpointMods)(rowGap, toolbar_1.default, '', getBreakpoint(width)), (0, util_1.formatBreakpointMods)(rowWrap, toolbar_1.default, '', getBreakpoint(width)), (0, util_1.formatBreakpointMods)(flexGrow, toolbar_1.default, '', getBreakpoint(width)), alignItems === 'start' && toolbar_1.default.modifiers.alignItemsStart, alignItems === 'center' && toolbar_1.default.modifiers.alignItemsCenter, alignItems === 'baseline' && toolbar_1.default.modifiers.alignItemsBaseline, alignSelf === 'start' && toolbar_1.default.modifiers.alignSelfStart, alignSelf === 'center' && toolbar_1.default.modifiers.alignSelfCenter, alignSelf === 'baseline' && toolbar_1.default.modifiers.alignSelfBaseline, className) }, (variant === 'label' && { 'aria-hidden': true }), { id: id, role: role, style: Object.assign(Object.assign({}, style), responsiveWidths) }, props, { children: children }))) }));
};
exports.ToolbarItem = ToolbarItem;
exports.ToolbarItem.displayName = 'ToolbarItem';
//# sourceMappingURL=ToolbarItem.js.map