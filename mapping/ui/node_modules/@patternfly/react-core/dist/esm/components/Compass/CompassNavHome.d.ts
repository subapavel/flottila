/// <reference types="react" />
export interface CompassNavHomeProps extends Omit<React.HTMLProps<HTMLDivElement>, 'onClick'> {
    /** Content to display in the tooltip. Defaults to "Home". */
    tooltipContent?: React.ReactNode;
    /** Click handler for the home button. */
    onClick?: React.MouseEventHandler<HTMLButtonElement>;
    /** Additional classes added to the nav home wrapper. */
    className?: string;
    /** Accessible label for the nav home. */
    'aria-label'?: string;
}
export declare const CompassNavHome: React.FunctionComponent<CompassNavHomeProps>;
//# sourceMappingURL=CompassNavHome.d.ts.map