"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.IS_INERT = void 0;
const react_1 = require("react");
/**
 * Before React 19, React JSX treats empty string "" as truthy for inert prop.
 * @see {@link https://stackoverflow.com/questions/72720469}
 */
exports.IS_INERT = Number(react_1.version.split('.')[0]) >= 19 ? true : '';
//# sourceMappingURL=inert.js.map