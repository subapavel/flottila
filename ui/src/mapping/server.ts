import { nodeOf } from '../../server/adminProxy.ts';
import type { ModuleServer } from '../../server/modules.ts';
import { mappingApi } from './files/mappingApi.ts';

/**
 * The mapping module's half of the development server: it answers the studio's `/api` over the mapping files of the
 * working folder that RAIL8_WORKSPACE names, and asks the node at RAIL8_ADMIN_URL, with RAIL8_ADMIN_TOKEN, for the
 * engine's work, as the `/admin` proxy does. Mappings may be saved and created unless RAIL8_READ_ONLY is `true`. The
 * studio's fake service may read its fixtures from the mapping corpus and contract.
 */
const mappingServer: ModuleServer = ({ origins, env }) => ({
  name: 'rail8-mapping-studio',
  config: () => ({ server: { fs: { allow: ['../mapping/corpus', '../mapping/contract'] } } }),
  configureServer(server) {
    const workspace = env.RAIL8_WORKSPACE?.trim() || undefined;
    server.middlewares.use(mappingApi({ workspace, editable: env.RAIL8_READ_ONLY?.trim() !== 'true', origins, node: nodeOf(env) }));
  },
});

export default mappingServer;
