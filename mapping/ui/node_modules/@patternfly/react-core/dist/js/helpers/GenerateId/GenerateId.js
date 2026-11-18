"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.GenerateId = void 0;
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
const useSSRSafeId_1 = require("../useSSRSafeId");
const GenerateId = ({ prefix = 'pf-random-id-', children }) => {
    const id = (0, useSSRSafeId_1.useSSRSafeId)(prefix);
    return children(id);
};
exports.GenerateId = GenerateId;
GenerateId.displayName = 'GenerateId';
//# sourceMappingURL=GenerateId.js.map