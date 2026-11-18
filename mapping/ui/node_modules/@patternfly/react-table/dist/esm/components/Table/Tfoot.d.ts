/// <reference types="react" />
export interface TfootProps extends React.HTMLProps<HTMLTableSectionElement> {
    /** Content rendered inside the <tfoot> row group */
    children?: React.ReactNode;
    /** Additional classes added to the <tfoot> element */
    className?: string;
    /** @hide Forwarded ref */
    innerRef?: React.Ref<any>;
}
export declare const Tfoot: import("react").ForwardRefExoticComponent<Omit<TfootProps, "ref"> & import("react").RefAttributes<HTMLTableSectionElement>>;
//# sourceMappingURL=Tfoot.d.ts.map