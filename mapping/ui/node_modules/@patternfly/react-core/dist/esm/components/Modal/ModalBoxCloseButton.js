import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { css } from '@patternfly/react-styles';
import styles from '@patternfly/react-styles/css/components/ModalBox/modal-box.mjs';
import { Button } from '../Button';
import RhMicronsCloseIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-close-icon';
export const ModalBoxCloseButton = (_a) => {
    var { className, onClose, 'aria-label': ariaLabel = 'Close', ouiaId } = _a, props = __rest(_a, ["className", "onClose", 'aria-label', "ouiaId"]);
    return (_jsx("div", { className: css(styles.modalBoxClose, className), children: _jsx(Button, Object.assign({ variant: "plain", onClick: (event) => onClose(event), "aria-label": ariaLabel }, (ouiaId && { ouiaId: `${ouiaId}-${ModalBoxCloseButton.displayName}` }), props, { icon: _jsx(RhMicronsCloseIcon, {}) })) }));
};
ModalBoxCloseButton.displayName = 'ModalBoxCloseButton';
//# sourceMappingURL=ModalBoxCloseButton.js.map