"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SSRSafeIds = void 0;
const useSSRSafeId_1 = require("../useSSRSafeId");
const ouiaPrefix = 'OUIA-Generated-';
/**
 * Provides SSR/Strict-Mode-safe IDs for class components via render props.
 * Uses useSSRSafeId() (React.useId) for both component ID and OUIA ID,
 * ensuring consistent values between server and client rendering.
 */
const SSRSafeIds = ({ prefix, ouiaComponentType, children }) => {
    var _a;
    const id = (0, useSSRSafeId_1.useSSRSafeId)(prefix);
    const ouiaId = (0, useSSRSafeId_1.useSSRSafeId)(ouiaComponentType ? `${ouiaPrefix}${ouiaComponentType}-` : 'pf-unused-');
    return (_a = children(id, ouiaComponentType ? ouiaId : undefined)) !== null && _a !== void 0 ? _a : null;
};
exports.SSRSafeIds = SSRSafeIds;
exports.SSRSafeIds.displayName = 'SSRSafeIds';
//# sourceMappingURL=SSRSafeIds.js.map