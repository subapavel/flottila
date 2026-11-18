/// <reference types="react" />
export interface DataListActionProps extends Omit<React.HTMLProps<HTMLDivElement>, 'children'> {
    /** Content rendered as data list action  (e.g <Button> or <Dropdown>) */
    children: React.ReactNode;
    /** Additional classes added to the data list action */
    className?: string;
    /** Identify the data list toggle number */
    id: string;
    /** Adds accessible text to the data list action */
    'aria-labelledby': string;
    /** Adds accessible text to the data list action */
    'aria-label': string;
    /** What breakpoints to hide/show the data list action */
    visibility?: {
        default?: 'hidden' | 'visible';
        sm?: 'hidden' | 'visible';
        md?: 'hidden' | 'visible';
        lg?: 'hidden' | 'visible';
        xl?: 'hidden' | 'visible';
        '2xl'?: 'hidden' | 'visible';
    };
}
export declare const DataListAction: React.FunctionComponent<DataListActionProps>;
//# sourceMappingURL=DataListAction.d.ts.map