import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import RhUiGripVerticalFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-grip-vertical-fill-icon';
import { Button } from '@patternfly/react-core/dist/esm/components/Button';
export const DraggableCell = (_a) => {
    var { className, onClick, 'aria-label': ariaLabel, id } = _a, props = __rest(_a, ["className", "onClick", 'aria-label', "id"]);
    return (_jsx(Button, Object.assign({ id: id, variant: "plain", className: className, type: "button", "aria-label": ariaLabel || `Draggable row draggable button`, onClick: onClick }, props, { icon: _jsx(RhUiGripVerticalFillIcon, {}) })));
};
DraggableCell.displayName = 'DraggableCell';
//# sourceMappingURL=DraggableCell.js.map