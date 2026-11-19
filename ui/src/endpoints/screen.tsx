import type { Screen } from '../screens';
import type { EndpointsApi } from './api';
import { EndpointForm, type CloseGuard } from './EndpointForm';
import { EndpointList } from './EndpointList';
import type { FlowsApi } from './flows/api';
import { FlowList } from './flows/FlowList';
import { FlowPicture } from './flows/FlowPicture';

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

/** `api` with every endpoint file read as one that may not be edited: the form beside a flow's picture only shows it. */
function readOnly(api: EndpointsApi): EndpointsApi {
  return {
    ...api,
    read: async (path) => {
      const stored = await api.read(path);
      return { ...stored, file: { ...stored.file, editable: false } };
    },
  };
}

/**
 * The tab of one flow file: its id is `flow:` and the file's path, its title the flow's id. Its picture opens the
 * endpoints and flows it links to in tabs of their own. Closing it while the flow has unsaved changes asks first.
 */
export function flowTab(flows: FlowsApi, endpoints: EndpointsApi, path: string, id: string): Screen {
  const shown = readOnly(endpoints);
  const idOf = (file: string) => file.split('/').at(-1)!.replace(/\.ya?ml$/, '');
  let guard: CloseGuard | null = null;
  const guardClose = (next: CloseGuard | null) => {
    guard = next;
  };
  return {
    id: `flow:${path}`,
    title: id,
    render: (open) => (
      <FlowPicture
        api={flows}
        path={path}
        endpointForm={(file) => <EndpointForm key={file} api={shown} path={file} />}
        openEndpoint={(file) => open(endpointTab(endpoints, file, idOf(file)))}
        openFlow={(file) => open(flowTab(flows, endpoints, file, idOf(file)))}
        createEndpoint={(file, text) => endpoints.create(file, text)}
        guardClose={guardClose}
      />
    ),
    beforeClose: (close) => (guard === null ? close() : guard(close)),
  };
}

/** The Flows screen over `flows`: the working folder's flow files, each one's picture opening in a tab of its own. */
export function flowsScreen(flows: FlowsApi, endpoints: EndpointsApi): Screen {
  return {
    id: 'flows',
    title: 'Flows',
    render: (open) => <FlowList api={flows} onOpen={(path, id) => open(flowTab(flows, endpoints, path, id))} />,
  };
}
