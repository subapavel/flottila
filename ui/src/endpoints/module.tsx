import type { UiModule } from '../screens';
import { httpEndpointsApi } from './api';
import { endpointsScreen } from './screen';

/** The Endpoints screen, over the endpoint files the development server serves. */
export default { order: 20, screens: [endpointsScreen(httpEndpointsApi())] } satisfies UiModule;
