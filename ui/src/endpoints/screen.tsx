import type { Screen } from '../screens';
import type { EndpointsApi } from './api';
import { EndpointForm, type CloseGuard } from './EndpointForm';
import { EndpointList } from './EndpointList';

/**
 * The tab of one endpoint file: its id is `endpoint:` and the file's path, its title the endpoint's id. Closing it while
 * the form has unsaved changes asks first.
 */
export function endpointTab(api: EndpointsApi, path: string, id: string): Screen {
  let guard: CloseGuard | null = null;
  const guardClose = (next: CloseGuard | null) => {
    guard = next;
  };
  return {
    id: `endpoint:${path}`,
    title: id,
    render: () => <EndpointForm api={api} path={path} guardClose={guardClose} />,
    beforeClose: (close) => (guard === null ? close() : guard(close)),
  };
}

/** The Endpoints screen over `api`: the working folder's endpoint files, each one's settings form opening in a tab of its own. */
export function endpointsScreen(api: EndpointsApi): Screen {
  return {
    id: 'endpoints',
    title: 'Endpoints',
    render: (open) => <EndpointList api={api} onOpen={(path, id) => open(endpointTab(api, path, id))} />,
  };
}
