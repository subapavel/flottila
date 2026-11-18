/** SSR-safe ID generation component using render props.
 * For function components, prefer the useSSRSafeId hook instead.
 *
 * Example of how to use this component
 *
 * const Component = ({id}: {id: string}) => (
 *  <GenerateId>{randomId => (
 *     <div id={id || randomId}>
 *       div with random ID
 *     </div>
 *   )}
 *  </GenerateId>
 *  );
 */
import { useSSRSafeId } from '../useSSRSafeId';
const GenerateId = ({ prefix = 'pf-random-id-', children }) => {
    const id = useSSRSafeId(prefix);
    return children(id);
};
GenerateId.displayName = 'GenerateId';
export { GenerateId };
//# sourceMappingURL=GenerateId.js.map