import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Component, createRef } from 'react';
import * as ReactDOM from 'react-dom';
import styles from '@patternfly/react-styles/css/components/Toolbar/toolbar.mjs';
import { css } from '@patternfly/react-styles';
import { globalBreakpoints, ToolbarContext, ToolbarContentContext } from './ToolbarUtils';
import { Button } from '../Button';
import { formatBreakpointMods, toCamel } from '../../helpers/util';
import { ToolbarExpandableContent } from './ToolbarExpandableContent';
import { PageContext } from '../Page/PageContext';
class ToolbarToggleGroup extends Component {
    constructor() {
        super(...arguments);
        this.toggleRef = createRef();
        this.expandableContentRef = createRef();
        this.isContentPopup = (width) => {
            const size = width !== null && width !== void 0 ? width : (typeof window !== 'undefined' ? window.innerWidth : 1200);
            return size < globalBreakpoints.lg;
        };
    }
    render() {
        const _a = this.props, { toggleIcon, variant, visibility, breakpoint, gap, columnGap, rowGap, className, children, isExpanded, onToggle, labelContainerRef, clearAllFilters, showClearFiltersButton, clearFiltersButtonText } = _a, props = __rest(_a, ["toggleIcon", "variant", "visibility", "breakpoint", "gap", "columnGap", "rowGap", "className", "children", "isExpanded", "onToggle", "labelContainerRef", "clearAllFilters", "showClearFiltersButton", "clearFiltersButtonText"]);
        if (!breakpoint && !toggleIcon) {
            // eslint-disable-next-line no-console
            console.error('ToolbarToggleGroup will not be visible without a breakpoint or toggleIcon.');
        }
        return (_jsx(PageContext.Consumer, { children: ({ width, getBreakpoint }) => (_jsx(ToolbarContext.Consumer, { children: ({ toggleIsExpanded: managedOnToggle, isContainer, toolbarRef }) => {
                    const _onToggle = onToggle !== undefined ? onToggle : managedOnToggle;
                    return (_jsx(ToolbarContentContext.Consumer, { children: ({ expandableContentRef, expandableContentId, labelContainerRef: managedLabelContainerRef, isExpanded: managedIsExpanded, clearAllFilters: clearAllFiltersContext, clearFiltersButtonText: clearFiltersButtonContext, showClearFiltersButton: showClearFiltersButtonContext }) => {
                            var _a;
                            const _isExpanded = isExpanded !== undefined ? isExpanded : managedIsExpanded;
                            const _labelContainerRef = labelContainerRef !== undefined ? labelContainerRef : managedLabelContainerRef;
                            const breakpointMod = {};
                            breakpointMod[breakpoint] = 'show';
                            const expandableContent = (_jsx(ToolbarExpandableContent, { id: expandableContentId, expandableContentRef: this.expandableContentRef, isExpanded: _isExpanded, clearAllFilters: clearAllFilters || clearAllFiltersContext, showClearFiltersButton: showClearFiltersButton || showClearFiltersButtonContext, clearFiltersButtonText: clearFiltersButtonText || clearFiltersButtonContext, labelContainerRef: _labelContainerRef, children: children }));
                            const toggleButton = (_jsx("div", { className: css(styles.toolbarToggle), children: _jsx(Button, Object.assign({ variant: "plain", onClick: _onToggle, "aria-label": "Show Filters" }, (_isExpanded && { 'aria-expanded': true }), { "aria-haspopup": _isExpanded &&
                                        this.isContentPopup(isContainer ? (_a = toolbarRef === null || toolbarRef === void 0 ? void 0 : toolbarRef.current) === null || _a === void 0 ? void 0 : _a.clientWidth : undefined), "aria-controls": _isExpanded ? expandableContentId : undefined, ref: this.toggleRef, icon: toggleIcon })) }));
                            return (_jsxs("div", Object.assign({ className: css(styles.toolbarGroup, styles.modifiers.toggleGroup, variant &&
                                    styles.modifiers[toCamel(variant)], formatBreakpointMods(breakpointMod, styles, '', getBreakpoint(width)), formatBreakpointMods(visibility, styles, '', getBreakpoint(width)), formatBreakpointMods(gap, styles, '', getBreakpoint(width)), formatBreakpointMods(columnGap, styles, '', getBreakpoint(width)), formatBreakpointMods(rowGap, styles, '', getBreakpoint(width)), className) }, props, { children: [toggleButton, _isExpanded && ReactDOM.createPortal(expandableContent, expandableContentRef.current), !_isExpanded && children] })));
                        } }));
                } })) }));
    }
}
ToolbarToggleGroup.displayName = 'ToolbarToggleGroup';
export { ToolbarToggleGroup };
//# sourceMappingURL=ToolbarToggleGroup.js.map