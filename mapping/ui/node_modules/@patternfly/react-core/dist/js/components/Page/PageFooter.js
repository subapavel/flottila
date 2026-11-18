"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PageFooter = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const page_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Page/page"));
const react_styles_1 = require("@patternfly/react-styles");
const PageFooter = (_a) => {
    var { className, children, component = 'footer' } = _a, props = tslib_1.__rest(_a, ["className", "children", "component"]);
    const Component = component;
    return ((0, jsx_runtime_1.jsx)(Component, Object.assign({}, props, { className: (0, react_styles_1.css)(page_1.default.pageFooter, className), children: children })));
};
exports.PageFooter = PageFooter;
exports.PageFooter.displayName = 'PageFooter';
//# sourceMappingURL=PageFooter.js.map