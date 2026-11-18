import { Component } from 'react';
import { getBreakpoint } from '../../helpers/util';
import { OUIAProps } from '../../helpers';
export declare enum ToolbarColorVariant {
    default = "default",
    primary = "primary",
    secondary = "secondary",
    noBackground = "no-background"
}
export interface ToolbarProps extends React.HTMLProps<HTMLDivElement>, OUIAProps {
    /** Optional callback for clearing all filters in the toolbar */
    clearAllFilters?: () => void;
    /** Text to display in the clear all filters button */
    clearFiltersButtonText?: string;
    /** Custom content appended to the filter generated label group. To maintain spacing and styling, each node should be wrapped in a ToolbarItem or ToolbarGroup. This property will remove the default "Clear all filters" button. */
    customLabelGroupContent?: React.ReactNode;
    /** The breakpoint at which the listed filters in label groups are collapsed down to a summary */
    collapseListedFiltersBreakpoint?: 'all' | 'md' | 'lg' | 'xl' | '2xl';
    /** Flag indicating if a data toolbar toggle group's expandable content is expanded */
    isExpanded?: boolean;
    /** A callback for setting the isExpanded flag */
    toggleIsExpanded?: () => void;
    /** Classes applied to root element of the data toolbar */
    className?: string;
    /** Content to be rendered as rows in the data toolbar */
    children?: React.ReactNode;
    /** Id of the data toolbar */
    id?: string;
    /** Flag indicating if the toolbar is a container for CSS container queries */
    isContainer?: boolean;
    /** Flag indicating the toolbar height should expand to the full height of the container */
    isFullHeight?: boolean;
    /** Flag indicating the toolbar is static */
    isStatic?: boolean;
    /** Flag indicating the toolbar should stick to the top of its container. This property applies both the sticky position and styling. */
    isSticky?: boolean;
    /** @beta Flag indicating the toolbar should have sticky positioning to the top of its container */
    isStickyBase?: boolean;
    /** @beta Flag indicating the toolbar should have stuck styling, when the toolbar is not at the top of the scroll container */
    isStickyStuck?: boolean;
    /** @beta Flag indicating the toolbar has a vertical orientation */
    isVertical?: boolean;
    /** Insets at various breakpoints. */
    inset?: {
        default?: 'insetNone' | 'insetSm' | 'insetMd' | 'insetLg' | 'insetXl' | 'inset2xl';
        sm?: 'insetNone' | 'insetSm' | 'insetMd' | 'insetLg' | 'insetXl' | 'inset2xl';
        md?: 'insetNone' | 'insetSm' | 'insetMd' | 'insetLg' | 'insetXl' | 'inset2xl';
        lg?: 'insetNone' | 'insetSm' | 'insetMd' | 'insetLg' | 'insetXl' | 'inset2xl';
        xl?: 'insetNone' | 'insetSm' | 'insetMd' | 'insetLg' | 'insetXl' | 'inset2xl';
        '2xl'?: 'insetNone' | 'insetSm' | 'insetMd' | 'insetLg' | 'insetXl' | 'inset2xl';
    };
    /** Text to display in the total number of applied filters ToolbarFilter */
    numberOfFiltersText?: (numberOfFilters: number) => string;
    /** Value to overwrite the randomly generated data-ouia-component-id.*/
    ouiaId?: number | string;
    /** Set the value of data-ouia-safe. Only set to true when the component is in a static state, i.e. no animations are occurring. At all other times, this value must be false. */
    ouiaSafe?: boolean;
    /** Background color variant of the toolbar */
    colorVariant?: ToolbarColorVariant | 'default' | 'no-background' | 'primary' | 'secondary';
    /** Flag indicating the toolbar padding is removed */
    hasNoPadding?: boolean;
}
export interface ToolbarState {
    /** Flag used if the user has opted NOT to manage the 'isExpanded' state of the toggle group.
     *  Indicates whether or not the toggle group is expanded. */
    isManagedToggleExpanded: boolean;
    /** Object managing information about how many labels are in each label group */
    filterInfo: FilterInfo;
    /** Used to keep track of window width so we can collapse expanded content when window is resizing */
    windowWidth: number;
}
interface FilterInfo {
    [key: string]: number;
}
declare class Toolbar extends Component<ToolbarProps, ToolbarState> {
    static displayName: string;
    labelGroupContentRef: import("react").RefObject<HTMLDivElement>;
    toolbarRef: import("react").RefObject<HTMLDivElement>;
    resizeObserver: () => void;
    containerBreakpoint: ReturnType<typeof getBreakpoint>;
    staticFilterInfo: {};
    hasNoPadding: boolean;
    state: {
        isManagedToggleExpanded: boolean;
        filterInfo: {};
        windowWidth: number;
    };
    isToggleManaged: () => boolean;
    toggleIsExpanded: () => void;
    closeExpandableContent: (e?: any) => void;
    closeExpandableContentOnContainerResize: () => void;
    setupResizeHandling: () => void;
    cleanupResizeHandling: () => void;
    componentDidMount(): void;
    componentDidUpdate(prevProps: ToolbarProps): void;
    componentWillUnmount(): void;
    updateNumberFilters: (categoryName: string, numberOfFilters: number) => void;
    getNumberOfFilters: () => number;
    renderToolbar: (randomId: string, generatedOuiaId?: string) => import("react/jsx-runtime").JSX.Element;
    render(): import("react/jsx-runtime").JSX.Element;
}
export { Toolbar };
//# sourceMappingURL=Toolbar.d.ts.map