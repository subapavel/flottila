/// <reference types="react" />
import { ValidatedOptions } from '../../helpers/constants';
export interface TextInputGroupProps extends React.HTMLProps<HTMLDivElement> {
    /** Content rendered inside the text input group */
    children?: React.ReactNode;
    /** Additional classes applied to the text input group container */
    className?: string;
    /** Adds disabled styling and a disabled context value which text input group main hooks into for the input itself */
    isDisabled?: boolean;
    /** Flag to indicate the toggle has no border or background */
    isPlain?: boolean;
    /** Value to indicate if the text input group is modified to show that validation state.
     * If set to success, warning, or error, the group will show that state.
     * If set to default, no validation styling is applied (use to clear a prior validation state).
     */
    validated?: 'success' | 'warning' | 'error' | 'default' | ValidatedOptions;
    /** @hide A reference object to attach to the input box */
    innerRef?: React.RefObject<any>;
}
export declare const TextInputGroupContext: import("react").Context<Partial<TextInputGroupProps>>;
export declare const TextInputGroup: React.FunctionComponent<TextInputGroupProps>;
//# sourceMappingURL=TextInputGroup.d.ts.map