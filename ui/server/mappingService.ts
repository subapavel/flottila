/**
 * Where the development server sends the mapping studio's `/api` requests: the mapping service on this machine, on
 * the port Quarkus listens on. Quarkus reads QUARKUS_HTTP_PORT itself, so one variable moves the service and the
 * development UI together; 8080 is Quarkus' default.
 */
export function mappingServiceTarget(env: Record<string, string | undefined>): string {
  const port = env.QUARKUS_HTTP_PORT;
  return `http://127.0.0.1:${port ? port : '8080'}`;
}
