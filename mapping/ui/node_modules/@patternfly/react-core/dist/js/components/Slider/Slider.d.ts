/// <reference types="react" />
import { TooltipProps } from '../Tooltip';
import { OUIAProps } from '../../helpers';
/** Properties for creating custom steps in a slider. These properties should be passed in as
 * an object within an array to the slider component's customSteps property.
 */
export interface SliderStepObject {
    /** Flag to hide the label. */
    isLabelHidden?: boolean;
    /** The display label for the step value. This is also used for the aria-valuetext attribute. */
    label: string;
    /** Value of the step. This value is a percentage of the slider where the tick is drawn. */
    value: number;
}
export type SliderOnChangeEvent = React.MouseEvent | React.KeyboardEvent | React.FormEvent<HTMLInputElement> | React.TouchEvent | React.FocusEvent<HTMLInputElement>;
/** The main slider component. */
export interface SliderProps extends Omit<React.HTMLProps<HTMLDivElement>, 'onChange'>, OUIAProps {
    /** Flag indicating if the slider is discrete for custom steps. This will cause the slider
     * to snap to the closest value.
     */
    areCustomStepsContinuous?: boolean;
    /** One or more id's to use for the slider thumb's accessible description. */
    'aria-describedby'?: string;
    /** One or more id's to use for the slider thumb's accessible label. */
    'aria-labelledby'?: string;
    /** Additional classes added to the slider. */
    className?: string;
    /** Array of custom slider step objects (value and label of each step) for the slider. */
    customSteps?: SliderStepObject[];
    /** Enables a tooltip over the silder thumb. Defaults to the current value, or tooltipContent if provided. */
    hasTooltipOverThumb?: boolean;
    /** Content of the tooltip over the slider thumb. Defaults to the current value.  */
    tooltipContent?: React.ReactNode;
    /** Additional props passed to the tooltip. */
    tooltipProps?: Omit<TooltipProps, 'content'>;
    /** Accessible label for the input field. */
    inputAriaLabel?: string;
    /** Text label that is place after the input field. */
    inputLabel?: string | number;
    /** Position of the input. Note "right" is deprecated. Use "end" instead*/
    inputPosition?: 'aboveThumb' | 'right' | 'end';
    /** Value displayed in the input field. */
    inputValue?: number;
    /** Adds disabled styling, and disables the slider and the input component if present. */
    isDisabled?: boolean;
    /** Flag to show value input field. */
    isInputVisible?: boolean;
    /** Flag indicating if the input value should also update the slider value as the user types. When false, the slider will update its value on blur or enter key press. */
    isInputLive?: boolean;
    /** @deprecated Use startActions instead. Actions placed at the start of the slider. */
    leftActions?: React.ReactNode;
    /** Actions placed at the start of the slider. */
    startActions?: React.ReactNode;
    /** The maximum permitted value. */
    max?: number;
    /** The minimum permitted value. */
    min?: number;
    /** Value change callback. This is called when the slider value changes. */
    onChange?: (event: SliderOnChangeEvent, value: number, inputValue?: number, setLocalInputValue?: React.Dispatch<React.SetStateAction<number>>) => void;
    /** @deprecated Use endActions instead. Actions placed to the right of the slider. */
    rightActions?: React.ReactNode;
    /** Actions placed at the end of the slider. */
    endActions?: React.ReactNode;
    /** Flag to indicate if boundaries should be shown for slider that does not have custom steps. */
    showBoundaries?: boolean;
    /** Flag to indicate if ticks should be shown for slider that does not have custom steps. */
    showTicks?: boolean;
    /** The step interval. */
    step?: number;
    /** Accessible label for the slider thumb. */
    thumbAriaLabel?: string;
    /** Accessible text for the current value of the slider. Defaults to the current value. */
    thumbAriaValueText?: string;
    /** Current value of the slider.  */
    value?: number;
    /** Value to overwrite the randomly generated data-ouia-component-id.*/
    ouiaId?: number | string;
    /** Set the value of data-ouia-safe. Only set to true when the component is in a static state, i.e. no animations are occurring. At all other times, this value must be false. */
    ouiaSafe?: boolean;
}
export declare const Slider: React.FunctionComponent<SliderProps>;
//# sourceMappingURL=Slider.d.ts.map