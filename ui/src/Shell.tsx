import {
  EmptyState,
  EmptyStateBody,
  Masthead,
  MastheadBrand,
  MastheadLogo,
  MastheadMain,
  MastheadToggle,
  Nav,
  NavItem,
  NavList,
  Page,
  PageSection,
  PageSidebar,
  PageSidebarBody,
  PageToggleButton,
  Tab,
  Tabs,
  TabTitleText,
} from '@patternfly/react-core';
import { useCallback, useState } from 'react';
import type { Screen } from './screens';
import { closeTab, noTabs, openTab } from './tabs';

export interface ShellProps {
  /** The screens the navigation lists, in its order. */
  screens: readonly Screen[];
  /** The id of the screen open in a tab when the shell starts, if any. */
  start?: string;
}

/**
 * The application's shell: a masthead with the brand and the button that shows or hides the navigation, the
 * navigation listing every screen, and the tabs of the open screens. Choosing a screen in the navigation opens it in a
 * new tab, or shows the tab it already has; each tab closes with its close button, once its screen agrees. A screen may
 * open tabs of its own, such as one for each message it found, through the function its `render` is given. With no
 * tab open the main area says so.
 */
export function Shell({ screens, start }: ShellProps) {
  const [tabs, setTabs] = useState(() => (start === undefined ? noTabs : openTab(noTabs, start)));
  // the tabs screens opened, by id; the navigation's screens are looked up in `screens`
  const [opened, setOpened] = useState<ReadonlyMap<string, Screen>>(() => new Map());
  const byId = new Map(screens.map((screen) => [screen.id, screen]));

  const open = useCallback((screen: Screen) => {
    setOpened((current) => (current.has(screen.id) ? current : new Map(current).set(screen.id, screen)));
    setTabs((current) => openTab(current, screen.id));
  }, []);

  const remove = (id: string) => {
    setTabs((current) => closeTab(current, id));
    setOpened((current) => {
      if (!current.has(id)) return current;
      const rest = new Map(current);
      rest.delete(id);
      return rest;
    });
  };
  const close = (id: string) => {
    const screen = opened.get(id) ?? byId.get(id);
    if (screen?.beforeClose) screen.beforeClose(() => remove(id));
    else remove(id);
  };

  const masthead = (
    <Masthead>
      <MastheadMain>
        <MastheadToggle>
          <PageToggleButton isHamburgerButton aria-label="Navigation" />
        </MastheadToggle>
        <MastheadBrand>
          <MastheadLogo component="span">Rail8</MastheadLogo>
        </MastheadBrand>
      </MastheadMain>
    </Masthead>
  );

  const sidebar = (
    <PageSidebar>
      <PageSidebarBody>
        <Nav aria-label="Screens">
          <NavList>
            {screens.map((screen) => (
              <NavItem
                key={screen.id}
                itemId={screen.id}
                component="button"
                isActive={tabs.active === screen.id}
                onClick={() => setTabs((current) => openTab(current, screen.id))}
              >
                {screen.title}
              </NavItem>
            ))}
          </NavList>
        </Nav>
      </PageSidebarBody>
    </PageSidebar>
  );

  return (
    <Page masthead={masthead} sidebar={sidebar} isManagedSidebar>
      {tabs.active === null ? (
        <PageSection>
          <EmptyState titleText="No screen open" headingLevel="h1">
            <EmptyStateBody>Choose a screen in the navigation to open it in a tab.</EmptyStateBody>
          </EmptyState>
        </PageSection>
      ) : (
        <PageSection type="tabs">
          <Tabs
            aria-label="Open screens"
            activeKey={tabs.active}
            onSelect={(_event, key) => setTabs((current) => openTab(current, String(key)))}
            onClose={(_event, key) => close(String(key))}
            usePageInsets
            mountOnEnter
          >
            {tabs.open.map((id) => {
              const screen = opened.get(id) ?? byId.get(id);
              const title = screen?.title ?? id;
              return (
                <Tab key={id} eventKey={id} title={<TabTitleText>{title}</TabTitleText>} closeButtonAriaLabel={`Close ${title}`}>
                  {screen?.render(open)}
                </Tab>
              );
            })}
          </Tabs>
        </PageSection>
      )}
    </Page>
  );
}
