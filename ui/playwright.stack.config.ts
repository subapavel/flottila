import { defineConfig } from '@playwright/test';
import { stackWorkspace } from './e2e/stack-workspace';

// The @stack scenarios: the UI's development server, whose /api requests go to the real mapping service. The service
// is the plain jar, built in mapping/ with the repository's wrapper:
//   ../mvnw -B -pl service -am package -DskipTests
// It answers on a fixed port over a temporary copy of the mapping corpus that e2e/stack-setup.mjs prepares.
const servicePort = 18080;
const port = 5173;

export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.stack.spec.ts',
  use: {
    baseURL: `http://localhost:${port}`,
  },
  webServer: [
    {
      command: `rm -rf ${stackWorkspace} && cp -r ../mapping/corpus ${stackWorkspace} && node e2e/stack-setup.mjs ${stackWorkspace} && exec java -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=${servicePort} -Drail8.mapping.workspace=${stackWorkspace} -jar ../mapping/service/target/quarkus-app/quarkus-run.jar`,
      url: `http://127.0.0.1:${servicePort}/api/mappings`,
      reuseExistingServer: false,
      timeout: 60_000,
    },
    {
      command: 'npm run dev',
      url: `http://localhost:${port}/`,
      env: { QUARKUS_HTTP_PORT: String(servicePort) },
      reuseExistingServer: false,
    },
  ],
});
