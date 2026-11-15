import '@patternfly/react-core/dist/styles/base.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import type { ApiClient } from '../api/client';
import { httpApiClient } from '../api/http-api';
import { storageBrowserState } from '../studio/browser-state/browser-state';
import { Page } from './Page';

// The page (ADR-0028): the start page, or the Mapping studio of ?mapping=<id>; ?api=fake runs on the fake ApiClient.
const params = new URLSearchParams(window.location.search);
const api: Promise<ApiClient> =
  params.get('api') === 'fake'
    ? import('../api/fake/fake-api').then((fake) => fake.fakeApiClient())
    : Promise.resolve(httpApiClient());
const browserState = storageBrowserState(window.localStorage);

void api.then((client) =>
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <Page api={client} browserState={browserState} />
    </StrictMode>,
  ),
);
