import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { Component } from 'react';
import styles from '@patternfly/react-styles/css/components/Progress/progress.mjs';
import { css } from '@patternfly/react-styles';
import { ProgressContainer, ProgressMeasureLocation } from './ProgressContainer';
import { GenerateId, getOUIAProps } from '../../helpers';
export var ProgressSize;
(function (ProgressSize) {
    ProgressSize["sm"] = "sm";
    ProgressSize["md"] = "md";
    ProgressSize["lg"] = "lg";
})(ProgressSize || (ProgressSize = {}));
class Progress extends Component {
    render() {
        const _a = this.props, { id: idProp, size, className, value, title, label, variant, measureLocation, min, max, valueText, isTitleTruncated, tooltipPosition, 'aria-label': ariaLabel, 'aria-labelledby': ariaLabelledBy, 'aria-describedby': ariaDescribedBy, helperText, hideStatusIcon, ouiaId, ouiaSafe } = _a, props = __rest(_a, ["id", "size", "className", "value", "title", "label", "variant", "measureLocation", "min", "max", "valueText", "isTitleTruncated", "tooltipPosition", 'aria-label', 'aria-labelledby', 'aria-describedby', "helperText", "hideStatusIcon", "ouiaId", "ouiaSafe"]);
        if (!title && !ariaLabelledBy && !ariaLabel) {
            /* eslint-disable no-console */
            console.warn('One of aria-label or aria-labelledby properties should be passed when using the progress component without a title.');
        }
        return (_jsx(GenerateId, { prefix: "pf-progress-", children: (generatedId) => {
                const id = idProp || generatedId;
                const progressBarAriaProps = {
                    'aria-valuemin': min,
                    'aria-valuenow': value,
                    'aria-valuemax': max
                };
                if (title || ariaLabelledBy) {
                    progressBarAriaProps['aria-labelledby'] = title ? `${id}-description` : ariaLabelledBy;
                }
                if (ariaLabel) {
                    progressBarAriaProps['aria-label'] = ariaLabel;
                }
                if (ariaDescribedBy) {
                    progressBarAriaProps['aria-describedby'] = ariaDescribedBy;
                }
                if (valueText) {
                    progressBarAriaProps['aria-valuetext'] = valueText;
                }
                const scaledValue = Math.min(100, Math.max(0, Math.floor(((value - min) / (max - min)) * 100))) || 0;
                return (_jsx("div", Object.assign({}, props, { className: css(styles.progress, styles.modifiers[variant], ['inside', 'outside'].includes(measureLocation) &&
                        styles.modifiers[measureLocation], measureLocation === 'inside'
                        ? styles.modifiers[ProgressSize.lg]
                        : styles.modifiers[size], !title && styles.modifiers.singleline, className), id: id }, getOUIAProps(Progress.displayName, ouiaId, ouiaSafe), { children: _jsx(ProgressContainer, { parentId: id, value: scaledValue, title: title, label: label, variant: variant, measureLocation: measureLocation, progressBarAriaProps: progressBarAriaProps, isTitleTruncated: isTitleTruncated, tooltipPosition: tooltipPosition, helperText: helperText, hideStatusIcon: hideStatusIcon }) })));
            } }));
    }
}
Progress.displayName = 'Progress';
Progress.defaultProps = {
    className: '',
    measureLocation: ProgressMeasureLocation.top,
    variant: null,
    id: '',
    title: '',
    min: 0,
    max: 100,
    size: null,
    label: null,
    value: 0,
    valueText: null,
    isTitleTruncated: false,
    tooltipPosition: 'top',
    'aria-label': null,
    'aria-labelledby': null,
    'aria-describedby': null,
    ouiaSafe: true
};
export { Progress };
//# sourceMappingURL=Progress.js.map