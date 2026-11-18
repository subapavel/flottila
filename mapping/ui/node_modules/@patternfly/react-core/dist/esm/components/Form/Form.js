import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { forwardRef } from 'react';
import styles from '@patternfly/react-styles/css/components/Form/form.mjs';
import { css } from '@patternfly/react-styles';
import cssMaxWidth from '@patternfly/react-tokens/dist/esm/c_form_m_limit_width_MaxWidth';
import { useOUIAProps } from '../../helpers';
const FormBase = (_a) => {
    var { children = null, className = '', isHorizontal = false, isWidthLimited = false, maxWidth = '', innerRef, ouiaId, ouiaSafe = true } = _a, props = __rest(_a, ["children", "className", "isHorizontal", "isWidthLimited", "maxWidth", "innerRef", "ouiaId", "ouiaSafe"]);
    const ouiaProps = useOUIAProps(Form.displayName, ouiaId, ouiaSafe);
    return (_jsx("form", Object.assign({ noValidate: true }, (maxWidth && {
        style: Object.assign({ [cssMaxWidth.name]: maxWidth }, props.style)
    }), props, ouiaProps, { className: css(styles.form, isHorizontal && styles.modifiers.horizontal, (isWidthLimited || maxWidth) && styles.modifiers.limitWidth, className), ref: innerRef, children: children })));
};
export const Form = forwardRef((props, ref) => _jsx(FormBase, Object.assign({ innerRef: ref }, props)));
Form.displayName = 'Form';
//# sourceMappingURL=Form.js.map