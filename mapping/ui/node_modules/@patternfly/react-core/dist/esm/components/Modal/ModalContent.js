import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useEffect, useState } from 'react';
import { FocusTrap } from '../../helpers';
import bullsEyeStyles from '@patternfly/react-styles/css/layouts/Bullseye/bullseye.mjs';
import { css } from '@patternfly/react-styles';
import { getOUIAProps, useHasAnimations } from '../../helpers';
import { Backdrop } from '../Backdrop';
import { ModalBoxCloseButton } from './ModalBoxCloseButton';
import { ModalBox } from './ModalBox';
const transitionEndFallbackDelay = 300;
export const ModalContent = (_a) => {
    var { backdropClassName, children, className, isOpen = false, 'aria-label': ariaLabel, 'aria-describedby': ariaDescribedby, 'aria-labelledby': ariaLabelledby, onClose, variant = 'default', position, positionOffset, width, maxWidth, boxId, backdropId, disableFocusTrap = false, ouiaId, ouiaSafe = true, elementToFocus, focusTrapId, hasAnimations: hasAnimationsProp } = _a, props = __rest(_a, ["backdropClassName", "children", "className", "isOpen", 'aria-label', 'aria-describedby', 'aria-labelledby', "onClose", "variant", "position", "positionOffset", "width", "maxWidth", "boxId", "backdropId", "disableFocusTrap", "ouiaId", "ouiaSafe", "elementToFocus", "focusTrapId", "hasAnimations"]);
    const hasAnimations = useHasAnimations(hasAnimationsProp);
    // Keeps the modal in the DOM while the close animation runs. When animations are enabled we defer
    // unmounting until the backdrop's transition ends (see onTransitionEnd below) instead of removing
    // it immediately when isOpen becomes false.
    const [isRendered, setIsRendered] = useState(isOpen);
    useEffect(() => {
        if (isOpen) {
            setIsRendered(true);
        }
        else if (!isRendered) {
            return;
        }
        else if (!hasAnimations) {
            setIsRendered(false);
        }
        else {
            // Ensure the modal is removed if CSS transitions are disabled or transitionend does not fire.
            const transitionEndFallback = window.setTimeout(() => setIsRendered(false), transitionEndFallbackDelay);
            return () => window.clearTimeout(transitionEndFallback);
        }
    }, [isOpen, hasAnimations, isRendered]);
    if (!isRendered) {
        return null;
    }
    const getAriaLabelledBy = () => {
        if (ariaLabelledby) {
            return ariaLabelledby;
        }
        if (ariaLabel) {
            return undefined;
        }
        return boxId;
    };
    const modalBox = (_jsxs(ModalBox, Object.assign({ className: css(className), isOpen: isOpen, hasAnimations: hasAnimations, variant: variant, position: position, positionOffset: positionOffset, "aria-label": ariaLabel, "aria-labelledby": getAriaLabelledBy(), "aria-describedby": ariaDescribedby }, getOUIAProps(ModalContent.displayName, ouiaId, ouiaSafe), { style: Object.assign(Object.assign({}, (width && { '--pf-v6-c-modal-box--Width': typeof width !== 'number' ? width : `${width}px` })), (maxWidth && {
            '--pf-v6-c-modal-box--MaxWidth': typeof maxWidth !== 'number' ? maxWidth : `${maxWidth}px`
        })) }, props, { id: boxId, children: [onClose && _jsx(ModalBoxCloseButton, { onClose: (event) => onClose(event), ouiaId: ouiaId }), children] })));
    let focusTrapActive = !disableFocusTrap;
    if (hasAnimations) {
        focusTrapActive = !disableFocusTrap && isOpen;
    }
    return (_jsx(Backdrop, { className: css(backdropClassName), id: backdropId, hasAnimations: hasAnimations, isVisible: isOpen, "aria-hidden": hasAnimations && !isOpen ? true : undefined, onTransitionEnd: hasAnimations
            ? (event) => {
                // Only unmount once the backdrop's own closing transition finishes. Guarding on the
                // target prevents bubbled transitions from child elements from triggering this early.
                if (!isOpen && event.target === event.currentTarget) {
                    setIsRendered(false);
                }
            }
            : undefined, children: _jsx(FocusTrap, { active: focusTrapActive, focusTrapOptions: {
                clickOutsideDeactivates: true,
                tabbableOptions: { displayCheck: 'none' },
                // FocusTrap's initialFocus can accept false as a value to prevent initial focus.
                // We want to prevent this in case false is ever passed in.
                initialFocus: elementToFocus || undefined
            }, className: css(bullsEyeStyles.bullseye), id: focusTrapId, children: modalBox }) }));
};
ModalContent.displayName = 'ModalContent';
//# sourceMappingURL=ModalContent.js.map