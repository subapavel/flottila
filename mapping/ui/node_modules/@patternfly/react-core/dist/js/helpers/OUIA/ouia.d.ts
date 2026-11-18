type OuiaId = number | string;
export interface OUIAProps {
    ouiaId?: OuiaId;
    ouiaSafe?: boolean;
}
/**
 * Get props to conform to OUIA spec.
 * For functional components, use the useOUIAProps hook instead.
 *
 * @param {string} componentType OUIA component type
 * @param {number|string} id OUIA component id
 * @param {boolean} ouiaSafe false if in animation
 */
export declare function getOUIAProps(componentType: string, id: OuiaId, ouiaSafe?: boolean): {
    'data-ouia-component-type': string;
    'data-ouia-safe': boolean;
    'data-ouia-component-id': OuiaId;
};
/**
 * Hooks version of the getOUIAProps function with SSR-safe ID generation.
 * Can only be used in functional components.
 *
 * @param {string} componentType OUIA component type
 * @param {number|string} id OUIA component id
 * @param {boolean} ouiaSafe false if in animation
 * @param {string} variant Optional variant to add to the generated ID
 */
export declare const useOUIAProps: (componentType: string, id?: OuiaId, ouiaSafe?: boolean, variant?: string) => {
    'data-ouia-component-type': string;
    'data-ouia-safe': boolean;
    'data-ouia-component-id': OuiaId;
};
/**
 * Returns the provided ID or a deterministic SSR-safe generated OUIA ID.
 * Uses React.useId() under the hood for consistent server/client rendering.
 *
 * @param {string} componentType OUIA component type
 * @param {number|string} id OUIA component id
 * @param {string} variant Optional variant to add to the generated ID
 */
export declare const useOUIAId: (componentType: string, id?: OuiaId, variant?: string) => OuiaId;
/**
 * Legacy counter-based OUIA ID generator for class components that cannot use hooks.
 * Prefer useOUIAId or useOUIAProps for functional components.
 *
 * @deprecated Use useOUIAId in functional components instead.
 */
export declare function getDefaultOUIAId(componentType: string, variant?: string): string;
export {};
//# sourceMappingURL=ouia.d.ts.map