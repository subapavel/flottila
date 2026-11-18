import '@patternfly/react-core/dist/styles/base.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { fakeApiClient } from '../../src/mapping/api/fake/fake-api';
import { mappingsScreen } from '../../src/mapping/screen';
import { storageBrowserState } from '../../src/mapping/studio/browser-state/browser-state';
import { Shell } from '../../src/Shell';

// The shell with only its Mappings screen, over the fake mapping service, for the end-to-end scenarios that need no
// real service. The development server serves this page at /e2e/fake/; the production build leaves it out.
const mappings = mappingsScreen(fakeApiClient(), storageBrowserState(window.sessionStorage));

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Shell screens={[mappings]} start={mappings.id} />
  </StrictMode>,
);
