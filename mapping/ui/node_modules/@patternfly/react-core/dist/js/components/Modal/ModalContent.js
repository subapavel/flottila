"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ModalContent = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const helpers_1 = require("../../helpers");
const bullseye_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/layouts/Bullseye/bullseye"));
const react_styles_1 = require("@patternfly/react-styles");
const helpers_2 = require("../../helpers");
const Backdrop_1 = require("../Backdrop");
const ModalBoxCloseButton_1 = require("./ModalBoxCloseButton");
const ModalBox_1 = require("./ModalBox");
const transitionEndFallbackDelay = 300;
const ModalContent = (_a) => {
    var { backdropClassName, children, className, isOpen = false, 'aria-label': ariaLabel, 'aria-describedby': ariaDescribedby, 'aria-labelledby': ariaLabelledby, onClose, variant = 'default', position, positionOffset, width, maxWidth, boxId, backdropId, disableFocusTrap = false, ouiaId, ouiaSafe = true, elementToFocus, focusTrapId, hasAnimations: hasAnimationsProp } = _a, props = tslib_1.__rest(_a, ["backdropClassName", "children", "className", "isOpen", 'aria-label', 'aria-describedby', 'aria-labelledby', "onClose", "variant", "position", "positionOffset", "width", "maxWidth", "boxId", "backdropId", "disableFocusTrap", "ouiaId", "ouiaSafe", "elementToFocus", "focusTrapId", "hasAnimations"]);
    const hasAnimations = (0, helpers_2.useHasAnimations)(hasAnimationsProp);
    // Keeps the modal in the DOM while the close animation runs. When animations are enabled we defer
    // unmounting until the backdrop's transition ends (see onTransitionEnd below) instead of removing
    // it immediately when isOpen becomes false.
    const [isRendered, setIsRendered] = (0, react_1.useState)(isOpen);
    (0, react_1.useEffect)(() => {
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
    const modalBox = ((0, jsx_runtime_1.jsxs)(ModalBox_1.ModalBox, Object.assign({ className: (0, react_styles_1.css)(className), isOpen: isOpen, hasAnimations: hasAnimations, variant: variant, position: position, positionOffset: positionOffset, "aria-label": ariaLabel, "aria-labelledby": getAriaLabelledBy(), "aria-describedby": ariaDescribedby }, (0, helpers_2.getOUIAProps)(exports.ModalContent.displayName, ouiaId, ouiaSafe), { style: Object.assign(Object.assign({}, (width && { '--pf-v6-c-modal-box--Width': typeof width !== 'number' ? width : `${width}px` })), (maxWidth && {
            '--pf-v6-c-modal-box--MaxWidth': typeof maxWidth !== 'number' ? maxWidth : `${maxWidth}px`
        })) }, props, { id: boxId, children: [onClose && (0, jsx_runtime_1.jsx)(ModalBoxCloseButton_1.ModalBoxCloseButton, { onClose: (event) => onClose(event), ouiaId: ouiaId }), children] })));
    let focusTrapActive = !disableFocusTrap;
    if (hasAnimations) {
        focusTrapActive = !disableFocusTrap && isOpen;
    }
    return ((0, jsx_runtime_1.jsx)(Backdrop_1.Backdrop, { className: (0, react_styles_1.css)(backdropClassName), id: backdropId, hasAnimations: hasAnimations, isVisible: isOpen, "aria-hidden": hasAnimations && !isOpen ? true : undefined, onTransitionEnd: hasAnimations
            ? (event) => {
                // Only unmount once the backdrop's own closing transition finishes. Guarding on the
                // target prevents bubbled transitions from child elements from triggering this early.
                if (!isOpen && event.target === event.currentTarget) {
                    setIsRendered(false);
                }
            }
            : undefined, children: (0, jsx_runtime_1.jsx)(helpers_1.FocusTrap, { active: focusTrapActive, focusTrapOptions: {
                clickOutsideDeactivates: true,
                tabbableOptions: { displayCheck: 'none' },
                // FocusTrap's initialFocus can accept false as a value to prevent initial focus.
                // We want to prevent this in case false is ever passed in.
                initialFocus: elementToFocus || undefined
            }, className: (0, react_styles_1.css)(bullseye_1.default.bullseye), id: focusTrapId, children: modalBox }) }));
};
exports.ModalContent = ModalContent;
exports.ModalContent.displayName = 'ModalContent';
//# sourceMappingURL=ModalContent.js.map