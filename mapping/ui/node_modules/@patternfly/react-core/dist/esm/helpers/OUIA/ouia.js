import { useSSRSafeId } from '../useSSRSafeId';
const ouiaPrefix = 'OUIA-Generated-';
/**
 * Get props to conform to OUIA spec.
 * For functional components, use the useOUIAProps hook instead.
 *
 * @param {string} componentType OUIA component type
 * @param {number|string} id OUIA component id
 * @param {boolean} ouiaSafe false if in animation
 */
export function getOUIAProps(componentType, id, ouiaSafe = true) {
    return {
        'data-ouia-component-type': `PF6/${componentType}`,
        'data-ouia-safe': ouiaSafe,
        'data-ouia-component-id': id
    };
}
/**
 * Hooks version of the getOUIAProps function with SSR-safe ID generation.
 * Can only be used in functional components.
 *
 * @param {string} componentType OUIA component type
 * @param {number|string} id OUIA component id
 * @param {boolean} ouiaSafe false if in animation
 * @param {string} variant Optional variant to add to the generated ID
 */
export const useOUIAProps = (componentType, id, ouiaSafe = true, variant) => ({
    'data-ouia-component-type': `PF6/${componentType}`,
    'data-ouia-safe': ouiaSafe,
    'data-ouia-component-id': useOUIAId(componentType, id, variant)
});
/**
 * Returns the provided ID or a deterministic SSR-safe generated OUIA ID.
 * Uses React.useId() under the hood for consistent server/client rendering.
 *
 * @param {string} componentType OUIA component type
 * @param {number|string} id OUIA component id
 * @param {string} variant Optional variant to add to the generated ID
 */
export const useOUIAId = (componentType, id, variant) => {
    const generatedId = useSSRSafeId(`${ouiaPrefix}${componentType}-${variant ? `${variant}-` : ''}`);
    return id !== null && id !== void 0 ? id : generatedId;
};
let uid = 0;
/**
 * Legacy counter-based OUIA ID generator for class components that cannot use hooks.
 * Prefer useOUIAId or useOUIAProps for functional components.
 *
 * @deprecated Use useOUIAId in functional components instead.
 */
export function getDefaultOUIAId(componentType, variant) {
    return `${ouiaPrefix}${componentType}-${variant ? `${variant}-` : ''}${++uid}`;
}
//# sourceMappingURL=ouia.js.map