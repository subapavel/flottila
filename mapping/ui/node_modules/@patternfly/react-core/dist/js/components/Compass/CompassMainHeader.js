"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CompassMainHeader = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const CompassMainHeaderContent_1 = require("./CompassMainHeaderContent");
const CompassMainHeaderTitle_1 = require("./CompassMainHeaderTitle");
const CompassMainHeaderToolbar_1 = require("./CompassMainHeaderToolbar");
const Panel_1 = require("../Panel");
const compass_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Compass/compass"));
const react_styles_1 = require("@patternfly/react-styles");
const CompassMainHeader = (_a) => {
    var { className, title, toolbar, children, panelProps } = _a, props = tslib_1.__rest(_a, ["className", "title", "toolbar", "children", "panelProps"]);
    const _content = title !== undefined || toolbar !== undefined ? ((0, jsx_runtime_1.jsx)(Panel_1.Panel, Object.assign({}, panelProps, { children: (0, jsx_runtime_1.jsx)(Panel_1.PanelMain, { children: (0, jsx_runtime_1.jsx)(Panel_1.PanelMainBody, { children: (0, jsx_runtime_1.jsxs)(CompassMainHeaderContent_1.CompassMainHeaderContent, { children: [title && (0, jsx_runtime_1.jsx)(CompassMainHeaderTitle_1.CompassMainHeaderTitle, { children: title }), toolbar && (0, jsx_runtime_1.jsx)(CompassMainHeaderToolbar_1.CompassMainHeaderToolbar, { children: toolbar })] }) }) }) }))) : (children);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(`${compass_1.default.compass}__main-header`, className) }, props, { children: _content })));
};
exports.CompassMainHeader = CompassMainHeader;
exports.CompassMainHeader.displayName = 'CompassMainHeader';
//# sourceMappingURL=CompassMainHeader.js.map