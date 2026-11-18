/// <reference types="react" />
export interface CompassMessageBarProps extends React.HTMLProps<HTMLDivElement> {
    /** Content of the message bar. Typically a @patternfly/chatbot MessageBar component. */
    children?: React.ReactNode;
    /** Additional classes added to the message bar */
    className?: string;
}
export declare const CompassMessageBar: React.FunctionComponent<CompassMessageBarProps>;
//# sourceMappingURL=CompassMessageBar.d.ts.map