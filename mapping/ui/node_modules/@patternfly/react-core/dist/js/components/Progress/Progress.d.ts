import { Component } from 'react';
import { OUIAProps } from '../../helpers';
export declare enum ProgressSize {
    sm = "sm",
    md = "md",
    lg = "lg"
}
export interface ProgressProps extends Omit<React.HTMLProps<HTMLDivElement>, 'size' | 'label' | 'title'>, OUIAProps {
    /** Classname for progress component. */
    className?: string;
    /** Size variant of progress. */
    size?: 'sm' | 'md' | 'lg';
    /** Where the measure percent will be located. */
    measureLocation?: 'outside' | 'inside' | 'top' | 'none';
    /** Status variant of progress. */
    variant?: 'danger' | 'success' | 'warning';
    /** Title above progress. The isTitleTruncated property will only affect string titles. Node title truncation must be handled manually. */
    title?: React.ReactNode;
    /** Text description of current progress value to display instead of percentage. */
    label?: React.ReactNode;
    /** Actual value of progress. */
    value?: number;
    /** DOM id for progress component. */
    id?: string;
    /** Minimal value of progress. */
    min?: number;
    /** Maximum value of progress. */
    max?: number;
    /** Accessible text description of current progress value, for when value is not a percentage. Use with label. */
    valueText?: string;
    /** Indicate whether to truncate the string title */
    isTitleTruncated?: boolean;
    /** Position of the tooltip which is displayed if title is truncated */
    tooltipPosition?: 'auto' | 'top' | 'bottom' | 'left' | 'right';
    /** Adds accessible text to the ProgressBar. Required when title not used and there is not any label associated with the progress bar */
    'aria-label'?: string;
    /** Associates the ProgressBar with it's label for accessibility purposes. Required when title not used */
    'aria-labelledby'?: string;
    /** Adds an accessible description to the ProgressBar via space separated list of ids. Required when helperText is passed in. */
    'aria-describedby'?: string;
    /** Content which can be used to convey additional information about the progress component.
     * We recommend the helper text component as it was designed for this purpose.
     */
    helperText?: React.ReactNode;
    /** Flag indicating whether the status icon should be hidden, helpful when space is limited (such as within table cells). When set to true, you must ensure the context of the status is provided in another way, such as via the progress measure. */
    hideStatusIcon?: boolean;
    /** Value to overwrite the randomly generated data-ouia-component-id.*/
    ouiaId?: number | string;
    /** Set the value of data-ouia-safe. Only set to true when the component is in a static state, i.e. no animations are occurring. At all other times, this value must be false. */
    ouiaSafe?: boolean;
}
declare class Progress extends Component<ProgressProps> {
    static displayName: string;
    static defaultProps: ProgressProps;
    render(): import("react/jsx-runtime").JSX.Element;
}
export { Progress };
//# sourceMappingURL=Progress.d.ts.map