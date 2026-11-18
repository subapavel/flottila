import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/ClipboardCopy/clipboard-copy.mjs';
import { css } from '@patternfly/react-styles';
import RhMicronsCaretDownIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-caret-down-icon';
import { Button } from '../Button';
export const ClipboardCopyToggle = (_a) => {
    var { onClick, id, contentId, isExpanded = false } = _a, props = __rest(_a, ["onClick", "id", "contentId", "isExpanded"]);
    return (_jsx(Button, Object.assign({ type: "button", variant: "control", onClick: onClick, id: id, "aria-controls": isExpanded ? contentId : undefined, "aria-expanded": isExpanded }, props, { icon: _jsx("div", { className: css(styles.clipboardCopyToggleIcon), children: _jsx(RhMicronsCaretDownIcon, {}) }) })));
};
ClipboardCopyToggle.displayName = 'ClipboardCopyToggle';
//# sourceMappingURL=ClipboardCopyToggle.js.map