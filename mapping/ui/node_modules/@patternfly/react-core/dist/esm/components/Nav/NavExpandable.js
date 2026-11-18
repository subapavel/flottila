import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Component } from 'react';
import styles from '@patternfly/react-styles/css/components/Nav/nav.mjs';
import { css } from '@patternfly/react-styles';
import RhMicronsCaretDownIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-caret-down-icon';
import { NavContext } from './Nav';
import { PageSidebarContext } from '../Page/PageSidebar';
import { getOUIAProps } from '../../helpers';
import { SSRSafeIds } from '../../helpers/SSRSafeIds/SSRSafeIds';
import { IS_INERT } from '../../helpers/inert';
import RhUiEllipsisHorizontalFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-ellipsis-horizontal-fill-icon';
class NavExpandable extends Component {
    constructor() {
        super(...arguments);
        this.state = {
            expandedState: this.props.isExpanded
        };
        this.onExpand = (event, onToggle) => {
            const { expandedState } = this.state;
            if (this.props.onExpand) {
                this.props.onExpand(event, !expandedState);
            }
            else {
                this.setState((prevState) => ({ expandedState: !prevState.expandedState }));
                const { groupId } = this.props;
                onToggle(event, groupId, !expandedState);
            }
        };
    }
    componentDidMount() {
        this.setState({ expandedState: this.props.isExpanded });
    }
    componentDidUpdate(prevProps) {
        if (this.props.isExpanded !== prevProps.isExpanded) {
            this.setState({ expandedState: this.props.isExpanded });
        }
    }
    render() {
        const _a = this.props, { icon, title, srText, children, className, isActive, ouiaId, 
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        groupId, id, 
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        isExpanded, hasExpandableIcon, buttonProps, 
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        onExpand } = _a, props = __rest(_a, ["icon", "title", "srText", "children", "className", "isActive", "ouiaId", "groupId", "id", "isExpanded", "hasExpandableIcon", "buttonProps", "onExpand"]);
        const { expandedState } = this.state;
        return (_jsx(SSRSafeIds, { prefix: "pf-nav-expandable-", ouiaComponentType: "NavExpandable", children: (generatedId, generatedOuiaId) => {
                const navId = id || generatedId;
                return (_jsx(NavContext.Consumer, { children: (context) => (_jsxs("li", Object.assign({ className: css(styles.navItem, expandedState && styles.modifiers.expanded, isActive && styles.modifiers.current, className) }, getOUIAProps(NavExpandable.displayName, ouiaId !== undefined ? ouiaId : generatedOuiaId), props, { children: [_jsx(PageSidebarContext.Consumer, { children: ({ isSidebarOpen }) => (_jsxs("button", Object.assign({ className: css(styles.navLink), id: srText ? null : navId, onClick: (event) => this.onExpand(event, context.onToggle), "aria-expanded": expandedState, tabIndex: isSidebarOpen ? null : -1 }, buttonProps, { children: [icon && _jsx("span", { className: css(styles.navLinkIcon), children: icon }), hasExpandableIcon && (_jsx("span", { className: css(styles.navLinkExpandableIcon), children: _jsx(RhUiEllipsisHorizontalFillIcon, {}) })), _jsx("span", { className: css(styles.navLinkText), children: title }), _jsx("span", { className: css(styles.navToggle), children: _jsx("span", { className: css(styles.navToggleIcon), children: _jsx(RhMicronsCaretDownIcon, {}) }) })] }))) }), _jsxs("section", Object.assign({ className: css(styles.navSubnav), "aria-labelledby": navId, hidden: expandedState ? null : true }, (!expandedState && { inert: IS_INERT }), { children: [srText && (_jsx("h2", { className: "pf-v6-screen-reader", id: navId, children: srText })), _jsx("ul", { className: css(styles.navList), role: "list", children: children })] }))] }))) }));
            } }));
    }
}
NavExpandable.displayName = 'NavExpandable';
NavExpandable.defaultProps = {
    srText: '',
    isExpanded: false,
    children: '',
    className: '',
    groupId: null,
    isActive: false,
    id: '',
    hasExpandableIcon: false
};
export { NavExpandable };
//# sourceMappingURL=NavExpandable.js.map