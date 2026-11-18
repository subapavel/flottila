import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { createRef, Fragment } from 'react';
import { Tooltip } from '@patternfly/react-core/dist/esm/components/Tooltip';
import { Checkbox } from '@patternfly/react-core/dist/esm/components/Checkbox';
import { Radio } from '@patternfly/react-core/dist/esm/components/Radio';
export var RowSelectVariant;
(function (RowSelectVariant) {
    RowSelectVariant["radio"] = "radio";
    RowSelectVariant["checkbox"] = "checkbox";
})(RowSelectVariant || (RowSelectVariant = {}));
export const SelectColumn = (_a) => {
    var { children = null, 
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    className, onSelect = null, selectVariant, tooltip, tooltipProps, id, name, isIndeterminate } = _a, props = __rest(_a, ["children", "className", "onSelect", "selectVariant", "tooltip", "tooltipProps", "id", "name", "isIndeterminate"]);
    const inputRef = createRef();
    const handleChange = (event, _checked) => {
        onSelect && onSelect(event);
    };
    const commonProps = Object.assign(Object.assign({}, props), { id, ref: inputRef, onChange: handleChange });
    const checkboxProps = Object.assign(Object.assign({}, commonProps), (isIndeterminate && { isChecked: null }));
    const content = (_jsxs(Fragment, { children: [selectVariant === RowSelectVariant.checkbox ? (_jsx(Checkbox, Object.assign({}, checkboxProps))) : (_jsx(Radio, Object.assign({}, commonProps, { name: name }))), children] }));
    return tooltip ? (_jsx(Tooltip, Object.assign({ triggerRef: inputRef, content: tooltip }, tooltipProps, { children: content }))) : (content);
};
SelectColumn.displayName = 'SelectColumn';
//# sourceMappingURL=SelectColumn.js.map