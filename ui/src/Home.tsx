import { Content, PageSection, Title } from '@patternfly/react-core';
import type { Screen } from './screens';

function Home() {
  return (
    <PageSection>
      <Content>
        <Title headingLevel="h1">Rail8</Title>
        <p>Choose a screen in the navigation. Each one opens in its own tab; close a tab with its close button.</p>
      </Content>
    </PageSection>
  );
}

/** The shell's own screen, first in the navigation and open when the application starts. */
export const home: Screen = { id: 'home', title: 'Home', render: () => <Home /> };
