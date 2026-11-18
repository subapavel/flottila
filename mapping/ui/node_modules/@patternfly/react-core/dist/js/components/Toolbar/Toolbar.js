"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Toolbar = exports.ToolbarColorVariant = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const toolbar_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Toolbar/toolbar"));
const react_styles_1 = require("@patternfly/react-styles");
const ToolbarUtils_1 = require("./ToolbarUtils");
const ToolbarLabelGroupContent_1 = require("./ToolbarLabelGroupContent");
const util_1 = require("../../helpers/util");
const resizeObserver_1 = require("../../helpers/resizeObserver");
const helpers_1 = require("../../helpers");
const SSRSafeIds_1 = require("../../helpers/SSRSafeIds/SSRSafeIds");
const PageContext_1 = require("../Page/PageContext");
var ToolbarColorVariant;
(function (ToolbarColorVariant) {
    ToolbarColorVariant["default"] = "default";
    ToolbarColorVariant["primary"] = "primary";
    ToolbarColorVariant["secondary"] = "secondary";
    ToolbarColorVariant["noBackground"] = "no-background";
})(ToolbarColorVariant || (exports.ToolbarColorVariant = ToolbarColorVariant = {}));
class Toolbar extends react_1.Component {
    constructor() {
        super(...arguments);
        this.labelGroupContentRef = (0, react_1.createRef)();
        this.toolbarRef = (0, react_1.createRef)();
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
            const breakpoint = (0, util_1.getBreakpoint)(containerWidth);
            const isInitialMeasurement = this.containerBreakpoint === undefined;
            if (breakpoint !== this.containerBreakpoint) {
                this.containerBreakpoint = breakpoint;
                if (!isInitialMeasurement && this.state.isManagedToggleExpanded) {
                    this.setState({ isManagedToggleExpanded: false });
                }
            }
        };
        this.setupResizeHandling = () => {
            if (!this.isToggleManaged() || !util_1.canUseDOM) {
                return;
            }
            this.containerBreakpoint = undefined;
            const reference = this.props.isContainer ? this.toolbarRef.current : undefined;
            const handler = this.props.isContainer ? this.closeExpandableContentOnContainerResize : this.closeExpandableContent;
            this.resizeObserver = (0, resizeObserver_1.getResizeObserver)(reference, handler, true);
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
            const _a = this.props, { hasNoPadding, clearAllFilters, clearFiltersButtonText, collapseListedFiltersBreakpoint, isExpanded: isExpandedProp, toggleIsExpanded, className, children, isContainer, isFullHeight, isStatic, isStickyBase, isStickyStuck, inset, isSticky, isVertical, ouiaId, numberOfFiltersText, customLabelGroupContent, colorVariant = ToolbarColorVariant.default } = _a, props = tslib_1.__rest(_a, ["hasNoPadding", "clearAllFilters", "clearFiltersButtonText", "collapseListedFiltersBreakpoint", "isExpanded", "toggleIsExpanded", "className", "children", "isContainer", "isFullHeight", "isStatic", "isStickyBase", "isStickyStuck", "inset", "isSticky", "isVertical", "ouiaId", "numberOfFiltersText", "customLabelGroupContent", "colorVariant"]);
            const { isManagedToggleExpanded } = this.state;
            const isToggleManaged = this.isToggleManaged();
            const isExpanded = isToggleManaged ? isManagedToggleExpanded : isExpandedProp;
            const numberOfFilters = this.getNumberOfFilters();
            const showClearFiltersButton = numberOfFilters > 0;
            return ((0, jsx_runtime_1.jsx)(PageContext_1.PageContext.Consumer, { children: (pageContext) => {
                    const toolbarContent = ((0, jsx_runtime_1.jsx)("div", Object.assign({ className: (0, react_styles_1.css)(toolbar_1.default.toolbar, hasNoPadding && toolbar_1.default.modifiers.noPadding, isContainer && toolbar_1.default.modifiers.container, isFullHeight && toolbar_1.default.modifiers.fullHeight, isStatic && toolbar_1.default.modifiers.static, isSticky && toolbar_1.default.modifiers.sticky, isStickyBase && toolbar_1.default.modifiers.stickyBase, isStickyStuck && toolbar_1.default.modifiers.stickyStuck, isVertical && toolbar_1.default.modifiers.vertical, (0, util_1.formatBreakpointMods)(inset, toolbar_1.default, '', !isContainer ? pageContext.getBreakpoint(pageContext.width) : undefined), colorVariant === 'primary' && toolbar_1.default.modifiers.primary, colorVariant === 'secondary' && toolbar_1.default.modifiers.secondary, colorVariant === 'no-background' && toolbar_1.default.modifiers.noBackground, className), id: randomId, ref: this.toolbarRef }, (0, helpers_1.getOUIAProps)(Toolbar.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId), props, { children: (0, jsx_runtime_1.jsxs)(ToolbarUtils_1.ToolbarContext.Provider, { value: {
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
                            }, children: [children, (0, jsx_runtime_1.jsx)(ToolbarLabelGroupContent_1.ToolbarLabelGroupContent, { isExpanded: isExpanded, labelGroupContentRef: this.labelGroupContentRef, clearAllFilters: clearAllFilters, showClearFiltersButton: showClearFiltersButton, clearFiltersButtonText: clearFiltersButtonText, numberOfFilters: numberOfFilters, numberOfFiltersText: numberOfFiltersText, collapseListedFiltersBreakpoint: collapseListedFiltersBreakpoint, customLabelGroupContent: customLabelGroupContent })] }) })));
                    return isContainer ? ((0, jsx_runtime_1.jsx)(PageContext_1.PageContext.Provider, { value: Object.assign(Object.assign({}, pageContext), { width: null }), children: toolbarContent })) : (toolbarContent);
                } }));
        };
    }
    componentDidMount() {
        if (util_1.canUseDOM) {
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
        return ((0, jsx_runtime_1.jsx)(SSRSafeIds_1.SSRSafeIds, { prefix: "pf-random-id-", ouiaComponentType: "Toolbar", children: (generatedId, generatedOuiaId) => this.renderToolbar(this.props.id || generatedId, generatedOuiaId) }));
    }
}
exports.Toolbar = Toolbar;
Toolbar.displayName = 'Toolbar';
//# sourceMappingURL=Toolbar.js.map