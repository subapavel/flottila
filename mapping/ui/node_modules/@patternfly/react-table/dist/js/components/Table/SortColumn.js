"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SortColumn = exports.SortByDirection = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const rh_microns_arrow_up_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-microns-arrow-up-icon'));
const rh_microns_arrow_down_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-microns-arrow-down-icon'));
const rh_ui_arrow_up_down_icon_1 = tslib_1.__importDefault(require('@patternfly/react-icons/dist/js/icons/rh-ui-arrow-up-down-icon'));
const react_styles_1 = require("@patternfly/react-styles");
const table_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Table/table"));
const TableText_1 = require("./TableText");
const ActionList_1 = require('@patternfly/react-core/dist/js/components/ActionList');
const Button_1 = require('@patternfly/react-core/dist/js/components/Button');
var SortByDirection;
(function (SortByDirection) {
    SortByDirection["asc"] = "asc";
    SortByDirection["desc"] = "desc";
})(SortByDirection || (exports.SortByDirection = SortByDirection = {}));
const SortColumn = (_a) => {
    var { children = null, className = '', isSortedBy = false, onSort = null, sortDirection = '', type = 'button', tooltip, tooltipProps, tooltipHasDefaultBehavior, favoriteButtonProps } = _a, props = tslib_1.__rest(_a, ["children", "className", "isSortedBy", "onSort", "sortDirection", "type", "tooltip", "tooltipProps", "tooltipHasDefaultBehavior", "favoriteButtonProps"]);
    let SortedByIcon;
    const [focused, setFocused] = (0, react_1.useState)(false);
    if (isSortedBy) {
        SortedByIcon = sortDirection === SortByDirection.asc ? rh_microns_arrow_up_icon_1.default : rh_microns_arrow_down_icon_1.default;
    }
    else {
        SortedByIcon = rh_ui_arrow_up_down_icon_1.default;
    }
    if (favoriteButtonProps) {
        const { favorited } = favoriteButtonProps, rest = tslib_1.__rest(favoriteButtonProps, ["favorited"]);
        return ((0, jsx_runtime_1.jsxs)(ActionList_1.ActionList, { isIconList: true, children: [(0, jsx_runtime_1.jsx)(ActionList_1.ActionListItem, { children: (0, jsx_runtime_1.jsx)(Button_1.Button, Object.assign({ variant: "plain", isFavorite: true, isFavorited: favorited }, rest)) }), (0, jsx_runtime_1.jsx)(ActionList_1.ActionListItem, { children: (0, jsx_runtime_1.jsx)(Button_1.Button, Object.assign({ variant: "plain", icon: (0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(table_1.default.tableSortIndicator), children: (0, jsx_runtime_1.jsx)(SortedByIcon, {}) }), onClick: (event) => onSort && onSort(event) }, props)) })] }));
    }
    return ((0, jsx_runtime_1.jsx)("button", Object.assign({}, props, { type: type, className: (0, react_styles_1.css)(className, table_1.default.tableButton), onClick: (event) => onSort && onSort(event), onFocus: () => setFocused(true), onBlur: () => setFocused(false), children: (0, jsx_runtime_1.jsxs)("div", { className: (0, react_styles_1.css)(className, table_1.default.tableButtonContent), children: [(0, jsx_runtime_1.jsx)(TableText_1.TableText, { tooltip: tooltip, tooltipProps: tooltipProps, tooltipHasDefaultBehavior: tooltipHasDefaultBehavior, focused: focused, children: children }), (0, jsx_runtime_1.jsx)("span", { className: (0, react_styles_1.css)(table_1.default.tableSortIndicator), children: (0, jsx_runtime_1.jsx)(SortedByIcon, {}) })] }) })));
};
exports.SortColumn = SortColumn;
exports.SortColumn.displayName = 'SortColumn';
//# sourceMappingURL=SortColumn.js.map