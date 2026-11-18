import { Component } from 'react';
import { PickOptional } from '../../helpers/typeUtils';
export interface OverflowMenuProps extends React.HTMLProps<HTMLDivElement> {
    /** Any elements that can be rendered in the menu */
    children?: any;
    /** Additional classes added to the OverflowMenu. */
    className?: string;
    /** Indicates breakpoint at which to switch between expanded and collapsed states. The "sm" breakpoint does not apply to vertical overflow menus. */
    breakpoint: 'sm' | 'md' | 'lg' | 'xl' | '2xl';
    /** A container reference to base the specified breakpoint on instead of the viewport width. */
    breakpointReference?: HTMLElement | (() => HTMLElement) | React.RefObject<any>;
    /** Indicates the overflow menu orientation is vertical and should respond to height changes instead of width. */
    isVertical?: boolean;
}
export interface OverflowMenuState extends React.HTMLProps<HTMLDivElement> {
    isBelowBreakpoint: boolean;
    breakpointRef: HTMLElement;
}
declare class OverflowMenu extends Component<OverflowMenuProps, OverflowMenuState> {
    static displayName: string;
    static defaultProps: PickOptional<OverflowMenuProps>;
    constructor(props: OverflowMenuProps);
    observer: any;
    getBreakpointRef(): any;
    componentDidMount(): void;
    componentDidUpdate(prevProps: Readonly<OverflowMenuProps>, prevState: Readonly<OverflowMenuState>): void;
    componentWillUnmount(): void;
    handleResize: () => void;
    handleResizeWidth: () => void;
    handleResizeHeight: () => void;
    handleResizeWithDelay: (...args: any[]) => void;
    render(): import("react/jsx-runtime").JSX.Element;
}
export { OverflowMenu };
//# sourceMappingURL=OverflowMenu.d.ts.map