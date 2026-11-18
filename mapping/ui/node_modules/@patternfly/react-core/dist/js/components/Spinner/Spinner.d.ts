/// <reference types="react" />
export declare enum spinnerSize {
    sm = "sm",
    md = "md",
    lg = "lg",
    xl = "xl"
}
export interface SpinnerProps extends React.SVGProps<SVGSVGElement> {
    /** Additional classes added to the Spinner. */
    className?: string;
    /** Size variant of progress. */
    size?: 'sm' | 'md' | 'lg' | 'xl';
    /** Text describing that current loading status or progress */
    'aria-valuetext'?: string;
    /** Diameter of spinner set as CSS variable */
    diameter?: string;
    /** Indicates the spinner is inline and the size should inherit the text font size. This will override the size prop. */
    isInline?: boolean;
    /** Accessible label to describe what is loading */
    'aria-label'?: string;
    /** ID of the element(s) that provide an accessible name for the spinner. This prop is deprecated and you should instead use the aria-labelledby prop (with a lowercase "b") instead. */
    'aria-labelledBy'?: string;
    /** ID of the element(s) that provide an accessible name for the spinner. */
    'aria-labelledby'?: string;
}
export declare const Spinner: React.FunctionComponent<SpinnerProps>;
//# sourceMappingURL=Spinner.d.ts.map