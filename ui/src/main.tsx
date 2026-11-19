import '@patternfly/react-core/dist/styles/base.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { home } from './Home';
import { screenList } from './screens';
import { Shell } from './Shell';

// Every folder of src is a module of the UI; its module.tsx names its screens.
const screens = screenList(home, import.meta.glob('./*/module.tsx', { eager: true }));

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Shell screens={screens} start={home.id} />
  </StrictMode>,
);
