import { useSSRSafeId } from '../useSSRSafeId';
const ouiaPrefix = 'OUIA-Generated-';
/**
 * Provides SSR/Strict-Mode-safe IDs for class components via render props.
 * Uses useSSRSafeId() (React.useId) for both component ID and OUIA ID,
 * ensuring consistent values between server and client rendering.
 */
export const SSRSafeIds = ({ prefix, ouiaComponentType, children }) => {
    var _a;
    const id = useSSRSafeId(prefix);
    const ouiaId = useSSRSafeId(ouiaComponentType ? `${ouiaPrefix}${ouiaComponentType}-` : 'pf-unused-');
    return (_a = children(id, ouiaComponentType ? ouiaId : undefined)) !== null && _a !== void 0 ? _a : null;
};
SSRSafeIds.displayName = 'SSRSafeIds';
//# sourceMappingURL=SSRSafeIds.js.map