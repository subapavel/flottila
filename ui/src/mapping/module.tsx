import type { UiModule } from '../screens';
import { httpApiClient } from './api/http-api';
import { mappingsScreen } from './screen';
import { storageBrowserState } from './studio/browser-state/browser-state';

/** The Mappings screen, over the mapping service, keeping the viewer's preferences in the browser's local storage. */
export default {
  order: 30,
  screens: [mappingsScreen(httpApiClient(), storageBrowserState(window.localStorage))],
} satisfies UiModule;
