/// <reference types="react" />
export declare enum DataListWrapModifier {
    nowrap = "nowrap",
    truncate = "truncate",
    breakWord = "breakWord"
}
export interface DataListProps extends React.HTMLProps<HTMLUListElement> {
    /** Content rendered inside the data list list */
    children?: React.ReactNode;
    /** Additional classes added to the data list list */
    className?: string;
    /** Adds accessible text to the data list list */
    'aria-label': string;
    /** Optional callback to make data list selectable, fired when data list item selected */
    onSelectDataListItem?: (event: React.MouseEvent | React.KeyboardEvent, id: string) => void;
    /** Id of data list item currently selected */
    selectedDataListItemId?: string;
    /** Flag indicating if data list should have compact styling */
    isCompact?: boolean;
    /** @beta Flag indicating if data list should have plain styling with a transparent background */
    isPlain?: boolean;
    /** @beta Flag to prevent the data list from automatically applying plain styling when glass theme is enabled. */
    isNoPlainOnGlass?: boolean;
    /** Specifies the grid breakpoints  */
    gridBreakpoint?: 'none' | 'always' | 'sm' | 'md' | 'lg' | 'xl' | '2xl';
    /** Determines which wrapping modifier to apply to the data list */
    wrapModifier?: DataListWrapModifier | 'nowrap' | 'truncate' | 'breakWord';
    /** Object that causes the data list to render hidden inputs which improve selectable item a11y */
    onSelectableRowChange?: (event: React.FormEvent<HTMLInputElement>, id: string) => void;
    /** @hide custom ref of the data list */
    innerRef?: React.RefObject<HTMLUListElement | null>;
}
interface DataListContextProps {
    isSelectable: boolean;
    selectedDataListItemId: string;
    updateSelectedDataListItem: (event: React.MouseEvent | React.KeyboardEvent, id: string) => void;
    onSelectableRowChange?: (event: React.FormEvent<HTMLInputElement>, id: string) => void;
}
export declare const DataListContext: import("react").Context<Partial<DataListContextProps>>;
export declare const DataListBase: React.FunctionComponent<DataListProps>;
export declare const DataList: import("react").ForwardRefExoticComponent<Omit<DataListProps, "ref"> & import("react").RefAttributes<HTMLUListElement>>;
export {};
//# sourceMappingURL=DataList.d.ts.map