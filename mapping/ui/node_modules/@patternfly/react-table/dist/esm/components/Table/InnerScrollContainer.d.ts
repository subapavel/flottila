/// <reference types="react" />
export interface InnerScrollContainerProps extends React.HTMLProps<HTMLDivElement> {
    /** Content rendered inside the inner scroll container */
    children?: React.ReactNode;
    /** Additional classes added to the container */
    className?: string;
    /** @hide Forwarded ref */
    innerRef?: React.Ref<HTMLDivElement>;
}
export declare const InnerScrollContainer: import("react").ForwardRefExoticComponent<Omit<InnerScrollContainerProps, "ref"> & import("react").RefAttributes<HTMLDivElement>>;
//# sourceMappingURL=InnerScrollContainer.d.ts.map