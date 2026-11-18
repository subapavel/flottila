/// <reference types="react" />
export interface BackdropProps extends React.HTMLProps<HTMLDivElement> {
    /** Content rendered inside the backdrop */
    children?: React.ReactNode;
    /** Additional classes added to the backdrop */
    className?: string;
    /** Flag indicating whether animations are enabled. Animations are enabled by default. */
    hasAnimations?: boolean;
    /** Flag to show the backdrop when animations are enabled. Set to false while the backdrop remains mounted to play its exit transition. */
    isVisible?: boolean;
}
export declare const Backdrop: React.FunctionComponent<BackdropProps>;
//# sourceMappingURL=Backdrop.d.ts.map