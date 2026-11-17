import type { Screen } from '../screens';
import type { AdminApi } from './admin';
import { MessageSearch } from './MessageSearch';
import { MessageView } from './MessageView';

/** The tab of one message: its id is `message:` and the message's id, its title the message's id. */
export function messageTab(api: AdminApi, id: string): Screen {
  return { id: `message:${id}`, title: id, render: () => <MessageView api={api} id={id} /> };
}

/** The Messages screen over `api`: the search, each message it finds opening in a tab of its own. */
export function messagesScreen(api: AdminApi): Screen {
  return {
    id: 'messages',
    title: 'Messages',
    render: (open) => <MessageSearch api={api} onOpen={(id) => open(messageTab(api, id))} />,
  };
}
