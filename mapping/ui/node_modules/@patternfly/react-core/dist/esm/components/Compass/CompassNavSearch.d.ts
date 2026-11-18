/// <reference types="react" />
export interface CompassNavSearchProps extends Omit<React.HTMLProps<HTMLDivElement>, 'onClick'> {
    /** Content to display in the tooltip. Defaults to "Search". */
    tooltipContent?: React.ReactNode;
    /** Click handler for the search button. */
    onClick?: React.MouseEventHandler<HTMLButtonElement>;
    /** Additional classes added to the nav search wrapper. */
    className?: string;
    /** Accessible label for the nav search. */
    'aria-label'?: string;
}
export declare const CompassNavSearch: React.FunctionComponent<CompassNavSearchProps>;
//# sourceMappingURL=CompassNavSearch.d.ts.map