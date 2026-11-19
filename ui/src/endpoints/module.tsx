import type { UiModule } from '../screens';
import { httpEndpointsApi } from './api';
import { httpFlowsApi } from './flows/api';
import { endpointsScreen, flowsScreen } from './screen';

const endpoints = httpEndpointsApi();

/** The Endpoints and Flows screens, over the files of the working folder the development server serves. */
export default { order: 20, screens: [endpointsScreen(endpoints), flowsScreen(httpFlowsApi(), endpoints)] } satisfies UiModule;
