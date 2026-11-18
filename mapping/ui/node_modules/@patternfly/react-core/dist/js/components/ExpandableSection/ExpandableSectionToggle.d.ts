/// <reference types="react" />
/** Acts as the toggle sub-component when the main expandable section component has the isDetached
 * property passed in. Allows for more custom control over the expandable section's toggle.
 */
export interface ExpandableSectionToggleProps extends Omit<React.HTMLProps<HTMLDivElement>, 'onToggle'> {
    /** Content rendered inside the expandable toggle. */
    children?: React.ReactNode;
    /** Additional classes added to the expandable toggle. */
    className?: string;
    /** Id of the toggle's respective expandable section content. The value passed into this
     * property should match the contentId property of the main expandable section component.
     */
    contentId?: string;
    /** Id of the toggle. The value passed into this property should match the aria-labelledby
     * property of the main expandable section component.
     */
    toggleId?: string;
    /** Direction the toggle arrow should point when the expandable section is expanded. */
    direction?: 'up' | 'down';
    /** Flag to determine toggle styling when the expandable content is truncated. */
    hasTruncatedContent?: boolean;
    /** Flag indicating if the expandable section is expanded. */
    isExpanded?: boolean;
    /** Callback function to toggle the expandable content. */
    onToggle?: (isExpanded: boolean) => void;
    /** Flag indicating that the expandable section and expandable toggle are detached from one another. */
    isDetached?: boolean;
    /** Accessible name via human readable string for the expandable section toggle. */
    toggleAriaLabel?: string;
    /** Accessible name via space delimtted list of IDs for the expandable section toggle. */
    toggleAriaLabelledBy?: string;
    /** The HTML element to use for the toggle wrapper. Can be 'div' (default) or any heading level.
     * When using heading elements, the button will be rendered inside the heading for proper semantics.
     * This is useful when the toggle text should function as a heading in the document structure.
     */
    toggleWrapper?: 'div' | 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6';
}
export declare const ExpandableSectionToggle: React.FunctionComponent<ExpandableSectionToggleProps>;
//# sourceMappingURL=ExpandableSectionToggle.d.ts.map