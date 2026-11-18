"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Tfoot = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const react_styles_1 = require("@patternfly/react-styles");
const table_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Table/table"));
const TfootBase = (_a) => {
    var { children, className, innerRef } = _a, props = tslib_1.__rest(_a, ["children", "className", "innerRef"]);
    return ((0, jsx_runtime_1.jsx)("tfoot", Object.assign({ className: (0, react_styles_1.css)(table_1.default.tableTfoot, className), ref: innerRef }, props, { children: children })));
};
exports.Tfoot = (0, react_1.forwardRef)((props, ref) => ((0, jsx_runtime_1.jsx)(TfootBase, Object.assign({}, props, { innerRef: ref }))));
exports.Tfoot.displayName = 'Tfoot';
//# sourceMappingURL=Tfoot.js.map