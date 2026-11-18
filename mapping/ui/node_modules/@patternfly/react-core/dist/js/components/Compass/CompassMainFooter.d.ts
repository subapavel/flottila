/// <reference types="react" />
interface CompassMainFooterProps extends Omit<React.HTMLProps<HTMLDivElement>, 'title'> {
    /** Additional classes added to the main footer */
    className?: string;
    /** Main footer content */
    children?: React.ReactNode;
    /** Indicates if the main footer is expanded */
    isExpanded?: boolean;
}
export declare const CompassMainFooter: React.FunctionComponent<CompassMainFooterProps>;
export {};
//# sourceMappingURL=CompassMainFooter.d.ts.map