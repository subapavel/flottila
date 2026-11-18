"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.useSSRSafeId = void 0;
const react_1 = require("react");
const reactUseId = typeof react_1.useId === 'function' ? react_1.useId : undefined;
let counter = 0;
/**
 * SSR-safe ID generation hook. Uses React.useId() when available (React 18+)
 * for guaranteed SSR/CSR match. Falls back to a counter-based approach for React 17.
 */
exports.useSSRSafeId = reactUseId
    ? function useSSRSafeId(prefix = 'pf-') {
        return `${prefix}${reactUseId()}`;
    }
    : function useSSRSafeId(prefix = 'pf-') {
        const [id] = (0, react_1.useState)(() => `${prefix}${++counter}`);
        return id;
    };
//# sourceMappingURL=useSSRSafeId.js.map