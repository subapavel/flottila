"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CompassHeader = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const compass_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Compass/compass"));
const react_styles_1 = require("@patternfly/react-styles");
const CompassHeader = ({ logo, nav, profile }) => ((0, jsx_runtime_1.jsxs)(jsx_runtime_1.Fragment, { children: [(0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(`${compass_1.default.compass}__logo`), children: logo }), (0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(compass_1.default.compassNav), children: nav }), (0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(compass_1.default.compassProfile), children: profile })] }));
exports.CompassHeader = CompassHeader;
exports.CompassHeader.displayName = 'CompassHeader';
//# sourceMappingURL=CompassHeader.js.map