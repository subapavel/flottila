import { Content, PageSection, Title } from '@patternfly/react-core';
import type { ReactNode } from 'react';

/** A screen of the application: its id, the title on its tab and in the navigation, and what it shows. */
export interface Screen {
  id: string;
  title: string;
  render: () => ReactNode;
}

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

/** The screens the navigation lists, in its order. The first one is open when the application starts. */
export const screens: readonly Screen[] = [{ id: 'home', title: 'Home', render: () => <Home /> }];
