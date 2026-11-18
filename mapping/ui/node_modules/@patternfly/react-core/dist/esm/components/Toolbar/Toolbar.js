import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Component, createRef } from 'react';
import styles from '@patternfly/react-styles/css/components/Toolbar/toolbar.mjs';
import { css } from '@patternfly/react-styles';
import { ToolbarContext } from './ToolbarUtils';
import { ToolbarLabelGroupContent } from './ToolbarLabelGroupContent';
import { formatBreakpointMods, canUseDOM, getBreakpoint } from '../../helpers/util';
import { getResizeObserver } from '../../helpers/resizeObserver';
import { getOUIAProps } from '../../helpers';
import { SSRSafeIds } from '../../helpers/SSRSafeIds/SSRSafeIds';
import { PageContext } from '../Page/PageContext';
export var ToolbarColorVariant;
(function (ToolbarColorVariant) {
    ToolbarColorVariant["default"] = "default";
    ToolbarColorVariant["primary"] = "primary";
    ToolbarColorVariant["secondary"] = "secondary";
    ToolbarColorVariant["noBackground"] = "no-background";
})(ToolbarColorVariant || (ToolbarColorVariant = {}));
class Toolbar extends Component {
    constructor() {
        super(...arguments);
        this.labelGroupContentRef = createRef();
        this.toolbarRef = createRef();
        this.resizeObserver = () => { };
        this.staticFilterInfo = {};
        this.hasNoPadding = false;
        this.state = {
            isManagedToggleExpanded: false,
            filterInfo: {},
            windowWidth: 1200
        };
        this.isToggleManaged = () => !(this.props.isExpanded || !!this.props.toggleIsExpanded);
        this.toggleIsExpanded = () => {
            this.setState((prevState) => ({
                isManagedToggleExpanded: !prevState.isManagedToggleExpanded
            }));
        };
        this.closeExpandableContent = (e) => {
            if (e && e.target.innerWidth !== this.state.windowWidth) {
                this.setState(() => ({
                    isManagedToggleExpanded: false,
                    windowWidth: e.target.innerWidth
                }));
            }
        };
        this.closeExpandableContentOnContainerResize = () => {
            var _a;
            const containerWidth = (_a = this.toolbarRef.current) === null || _a === void 0 ? void 0 : _a.clientWidth;
            if (!containerWidth) {
                return;
            }
            const breakpoint = getBreakpoint(containerWidth);
            const isInitialMeasurement = this.containerBreakpoint === undefined;
            if (breakpoint !== this.containerBreakpoint) {
                this.containerBreakpoint = breakpoint;
                if (!isInitialMeasurement && this.state.isManagedToggleExpanded) {
                    this.setState({ isManagedToggleExpanded: false });
                }
            }
        };
        this.setupResizeHandling = () => {
            if (!this.isToggleManaged() || !canUseDOM) {
                return;
            }
            this.containerBreakpoint = undefined;
            const reference = this.props.isContainer ? this.toolbarRef.current : undefined;
            const handler = this.props.isContainer ? this.closeExpandableContentOnContainerResize : this.closeExpandableContent;
            this.resizeObserver = getResizeObserver(reference, handler, true);
        };
        this.cleanupResizeHandling = () => {
            this.resizeObserver();
            this.resizeObserver = () => { };
        };
        this.updateNumberFilters = (categoryName, numberOfFilters) => {
            const filterInfoToUpdate = Object.assign({}, this.staticFilterInfo);
            if (!filterInfoToUpdate.hasOwnProperty(categoryName) || filterInfoToUpdate[categoryName] !== numberOfFilters) {
                filterInfoToUpdate[categoryName] = numberOfFilters;
                this.staticFilterInfo = filterInfoToUpdate;
                this.setState({ filterInfo: filterInfoToUpdate });
            }
        };
        this.getNumberOfFilters = () => Object.values(this.state.filterInfo).reduce((acc, cur) => acc + cur, 0);
        this.renderToolbar = (randomId, generatedOuiaId) => {
            const _a = this.props, { hasNoPadding, clearAllFilters, clearFiltersButtonText, collapseListedFiltersBreakpoint, isExpanded: isExpandedProp, toggleIsExpanded, className, children, isContainer, isFullHeight, isStatic, isStickyBase, isStickyStuck, inset, isSticky, isVertical, ouiaId, numberOfFiltersText, customLabelGroupContent, colorVariant = ToolbarColorVariant.default } = _a, props = __rest(_a, ["hasNoPadding", "clearAllFilters", "clearFiltersButtonText", "collapseListedFiltersBreakpoint", "isExpanded", "toggleIsExpanded", "className", "children", "isContainer", "isFullHeight", "isStatic", "isStickyBase", "isStickyStuck", "inset", "isSticky", "isVertical", "ouiaId", "numberOfFiltersText", "customLabelGroupContent", "colorVariant"]);
            const { isManagedToggleExpanded } = this.state;
            const isToggleManaged = this.isToggleManaged();
            const isExpanded = isToggleManaged ? isManagedToggleExpanded : isExpandedProp;
            const numberOfFilters = this.getNumberOfFilters();
            const showClearFiltersButton = numberOfFilters > 0;
            return (_jsx(PageContext.Consumer, { children: (pageContext) => {
                    const toolbarContent = (_jsx("div", Object.assign({ className: css(styles.toolbar, hasNoPadding && styles.modifiers.noPadding, isContainer && styles.modifiers.container, isFullHeight && styles.modifiers.fullHeight, isStatic && styles.modifiers.static, isSticky && styles.modifiers.sticky, isStickyBase && styles.modifiers.stickyBase, isStickyStuck && styles.modifiers.stickyStuck, isVertical && styles.modifiers.vertical, formatBreakpointMods(inset, styles, '', !isContainer ? pageContext.getBreakpoint(pageContext.width) : undefined), colorVariant === 'primary' && styles.modifiers.primary, colorVariant === 'secondary' && styles.modifiers.secondary, colorVariant === 'no-background' && styles.modifiers.noBackground, className), id: randomId, ref: this.toolbarRef }, getOUIAProps(Toolbar.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId), props, { children: _jsxs(ToolbarContext.Provider, { value: {
                                isExpanded,
                                toggleIsExpanded: isToggleManaged ? this.toggleIsExpanded : toggleIsExpanded,
                                labelGroupContentRef: this.labelGroupContentRef,
                                updateNumberFilters: this.updateNumberFilters,
                                numberOfFilters,
                                clearAllFilters,
                                clearFiltersButtonText,
                                showClearFiltersButton,
                                toolbarId: randomId,
                                customLabelGroupContent,
                                isContainer,
                                toolbarRef: this.toolbarRef
                            }, children: [children, _jsx(ToolbarLabelGroupContent, { isExpanded: isExpanded, labelGroupContentRef: this.labelGroupContentRef, clearAllFilters: clearAllFilters, showClearFiltersButton: showClearFiltersButton, clearFiltersButtonText: clearFiltersButtonText, numberOfFilters: numberOfFilters, numberOfFiltersText: numberOfFiltersText, collapseListedFiltersBreakpoint: collapseListedFiltersBreakpoint, customLabelGroupContent: customLabelGroupContent })] }) })));
                    return isContainer ? (_jsx(PageContext.Provider, { value: Object.assign(Object.assign({}, pageContext), { width: null }), children: toolbarContent })) : (toolbarContent);
                } }));
        };
    }
    componentDidMount() {
        if (canUseDOM) {
            this.setState({ windowWidth: window.innerWidth });
        }
        this.setupResizeHandling();
    }
    componentDidUpdate(prevProps) {
        const wasToggleManaged = !(prevProps.isExpanded || !!prevProps.toggleIsExpanded);
        const isToggleManaged = this.isToggleManaged();
        if (prevProps.isContainer !== this.props.isContainer || wasToggleManaged !== isToggleManaged) {
            this.cleanupResizeHandling();
            this.setupResizeHandling();
        }
    }
    componentWillUnmount() {
        this.cleanupResizeHandling();
    }
    render() {
        return (_jsx(SSRSafeIds, { prefix: "pf-random-id-", ouiaComponentType: "Toolbar", children: (generatedId, generatedOuiaId) => this.renderToolbar(this.props.id || generatedId, generatedOuiaId) }));
    }
}
Toolbar.displayName = 'Toolbar';
export { Toolbar };
//# sourceMappingURL=Toolbar.js.map