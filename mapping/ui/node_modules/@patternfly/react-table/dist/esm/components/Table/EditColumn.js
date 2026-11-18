import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Fragment } from 'react';
import { Button } from '@patternfly/react-core/dist/esm/components/Button';
import RhUiEditFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-edit-fill-icon';
import RhMicronsCheckmarkIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-checkmark-icon';
import RhMicronsCloseIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-close-icon';
import inlineStyles from '@patternfly/react-styles/css/components/InlineEdit/inline-edit.mjs';
import { css } from '@patternfly/react-styles';
export const EditColumn = (_a) => {
    var { onClick = null, 
    /* eslint-disable @typescript-eslint/no-unused-vars */
    className = '', editing, valid, 
    /* eslint-enable @typescript-eslint/no-unused-vars */
    saveAriaLabel, cancelAriaLabel, editAriaLabel } = _a, props = __rest(_a, ["onClick", "className", "editing", "valid", "saveAriaLabel", "cancelAriaLabel", "editAriaLabel"]);
    return (_jsxs(Fragment, { children: [_jsxs("div", { className: css(inlineStyles.inlineEditGroup, inlineStyles.modifiers.iconGroup, 'pf-m-action-group'), children: [_jsx("div", { className: css(inlineStyles.inlineEditAction), children: _jsx(Button, Object.assign({ "aria-label": saveAriaLabel }, props, { onClick: (e) => onClick(e, 'save'), variant: "plain", icon: _jsx(RhMicronsCheckmarkIcon, {}) })) }), _jsx("div", { className: css(inlineStyles.inlineEditAction), children: _jsx(Button, Object.assign({ "aria-label": cancelAriaLabel }, props, { onClick: (e) => onClick(e, 'cancel'), variant: "plain", icon: _jsx(RhMicronsCloseIcon, {}) })) })] }), _jsx("div", { className: css(inlineStyles.inlineEditAction, inlineStyles.modifiers.enableEditable), children: _jsx(Button, Object.assign({ "aria-label": editAriaLabel }, props, { onClick: (e) => onClick(e, 'edit'), variant: "plain", icon: _jsx(RhUiEditFillIcon, {}) })) })] }));
};
EditColumn.displayName = 'EditColumn';
//# sourceMappingURL=EditColumn.js.map