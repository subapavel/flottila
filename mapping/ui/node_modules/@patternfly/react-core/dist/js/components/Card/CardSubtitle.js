"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CardSubtitle = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_styles_1 = require("@patternfly/react-styles");
const card_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Card/card"));
const CardSubtitle = (_a) => {
    var { children = null, id = '' } = _a, props = tslib_1.__rest(_a, ["children", "id"]);
    return ((0, jsx_runtime_1.jsx)("div", Object.assign({}, props, { id: id, className: (0, react_styles_1.css)(card_1.default.cardSubtitle), children: children })));
};
exports.CardSubtitle = CardSubtitle;
exports.CardSubtitle.displayName = 'CardSubtitle';
//# sourceMappingURL=CardSubtitle.js.map