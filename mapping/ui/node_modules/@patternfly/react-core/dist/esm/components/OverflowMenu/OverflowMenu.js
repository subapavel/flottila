import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { Component } from 'react';
import styles from '@patternfly/react-styles/css/components/OverflowMenu/overflow-menu.mjs';
import { css } from '@patternfly/react-styles';
import { OverflowMenuContext } from './OverflowMenuContext';
import { debounce } from '../../helpers/util';
import { globalWidthBreakpoints, globalHeightBreakpoints } from '../../helpers/constants';
import { getResizeObserver } from '../../helpers/resizeObserver';
class OverflowMenu extends Component {
    constructor(props) {
        super(props);
        this.observer = () => { };
        this.handleResize = () => {
            const { isVertical } = this.props;
            if (isVertical) {
                this.handleResizeHeight();
            }
            else {
                this.handleResizeWidth();
            }
        };
        this.handleResizeWidth = () => {
            const breakpointWidth = globalWidthBreakpoints[this.props.breakpoint];
            if (!breakpointWidth) {
                // eslint-disable-next-line no-console
                console.error('OverflowMenu will not be visible without a valid breakpoint.');
                return;
            }
            const relativeWidth = this.state.breakpointRef ? this.state.breakpointRef.clientWidth : window.innerWidth;
            const isBelowBreakpoint = relativeWidth < breakpointWidth;
            if (this.state.isBelowBreakpoint !== isBelowBreakpoint) {
                this.setState({ isBelowBreakpoint });
            }
        };
        this.handleResizeHeight = () => {
            const breakpointHeight = globalHeightBreakpoints[this.props.breakpoint];
            if (breakpointHeight === 0) {
                // eslint-disable-next-line no-console
                console.warn('The "sm" breakpoint does not apply to vertical overflow menus.');
                return;
            }
            if (!breakpointHeight) {
                // eslint-disable-next-line no-console
                console.error('OverflowMenu will not be visible without a valid breakpoint.');
                return;
            }
            const relativeHeight = this.state.breakpointRef ? this.state.breakpointRef.clientHeight : window.innerHeight;
            const isBelowBreakpoint = relativeHeight < breakpointHeight;
            if (this.state.isBelowBreakpoint !== isBelowBreakpoint) {
                this.setState({ isBelowBreakpoint });
            }
        };
        this.handleResizeWithDelay = debounce(this.handleResize, 250);
        this.state = {
            isBelowBreakpoint: false,
            breakpointRef: undefined
        };
    }
    getBreakpointRef() {
        const { breakpointReference } = this.props;
        if (breakpointReference.current) {
            return breakpointReference.current;
        }
        else if (typeof breakpointReference === 'function') {
            return breakpointReference();
        }
    }
    componentDidMount() {
        const reference = this.props.breakpointReference ? this.getBreakpointRef() : undefined;
        this.setState({ breakpointRef: reference });
        this.observer = getResizeObserver(reference, this.handleResizeWithDelay);
        this.handleResize();
    }
    componentDidUpdate(prevProps, prevState) {
        const reference = this.props.breakpointReference ? this.getBreakpointRef() : undefined;
        if (prevState.breakpointRef !== reference) {
            // To remove any previous observer/event listener from componentDidMount before adding a new one
            this.observer();
            this.setState({ breakpointRef: reference });
            this.observer = getResizeObserver(reference, this.handleResizeWithDelay);
            this.handleResize();
        }
    }
    componentWillUnmount() {
        this.observer();
    }
    render() {
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        const _a = this.props, { className, breakpoint, children, breakpointReference, isVertical } = _a, props = __rest(_a, ["className", "breakpoint", "children", "breakpointReference", "isVertical"]);
        return (_jsx("div", Object.assign({}, props, { className: css(styles.overflowMenu, isVertical && styles.modifiers.vertical, className), children: _jsx(OverflowMenuContext.Provider, { value: { isBelowBreakpoint: this.state.isBelowBreakpoint }, children: children }) })));
    }
}
OverflowMenu.displayName = 'OverflowMenu';
OverflowMenu.defaultProps = {
    isVertical: false
};
OverflowMenu.contextType = OverflowMenuContext;
export { OverflowMenu };
//# sourceMappingURL=OverflowMenu.js.map