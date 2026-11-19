import type { ModuleServer } from '../../server/modules.ts';

/**
 * Where the development server sends the mapping studio's `/api` requests: the mapping service on this machine, on
 * the port Quarkus listens on. Quarkus reads QUARKUS_HTTP_PORT itself, so one variable moves the service and the
 * development UI together; 8080 is Quarkus' default.
 */
export function mappingServiceTarget(env: Record<string, string | undefined>): string {
  const port = env.QUARKUS_HTTP_PORT;
  return `http://127.0.0.1:${port ? port : '8080'}`;
}

/**
 * The mapping module's half of the development server: the studio's `/api` requests go to the mapping service, and
 * the studio's fake service may read its fixtures from the mapping corpus and contract.
 */
const mappingServer: ModuleServer = ({ env }) => ({
  name: 'rail8-mapping-service',
  config: () => ({
    server: {
      proxy: { '/api': mappingServiceTarget(env) },
      fs: { allow: ['../mapping/corpus', '../mapping/contract'] },
    },
  }),
});

export default mappingServer;
