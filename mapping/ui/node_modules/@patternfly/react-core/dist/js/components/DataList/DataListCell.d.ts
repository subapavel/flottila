import { FunctionComponent } from 'react';
import { DataListWrapModifier } from './DataList';
export interface DataListCellProps extends Omit<React.HTMLProps<HTMLDivElement>, 'width'> {
    /** Content rendered inside the data list cell */
    children?: React.ReactNode;
    /** Additional classes added to the data list cell */
    className?: string;
    /** Width (from 1-5) to the data list cell */
    width?: 1 | 2 | 3 | 4 | 5;
    /** Enables the body content to fill the height of the card */
    isFilled?: boolean;
    /**  Aligns the cell content to the right of its parent. */
    alignRight?: boolean;
    /** Set to true if the cell content is an icon */
    isIcon?: boolean;
    /** Determines which wrapping modifier to apply to the data list cell */
    wrapModifier?: DataListWrapModifier | 'nowrap' | 'truncate' | 'breakWord';
}
export declare const DataListCell: FunctionComponent<DataListCellProps>;
//# sourceMappingURL=DataListCell.d.ts.map