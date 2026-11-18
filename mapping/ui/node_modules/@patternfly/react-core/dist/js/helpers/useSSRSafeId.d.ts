/**
 * SSR-safe ID generation hook. Uses React.useId() when available (React 18+)
 * for guaranteed SSR/CSR match. Falls back to a counter-based approach for React 17.
 */
export declare const useSSRSafeId: (prefix?: string) => string;
//# sourceMappingURL=useSSRSafeId.d.ts.map