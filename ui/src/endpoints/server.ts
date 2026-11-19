import type { ModuleServer } from '../../server/modules.ts';
import { endpointFiles } from './files/endpointFiles.ts';
import { flowFiles } from './files/flowFiles.ts';

/**
 * The endpoints module's half of the development server: it serves the endpoint files of the working folder that
 * RAIL8_WORKSPACE names to the settings form, under `/files/endpoints`, and its flow files to the flow screen, under
 * `/files/flows`. They may be edited unless RAIL8_READ_ONLY is `true`.
 */
const endpointsServer: ModuleServer = ({ origins, env }) => ({
  name: 'rail8-working-folder',
  configureServer(server) {
    const options = { workspace: env.RAIL8_WORKSPACE?.trim() || undefined, editable: env.RAIL8_READ_ONLY?.trim() !== 'true', origins };
    server.middlewares.use(endpointFiles(options));
    server.middlewares.use(flowFiles(options));
  },
});

export default endpointsServer;
