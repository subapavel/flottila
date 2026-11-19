import type { UiModule } from '../screens';
import { httpAdminApi } from './admin';
import { messagesScreen } from './screen';

/** The Messages screen, over the node's admin API. */
export default { order: 10, screens: [messagesScreen(httpAdminApi())] } satisfies UiModule;
