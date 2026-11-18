/// <reference types="react" />
export interface SSRSafeIdsProps {
    /** Prefix for the generated component ID */
    prefix: string;
    /** OUIA component type. If provided, a stable OUIA ID will be generated. */
    ouiaComponentType?: string;
    /** Children render function receiving (generatedId, generatedOuiaId?) */
    children: (id: string, ouiaId?: string) => React.ReactNode;
}
/**
 * Provides SSR/Strict-Mode-safe IDs for class components via render props.
 * Uses useSSRSafeId() (React.useId) for both component ID and OUIA ID,
 * ensuring consistent values between server and client rendering.
 */
export declare const SSRSafeIds: {
    ({ prefix, ouiaComponentType, children }: SSRSafeIdsProps): import("react").ReactElement<any, string | import("react").JSXElementConstructor<any>>;
    displayName: string;
};
//# sourceMappingURL=SSRSafeIds.d.ts.map