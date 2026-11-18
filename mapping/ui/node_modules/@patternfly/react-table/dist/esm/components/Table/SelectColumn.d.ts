/// <reference types="react" />
import { TooltipProps } from '@patternfly/react-core/dist/esm/components/Tooltip';
export declare enum RowSelectVariant {
    radio = "radio",
    checkbox = "checkbox"
}
export interface SelectColumnProps {
    children?: React.ReactNode;
    className?: string;
    onSelect?: (event: React.FormEvent<HTMLInputElement>) => void;
    selectVariant?: RowSelectVariant;
    /** text to display on the tooltip */
    tooltip?: React.ReactNode;
    /** other props to pass to the tooltip */
    tooltipProps?: Omit<TooltipProps, 'content'>;
    /** id for the input element - required by Checkbox and Radio components */
    id?: string;
    /** name for the input element - required by Radio component */
    name?: string;
    /** Whether the checkbox should be in an indeterminate state */
    isIndeterminate?: boolean;
}
export declare const SelectColumn: React.FunctionComponent<SelectColumnProps>;
//# sourceMappingURL=SelectColumn.d.ts.map