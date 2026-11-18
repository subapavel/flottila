import { Component } from 'react';
import { PickOptional } from '../../helpers/typeUtils';
import { OUIAProps } from '../../helpers';
export declare enum ExpandableSectionVariant {
    default = "default",
    truncate = "truncate"
}
/** The main expandable section component. */
export interface ExpandableSectionProps extends Omit<React.HTMLProps<HTMLDivElement>, 'onToggle'>, OUIAProps {
    /** Content rendered inside the expandable section. */
    children?: React.ReactNode;
    /** Additional classes added to the expandable section. */
    className?: string;
    /** Id of the content of the expandable section. When passing in the isDetached property, this
     * property's value should match the contentId property of the expandable section toggle sub-component.
     */
    contentId?: string;
    /** Id of the toggle of the expandable section, which provides an accessible name to the
     * expandable section content via the aria-labelledby attribute. When the isDetached property
     * is also passed in, the value of this property must match the toggleId property of the
     * expandable section toggle sub-component.
     */
    toggleId?: string;
    /** Display size variant. Set to "lg" for disclosure styling. */
    displaySize?: 'default' | 'lg';
    /** Flag indicating that the expandable section and expandable toggle are detached from one another. */
    isDetached?: boolean;
    /** Flag to indicate if the content is expanded. */
    isExpanded?: boolean;
    /** Flag to indicate if the content is indented. */
    isIndented?: boolean;
    /** Flag to indicate the width of the component is limited. Set to "true" for disclosure styling. */
    isWidthLimited?: boolean;
    /** Callback function to toggle the expandable section. Detached expandable sections should
     * use the onToggle property of the expandable section toggle sub-component.
     */
    onToggle?: (event: React.MouseEvent, isExpanded: boolean) => void;
    /** React node that appears in the attached toggle in place of the toggleText property.
     * Can also be a function that receives the expanded state and returns a React node.
     */
    toggleContent?: React.ReactNode | ((isExpanded: boolean) => React.ReactNode);
    /** Text that appears in the attached toggle. */
    toggleText?: string;
    /** Text that appears in the attached toggle when collapsed (will override toggleText if
     * both are specified; used for uncontrolled expandable with dynamic toggle text).
     */
    toggleTextCollapsed?: string;
    /** Text that appears in the attached toggle when expanded (will override toggleText if
     * both are specified; used for uncontrolled expandable with dynamic toggle text).
     */
    toggleTextExpanded?: string;
    /** Accessible name via human readable string for the expandable section toggle. */
    toggleAriaLabel?: string;
    /** Accessible name via space delimtted list of IDs for the expandable section toggle. */
    toggleAriaLabelledBy?: string;
    /** Icon shown in toggle when variant is not truncated. */
    toggleIcon?: React.ReactNode;
    /** Whether to show a toggle icon when variant is not truncated. If omitted, it is important to ensure the current state of the ExpandableSection is conveyed, most likely by having dynamic toggle text. */
    hasToggleIcon?: boolean;
    /** Truncates the expandable content to the specified number of lines when using the
     * "truncate" variant.
     */
    truncateMaxLines?: number;
    /** Determines the variant of the expandable section. When passing in "truncate" as the
     * variant, the expandable content will be truncated after 3 lines by default.
     */
    variant?: 'default' | 'truncate';
    /** Sets the direction of the expandable animation when isDetached is true. If this prop is not passed,
     * animation will not occur.
     */
    direction?: 'up' | 'down';
    /** The HTML element to use for the toggle wrapper. Can be 'div' (default) or any heading level.
     * When using heading elements, the button will be rendered inside the heading for proper semantics.
     * This is useful when the toggle text should function as a heading in the document structure.
     */
    toggleWrapper?: 'div' | 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6';
    /** Value to overwrite the randomly generated data-ouia-component-id.*/
    ouiaId?: number | string;
    /** Set the value of data-ouia-safe. Only set to true when the component is in a static state, i.e. no animations are occurring. At all other times, this value must be false. */
    ouiaSafe?: boolean;
}
interface ExpandableSectionState {
    isExpanded: boolean;
    hasToggle: boolean;
    previousWidth: number;
}
declare class ExpandableSection extends Component<ExpandableSectionProps, ExpandableSectionState> {
    static displayName: string;
    constructor(props: ExpandableSectionProps);
    expandableContentRef: import("react").RefObject<HTMLDivElement>;
    observer: any;
    static defaultProps: PickOptional<ExpandableSectionProps>;
    private calculateToggleText;
    componentDidMount(): void;
    componentDidUpdate(prevProps: ExpandableSectionProps): void;
    componentWillUnmount(): void;
    checkToggleVisibility: () => void;
    resize: () => void;
    handleResize: (...args: any[]) => void;
    render(): import("react/jsx-runtime").JSX.Element;
}
export { ExpandableSection };
//# sourceMappingURL=ExpandableSection.d.ts.map