import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Children, Component, createRef, isValidElement } from 'react';
import styles from '@patternfly/react-styles/css/components/Tabs/tabs.mjs';
import { css } from '@patternfly/react-styles';
import RhMicronsCaretLeftIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-caret-left-icon';
import RhMicronsCaretRightIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-caret-right-icon';
import RhMicronsCaretDownIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-caret-down-icon';
import RhUiAddIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-add-icon';
import { isElementInView, formatBreakpointMods, getLanguageDirection, getInlineStartProperty } from '../../helpers/util';
import { TabContent } from './TabContent';
import { TabsContextProvider } from './TabsContext';
import { OverflowTab } from './OverflowTab';
import { Button } from '../Button';
import { getOUIAProps, canUseDOM } from '../../helpers';
import { SSRSafeIds } from '../../helpers/SSRSafeIds/SSRSafeIds';
import { GenerateId } from '../../helpers/GenerateId/GenerateId';
import linkAccentLength from '@patternfly/react-tokens/dist/esm/c_tabs_link_accent_length';
import linkAccentStart from '@patternfly/react-tokens/dist/esm/c_tabs_link_accent_start';
export var TabsComponent;
(function (TabsComponent) {
    TabsComponent["div"] = "div";
    TabsComponent["nav"] = "nav";
})(TabsComponent || (TabsComponent = {}));
const variantStyle = {
    default: '',
    secondary: styles.modifiers.secondary
};
const getHashFromHref = (href) => {
    var _a;
    const hashIndex = (_a = href === null || href === void 0 ? void 0 : href.indexOf('#')) !== null && _a !== void 0 ? _a : -1;
    return hashIndex >= 0 ? href.slice(hashIndex) : undefined;
};
const getTabHashActiveKey = ({ children, component, isNav }) => {
    var _a;
    if (!canUseDOM || !(component === TabsComponent.nav || isNav) || !window.location.hash) {
        return undefined;
    }
    return (_a = Children.toArray(children)
        .filter((child) => isValidElement(child))
        .filter(({ props }) => !props.isHidden)
        .find(({ props }) => !props.isDisabled && !props.isAriaDisabled && getHashFromHref(props.href) === window.location.hash)) === null || _a === void 0 ? void 0 : _a.props.eventKey;
};
class Tabs extends Component {
    constructor(props) {
        var _a;
        super(props);
        this.tabList = createRef();
        this.leftScrollButtonRef = createRef();
        this.direction = 'ltr';
        this.scrollTimeout = null;
        this.countOverflowingElements = (container) => {
            const elements = Array.from(container.children);
            return elements.filter((element) => !isElementInView(container, element, false)).length;
        };
        this.handleScrollButtons = () => {
            const { isOverflowHorizontal: isOverflowHorizontal } = this.props;
            // add debounce to the scroll event
            clearTimeout(this.scrollTimeout);
            this.scrollTimeout = setTimeout(() => {
                const container = this.tabList.current;
                let disableBackScrollButton = true;
                let disableForwardScrollButton = true;
                let enableScrollButtons = false;
                let overflowingTabCount = 0;
                if (container && !this.props.isVertical && !isOverflowHorizontal) {
                    // get first element and check if it is in view
                    const overflowOnLeft = !isElementInView(container, container.firstChild, false);
                    // get last element and check if it is in view
                    const overflowOnRight = !isElementInView(container, container.lastChild, false);
                    enableScrollButtons = overflowOnLeft || overflowOnRight;
                    disableBackScrollButton = !overflowOnLeft;
                    disableForwardScrollButton = !overflowOnRight;
                }
                if (isOverflowHorizontal) {
                    overflowingTabCount = this.countOverflowingElements(container);
                }
                this.setState({
                    enableScrollButtons,
                    disableBackScrollButton,
                    disableForwardScrollButton,
                    overflowingTabCount
                });
            }, 100);
        };
        this.scrollBack = () => {
            // find first Element that is fully in view on the left, then scroll to the element before it
            if (this.tabList.current) {
                const container = this.tabList.current;
                const childrenArr = Array.from(container.children);
                let firstElementInView;
                let lastElementOutOfView;
                let i;
                for (i = 0; i < childrenArr.length && !firstElementInView; i++) {
                    if (isElementInView(container, childrenArr[i], false)) {
                        firstElementInView = childrenArr[i];
                        lastElementOutOfView = childrenArr[i - 1];
                    }
                }
                if (lastElementOutOfView) {
                    if (this.direction === 'ltr') {
                        // LTR scrolls left to go back
                        container.scrollLeft -= lastElementOutOfView.scrollWidth;
                    }
                    else {
                        // RTL scrolls right to go back
                        container.scrollLeft += lastElementOutOfView.scrollWidth;
                    }
                }
            }
        };
        this.scrollForward = () => {
            // find last Element that is fully in view on the right, then scroll to the element after it
            if (this.tabList.current) {
                const container = this.tabList.current;
                const childrenArr = Array.from(container.children);
                let lastElementInView;
                let firstElementOutOfView;
                for (let i = childrenArr.length - 1; i >= 0 && !lastElementInView; i--) {
                    if (isElementInView(container, childrenArr[i], false)) {
                        lastElementInView = childrenArr[i];
                        firstElementOutOfView = childrenArr[i + 1];
                    }
                }
                if (firstElementOutOfView) {
                    if (this.direction === 'ltr') {
                        // LTR scrolls right to go forward
                        container.scrollLeft += firstElementOutOfView.scrollWidth;
                    }
                    else {
                        // RTL scrolls left to go forward
                        container.scrollLeft -= firstElementOutOfView.scrollWidth;
                    }
                }
            }
        };
        this.hideScrollButtons = () => {
            const { enableScrollButtons, renderScrollButtons, showScrollButtons } = this.state;
            if (!enableScrollButtons && !showScrollButtons && renderScrollButtons) {
                this.setState({ renderScrollButtons: false });
            }
        };
        this.setAccentStyles = (shouldInitializeStyle) => {
            const currentItem = this.tabList.current.querySelector('li.pf-m-current');
            if (!currentItem) {
                return;
            }
            const { isVertical } = this.props;
            const { offsetWidth, offsetHeight, offsetTop } = currentItem;
            const lengthValue = isVertical ? offsetHeight : offsetWidth;
            const startValue = isVertical ? offsetTop : getInlineStartProperty(currentItem, this.tabList.current);
            this.setState(Object.assign({ currentLinkAccentLength: `${lengthValue}px`, currentLinkAccentStart: `${startValue}px` }, (shouldInitializeStyle && { isInitializingAccent: true })));
            setTimeout(() => {
                this.setState({ isInitializingAccent: false });
            }, 0);
        };
        this.handleResize = () => {
            this.handleScrollButtons();
            this.setAccentStyles();
        };
        const hashActiveKey = getTabHashActiveKey(props);
        this.state = {
            enableScrollButtons: false,
            showScrollButtons: false,
            renderScrollButtons: false,
            disableBackScrollButton: true,
            disableForwardScrollButton: true,
            shownKeys: [(_a = hashActiveKey !== null && hashActiveKey !== void 0 ? hashActiveKey : this.props.defaultActiveKey) !== null && _a !== void 0 ? _a : this.props.activeKey],
            uncontrolledActiveKey: hashActiveKey !== null && hashActiveKey !== void 0 ? hashActiveKey : this.props.defaultActiveKey,
            initialActiveKey: this.props.defaultActiveKey === undefined ? hashActiveKey : undefined,
            uncontrolledIsExpandedLocal: this.props.defaultIsExpanded,
            overflowingTabCount: 0,
            isInitializingAccent: true,
            currentLinkAccentLength: linkAccentLength.value,
            currentLinkAccentStart: linkAccentStart.value
        };
        if (this.props.isVertical && this.props.expandable !== undefined) {
            if (!this.props.toggleAriaLabel && !this.props.toggleText) {
                // eslint-disable-next-line no-console
                console.error('Tabs:', 'toggleAriaLabel or the toggleText prop is required to make the toggle button accessible');
            }
        }
    }
    handleTabClick(event, eventKey, tabContentRef) {
        const { shownKeys, initialActiveKey } = this.state;
        const { onSelect, defaultActiveKey, component, isNav } = this.props;
        if (event.currentTarget instanceof HTMLAnchorElement && event.currentTarget.hasAttribute('href')) {
            event.preventDefault();
            if (canUseDOM && (component === TabsComponent.nav || isNav)) {
                window.history.pushState(null, '', event.currentTarget.href);
            }
        }
        // if defaultActiveKey Tabs are uncontrolled, set new active key internally
        if (defaultActiveKey !== undefined) {
            this.setState({
                uncontrolledActiveKey: eventKey
            });
        }
        else {
            if (initialActiveKey !== undefined) {
                this.setState({ initialActiveKey: undefined });
            }
            onSelect(event, eventKey);
        }
        // process any tab content sections outside of the component
        if (tabContentRef) {
            Children.toArray(this.props.children)
                .filter((child) => isValidElement(child))
                .filter(({ props }) => props.tabContentRef && props.tabContentRef.current)
                .forEach((child) => (child.props.tabContentRef.current.hidden = true));
            // most recently selected tabContent
            if (tabContentRef.current) {
                tabContentRef.current.hidden = false;
            }
        }
        if (this.props.mountOnEnter) {
            this.setState({
                shownKeys: shownKeys.concat(eventKey)
            });
        }
    }
    componentDidMount() {
        if (!this.props.isVertical) {
            if (canUseDOM) {
                window.addEventListener('resize', this.handleResize, false);
            }
            this.direction = getLanguageDirection(this.tabList.current);
            // call the handle resize function to check if scroll buttons should be shown
            this.handleScrollButtons();
        }
        this.setAccentStyles(true);
    }
    componentWillUnmount() {
        var _a;
        if (!this.props.isVertical) {
            if (canUseDOM) {
                window.removeEventListener('resize', this.handleResize, false);
            }
        }
        clearTimeout(this.scrollTimeout);
        (_a = this.leftScrollButtonRef.current) === null || _a === void 0 ? void 0 : _a.removeEventListener('transitionend', this.hideScrollButtons);
    }
    componentDidUpdate(prevProps, prevState) {
        this.direction = getLanguageDirection(this.tabList.current);
        const { activeKey, mountOnEnter, isOverflowHorizontal, children, defaultActiveKey } = this.props;
        const { shownKeys, overflowingTabCount, enableScrollButtons, uncontrolledActiveKey, initialActiveKey } = this.state;
        const isOnCloseUpdate = !!prevProps.onClose !== !!this.props.onClose;
        if ((defaultActiveKey !== undefined && prevState.uncontrolledActiveKey !== uncontrolledActiveKey) ||
            (defaultActiveKey === undefined && prevProps.activeKey !== activeKey) ||
            isOnCloseUpdate) {
            this.setAccentStyles(isOnCloseUpdate);
        }
        if (prevProps.activeKey !== activeKey && mountOnEnter && shownKeys.indexOf(activeKey) < 0) {
            this.setState({
                shownKeys: shownKeys.concat(activeKey)
            });
        }
        if (defaultActiveKey === undefined && prevProps.activeKey !== activeKey && initialActiveKey !== undefined) {
            this.setState({ initialActiveKey: undefined });
        }
        if (prevProps.children &&
            children &&
            Children.toArray(prevProps.children).length !== Children.toArray(children).length) {
            this.handleScrollButtons();
            this.setAccentStyles(true);
        }
        const currentOverflowingTabCount = this.countOverflowingElements(this.tabList.current);
        if (isOverflowHorizontal && currentOverflowingTabCount) {
            this.setState({ overflowingTabCount: currentOverflowingTabCount + overflowingTabCount });
        }
        if (!prevState.enableScrollButtons && enableScrollButtons) {
            this.setState({ renderScrollButtons: true });
            setTimeout(() => {
                var _a, _b;
                // Remove any existing listener before adding a new one to prevent accumulation
                (_a = this.leftScrollButtonRef.current) === null || _a === void 0 ? void 0 : _a.removeEventListener('transitionend', this.hideScrollButtons);
                (_b = this.leftScrollButtonRef.current) === null || _b === void 0 ? void 0 : _b.addEventListener('transitionend', this.hideScrollButtons);
                this.setState({ showScrollButtons: true });
            }, 100);
        }
        else if (prevState.enableScrollButtons && !enableScrollButtons) {
            this.setState({ showScrollButtons: false });
        }
        if (prevState.uncontrolledIsExpandedLocal !== this.state.uncontrolledIsExpandedLocal) {
            this.setAccentStyles(true);
        }
    }
    static getDerivedStateFromProps(nextProps, prevState) {
        if (prevState.uncontrolledActiveKey === undefined) {
            return null;
        }
        const childrenHasTabWithActiveEventKey = Children.toArray(nextProps.children)
            .filter((child) => isValidElement(child))
            .some(({ props }) => props.eventKey === prevState.uncontrolledActiveKey);
        // if uncontrolledActiveKey is an existing eventKey of any Tab of nextProps.children --> don't update uncontrolledActiveKey
        if (childrenHasTabWithActiveEventKey) {
            return null;
        }
        // otherwise update state derived from nextProps.defaultActiveKey
        return {
            uncontrolledActiveKey: nextProps.defaultActiveKey,
            shownKeys: nextProps.defaultActiveKey !== undefined ? [nextProps.defaultActiveKey] : [nextProps.activeKey] // only for mountOnEnter case
        };
    }
    render() {
        const _a = this.props, { className, children, activeKey, defaultActiveKey, id, isAddButtonDisabled, isFilled, isSubtab, isNav, isVertical, isBox, hasNoBorderBottom, leftScrollAriaLabel, rightScrollAriaLabel, backScrollAriaLabel, forwardScrollAriaLabel, 'aria-label': ariaLabel, component, ouiaId, ouiaSafe, mountOnEnter, unmountOnExit, usePageInsets, inset, variant, expandable, isExpanded, defaultIsExpanded, toggleText, toggleAriaLabel, addButtonAriaLabel, tabListAriaLabel, tabListAriaLabelledBy, onToggle, onClose, onAdd, isOverflowHorizontal: isOverflowHorizontal } = _a, props = __rest(_a, ["className", "children", "activeKey", "defaultActiveKey", "id", "isAddButtonDisabled", "isFilled", "isSubtab", "isNav", "isVertical", "isBox", "hasNoBorderBottom", "leftScrollAriaLabel", "rightScrollAriaLabel", "backScrollAriaLabel", "forwardScrollAriaLabel", 'aria-label', "component", "ouiaId", "ouiaSafe", "mountOnEnter", "unmountOnExit", "usePageInsets", "inset", "variant", "expandable", "isExpanded", "defaultIsExpanded", "toggleText", "toggleAriaLabel", "addButtonAriaLabel", "tabListAriaLabel", "tabListAriaLabelledBy", "onToggle", "onClose", "onAdd", "isOverflowHorizontal"]);
        const { showScrollButtons, renderScrollButtons, disableBackScrollButton, disableForwardScrollButton, shownKeys, uncontrolledActiveKey, initialActiveKey, uncontrolledIsExpandedLocal, overflowingTabCount, isInitializingAccent, currentLinkAccentLength, currentLinkAccentStart } = this.state;
        const filteredChildren = Children.toArray(children)
            .filter((child) => isValidElement(child))
            .filter(({ props }) => !props.isHidden);
        const filteredChildrenWithoutOverflow = filteredChildren.slice(0, filteredChildren.length - overflowingTabCount);
        const filteredChildrenOverflowing = filteredChildren.slice(filteredChildren.length - overflowingTabCount);
        const overflowingTabProps = filteredChildrenOverflowing.map((child) => child.props);
        const defaultComponent = isNav && !component ? 'nav' : 'div';
        const Component = component !== undefined ? component : defaultComponent;
        const localActiveKey = defaultActiveKey !== undefined ? uncontrolledActiveKey : (initialActiveKey !== null && initialActiveKey !== void 0 ? initialActiveKey : activeKey);
        const isExpandedLocal = defaultIsExpanded !== undefined ? uncontrolledIsExpandedLocal : isExpanded;
        /*  Uncontrolled expandable tabs */
        const toggleTabs = (event, newValue) => {
            if (isExpanded === undefined) {
                this.setState({ uncontrolledIsExpandedLocal: newValue });
            }
            else {
                onToggle(event, newValue);
            }
        };
        const hasOverflowTab = isOverflowHorizontal && overflowingTabCount > 0;
        const overflowObjectProps = typeof isOverflowHorizontal === 'object' ? Object.assign({}, isOverflowHorizontal) : {};
        return (_jsx(SSRSafeIds, { prefix: "pf-random-id-", ouiaComponentType: Tabs.displayName, children: (generatedId, generatedOuiaId) => {
                const uniqueId = id || generatedId;
                return (_jsxs(TabsContextProvider, { value: {
                        variant,
                        mountOnEnter,
                        unmountOnExit,
                        localActiveKey,
                        uniqueId,
                        setAccentStyles: this.setAccentStyles,
                        handleTabClick: (...args) => this.handleTabClick(...args),
                        handleTabClose: onClose
                    }, children: [_jsxs(Component, Object.assign({ "aria-label": ariaLabel, className: css(styles.tabs, styles.modifiers.animateCurrent, isFilled && styles.modifiers.fill, isSubtab && styles.modifiers.subtab, isNav && styles.modifiers.nav, isVertical && styles.modifiers.vertical, isVertical && expandable && formatBreakpointMods(expandable, styles), isVertical && expandable && isExpandedLocal && styles.modifiers.expanded, isBox && styles.modifiers.box, showScrollButtons && styles.modifiers.scrollable, usePageInsets && styles.modifiers.pageInsets, hasNoBorderBottom && styles.modifiers.noBorderBottom, formatBreakpointMods(inset, styles), variantStyle[variant], hasOverflowTab && styles.modifiers.overflow, isInitializingAccent && styles.modifiers.initializingAccent, className) }, getOUIAProps(Tabs.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId, ouiaSafe), { id: id && id }, props, { style: Object.assign({ [linkAccentLength.name]: currentLinkAccentLength, [linkAccentStart.name]: currentLinkAccentStart }, props.style), children: [expandable && isVertical && (_jsx(GenerateId, { children: (randomId) => (_jsx("div", { className: css(styles.tabsToggle), children: _jsx("div", { className: 'pf-v6-c-tabs__toggle-button', children: _jsx(Button, { onClick: (event) => toggleTabs(event, !isExpandedLocal), variant: "plain", "aria-label": toggleAriaLabel, "aria-expanded": isExpandedLocal, id: `${randomId}-button`, "aria-labelledby": `${randomId}-text ${randomId}-button`, icon: _jsx("span", { className: css(styles.tabsToggleIcon), children: _jsx(RhMicronsCaretDownIcon, {}) }), children: toggleText && _jsx("span", { id: `${randomId}-text`, children: toggleText }) }) }) })) })), renderScrollButtons && (_jsx("div", { className: css(styles.tabsScrollButton), children: _jsx(Button, { "aria-label": backScrollAriaLabel || leftScrollAriaLabel, onClick: this.scrollBack, isDisabled: disableBackScrollButton, "aria-hidden": disableBackScrollButton, ref: this.leftScrollButtonRef, variant: "plain", icon: _jsx(RhMicronsCaretLeftIcon, {}) }) })), _jsxs("ul", { "aria-label": tabListAriaLabel, "aria-labelledby": tabListAriaLabelledBy, className: css(styles.tabsList), ref: this.tabList, onScroll: this.handleScrollButtons, role: "tablist", children: [isOverflowHorizontal ? filteredChildrenWithoutOverflow : filteredChildren, hasOverflowTab && _jsx(OverflowTab, Object.assign({ overflowingTabs: overflowingTabProps }, overflowObjectProps))] }), renderScrollButtons && (_jsx("div", { className: css(styles.tabsScrollButton), children: _jsx(Button, { "aria-label": forwardScrollAriaLabel || rightScrollAriaLabel, onClick: this.scrollForward, isDisabled: disableForwardScrollButton, "aria-hidden": disableForwardScrollButton, variant: "plain", icon: _jsx(RhMicronsCaretRightIcon, {}) }) })), onAdd !== undefined && (_jsx("span", { className: css(styles.tabsAdd), children: _jsx(Button, { variant: "plain", "aria-label": addButtonAriaLabel || 'Add tab', onClick: onAdd, icon: _jsx(RhUiAddIcon, {}), isDisabled: isAddButtonDisabled }) }))] })), filteredChildren
                            .filter((child) => child.props.children &&
                            !(unmountOnExit && child.props.eventKey !== localActiveKey) &&
                            !(mountOnEnter && shownKeys.indexOf(child.props.eventKey) === -1))
                            .map((child) => (_jsx(TabContent, { activeKey: localActiveKey, child: child, id: child.props.id || uniqueId, ouiaId: child.props.ouiaId }, child.props.eventKey)))] }));
            } }));
    }
}
Tabs.displayName = 'Tabs';
Tabs.defaultProps = {
    activeKey: 0,
    onSelect: () => undefined,
    isFilled: false,
    isSubtab: false,
    isNav: false,
    isVertical: false,
    isBox: false,
    hasNoBorderBottom: false,
    leftScrollAriaLabel: 'Scroll left',
    backScrollAriaLabel: 'Scroll back',
    rightScrollAriaLabel: 'Scroll right',
    forwardScrollAriaLabel: 'Scroll forward',
    mountOnEnter: false,
    unmountOnExit: false,
    ouiaSafe: true,
    variant: 'default',
    onToggle: (_event, _isExpanded) => undefined
};
export { Tabs };
//# sourceMappingURL=Tabs.js.map