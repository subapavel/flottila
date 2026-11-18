"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.InnerScrollContainer = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const react_styles_1 = require("@patternfly/react-styles");
const table_scrollable_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Table/table-scrollable"));
const InnerScrollContainerBase = (_a) => {
    var { children, className, innerRef } = _a, props = tslib_1.__rest(_a, ["children", "className", "innerRef"]);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ ref: innerRef, className: (0, react_styles_1.css)(className, table_scrollable_1.default.scrollInnerWrapper) }, props, { children: children })));
};
exports.InnerScrollContainer = (0, react_1.forwardRef)((props, ref) => ((0, jsx_runtime_1.jsx)(InnerScrollContainerBase, Object.assign({ innerRef: ref }, props))));
exports.InnerScrollContainer.displayName = 'InnerScrollContainer';
//# sourceMappingURL=InnerScrollContainer.js.map