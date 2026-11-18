import { useId, useState } from 'react';
const reactUseId = typeof useId === 'function' ? useId : undefined;
let counter = 0;
/**
 * SSR-safe ID generation hook. Uses React.useId() when available (React 18+)
 * for guaranteed SSR/CSR match. Falls back to a counter-based approach for React 17.
 */
export const useSSRSafeId = reactUseId
    ? function useSSRSafeId(prefix = 'pf-') {
        return `${prefix}${reactUseId()}`;
    }
    : function useSSRSafeId(prefix = 'pf-') {
        const [id] = useState(() => `${prefix}${++counter}`);
        return id;
    };
//# sourceMappingURL=useSSRSafeId.js.map