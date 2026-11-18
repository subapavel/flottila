"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DrawerPanelContent = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const drawer_1 = tslib_1.__importDefault(require("@patternfly/react-styles/css/components/Drawer/drawer"));
const react_styles_1 = require("@patternfly/react-styles");
const Drawer_1 = require("./Drawer");
const util_1 = require("../../helpers/util");
const helpers_1 = require("../../helpers");
const FocusTrap_1 = require("../../helpers/FocusTrap/FocusTrap");
const c_drawer__panel_md_FlexBasis_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_drawer__panel_md_FlexBasis'));
const c_drawer__panel_md_FlexBasis_min_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_drawer__panel_md_FlexBasis_min'));
const c_drawer__panel_md_FlexBasis_max_1 = tslib_1.__importDefault(require('@patternfly/react-tokens/dist/js/c_drawer__panel_md_FlexBasis_max'));
const inert_1 = require("../../helpers/inert");
let isResizing = null;
let newSize = 0;
const DrawerPanelContent = (_a) => {
    var { className, id, children, hasNoBorder = false, isResizable = false, hasNoGlass = false, isGlass = false, isPlain = false, isNoPlainOnGlass = false, onResize, minSize, defaultSize, maxSize, increment = 5, resizeAriaLabel = 'Resize', widths, colorVariant = Drawer_1.DrawerColorVariant.default, focusTrap, style } = _a, props = tslib_1.__rest(_a, ["className", "id", "children", "hasNoBorder", "isResizable", "hasNoGlass", "isGlass", "isPlain", "isNoPlainOnGlass", "onResize", "minSize", "defaultSize", "maxSize", "increment", "resizeAriaLabel", "widths", "colorVariant", "focusTrap", "style"]);
    const panelId = (0, helpers_1.useSSRSafeId)('pf-drawer-panel-');
    const panel = (0, react_1.useRef)(undefined);
    const splitterRef = (0, react_1.useRef)(undefined);
    const [separatorValue, setSeparatorValue] = (0, react_1.useState)(0);
    const { position, isExpanded, isStatic, onExpand, drawerRef, drawerContentRef, isInline, isViewport } = (0, react_1.useContext)(Drawer_1.DrawerContext);
    const hidden = isStatic ? false : !isExpanded;
    const [isExpandedInternal, setIsExpandedInternal] = (0, react_1.useState)(!hidden);
    const [isFocusTrapActive, setIsFocusTrapActive] = (0, react_1.useState)(false);
    const [shouldCollapseSpace, setShouldCollapseSpace] = (0, react_1.useState)(hidden);
    const previouslyFocusedElement = (0, react_1.useRef)(null);
    const resizeState = (0, react_1.useRef)({
        initialPointerPos: 0,
        initialPanelSize: 0,
        currWidth: 0
    });
    if (isStatic && (focusTrap === null || focusTrap === void 0 ? void 0 : focusTrap.enabled)) {
        // eslint-disable-next-line no-console
        console.warn(`DrawerPanelContent: The focusTrap.enabled prop cannot be true if the Drawer's isStatic prop is true. This will cause a permanent focus trap.`);
    }
    (0, react_1.useEffect)(() => {
        if (!isStatic && isExpanded) {
            setIsExpandedInternal(isExpanded);
            setShouldCollapseSpace(false);
        }
    }, [isStatic, isExpanded]);
    const getSizeElement = () => {
        var _a, _b;
        if (isViewport) {
            return (_a = drawerRef === null || drawerRef === void 0 ? void 0 : drawerRef.current) !== null && _a !== void 0 ? _a : drawerContentRef === null || drawerContentRef === void 0 ? void 0 : drawerContentRef.current;
        }
        return (_b = drawerContentRef === null || drawerContentRef === void 0 ? void 0 : drawerContentRef.current) !== null && _b !== void 0 ? _b : drawerRef === null || drawerRef === void 0 ? void 0 : drawerRef.current;
    };
    const calcValueNow = () => {
        let splitterPos;
        let drawerSize;
        const sizeEl = getSizeElement();
        if (!sizeEl || !panel.current || !splitterRef.current || !(drawerRef === null || drawerRef === void 0 ? void 0 : drawerRef.current)) {
            return 0;
        }
        const isRTL = (0, util_1.getLanguageDirection)(panel.current) === 'rtl';
        if (isInline && (position === 'end' || position === 'right')) {
            if (isRTL) {
                splitterPos = panel.current.getBoundingClientRect().left - splitterRef.current.getBoundingClientRect().right;
                drawerSize = drawerRef.current.getBoundingClientRect().left - drawerRef.current.getBoundingClientRect().right;
            }
            else {
                splitterPos = panel.current.getBoundingClientRect().right - splitterRef.current.getBoundingClientRect().left;
                drawerSize = drawerRef.current.getBoundingClientRect().right - drawerRef.current.getBoundingClientRect().left;
            }
        }
        else if (isInline && (position === 'start' || position === 'left')) {
            if (isRTL) {
                splitterPos = splitterRef.current.getBoundingClientRect().left - panel.current.getBoundingClientRect().right;
                drawerSize = drawerRef.current.getBoundingClientRect().left - drawerRef.current.getBoundingClientRect().right;
            }
            else {
                splitterPos = splitterRef.current.getBoundingClientRect().right - panel.current.getBoundingClientRect().left;
                drawerSize = drawerRef.current.getBoundingClientRect().right - drawerRef.current.getBoundingClientRect().left;
            }
        }
        else if (position === 'end' || position === 'right') {
            if (isRTL) {
                splitterPos = sizeEl.getBoundingClientRect().left - splitterRef.current.getBoundingClientRect().right;
                drawerSize = sizeEl.getBoundingClientRect().left - sizeEl.getBoundingClientRect().right;
            }
            else {
                splitterPos = sizeEl.getBoundingClientRect().right - splitterRef.current.getBoundingClientRect().left;
                drawerSize = sizeEl.getBoundingClientRect().right - sizeEl.getBoundingClientRect().left;
            }
        }
        else if (position === 'start' || position === 'left') {
            if (isRTL) {
                splitterPos = splitterRef.current.getBoundingClientRect().left - sizeEl.getBoundingClientRect().right;
                drawerSize = sizeEl.getBoundingClientRect().left - sizeEl.getBoundingClientRect().right;
            }
            else {
                splitterPos = splitterRef.current.getBoundingClientRect().right - sizeEl.getBoundingClientRect().left;
                drawerSize = sizeEl.getBoundingClientRect().right - sizeEl.getBoundingClientRect().left;
            }
        }
        else if (position === 'bottom') {
            splitterPos = sizeEl.getBoundingClientRect().bottom - splitterRef.current.getBoundingClientRect().top;
            drawerSize = sizeEl.getBoundingClientRect().bottom - sizeEl.getBoundingClientRect().top;
        }
        const newSplitterPos = (splitterPos / drawerSize) * 100;
        return Math.round((newSplitterPos + Number.EPSILON) * 100) / 100;
    };
    const setInitialResizeState = (controlPosition) => {
        const panelRect = panel.current.getBoundingClientRect();
        const initialPanelSize = position === 'bottom' ? panelRect.height : panelRect.width;
        resizeState.current = {
            initialPointerPos: controlPosition,
            initialPanelSize,
            currWidth: initialPanelSize
        };
    };
    const handleTouchStart = (e) => {
        e.stopPropagation();
        setInitialResizeState(position === 'bottom' ? e.touches[0].clientY : e.touches[0].clientX);
        document.addEventListener('touchmove', callbackTouchMove, { passive: false });
        document.addEventListener('touchend', callbackTouchEnd);
        isResizing = true;
    };
    const handleMousedown = (e) => {
        e.stopPropagation();
        e.preventDefault();
        setInitialResizeState(position === 'bottom' ? e.clientY : e.clientX);
        document.addEventListener('mousemove', callbackMouseMove);
        document.addEventListener('mouseup', callbackMouseUp);
        drawerRef.current.classList.add((0, react_styles_1.css)(drawer_1.default.modifiers.resizing));
        isResizing = true;
    };
    const handleMouseMove = (e) => {
        const mousePos = position === 'bottom' ? e.clientY : e.clientX;
        handleControlMove(e, mousePos);
    };
    const handleTouchMove = (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();
        const touchPos = position === 'bottom' ? e.touches[0].clientY : e.touches[0].clientX;
        handleControlMove(e, touchPos);
    };
    const handleControlMove = (e, controlPosition) => {
        const isRTL = (0, util_1.getLanguageDirection)(panel.current) === 'rtl';
        e.stopPropagation();
        if (!isResizing) {
            return;
        }
        const { initialPointerPos, initialPanelSize } = resizeState.current;
        const delta = controlPosition - initialPointerPos;
        let newSize = 0;
        if (position === 'end' || position === 'right') {
            newSize = isRTL ? initialPanelSize + delta : initialPanelSize - delta;
        }
        else if (position === 'start' || position === 'left') {
            newSize = isRTL ? initialPanelSize - delta : initialPanelSize + delta;
        }
        else {
            newSize = initialPanelSize - delta;
        }
        if (position === 'bottom') {
            panel.current.style.overflowAnchor = 'none';
        }
        panel.current.style.setProperty(c_drawer__panel_md_FlexBasis_1.default.name, newSize + 'px');
        resizeState.current.currWidth = newSize;
        setSeparatorValue(calcValueNow());
    };
    const handleMouseup = (e) => {
        if (!isResizing) {
            return;
        }
        drawerRef.current.classList.remove((0, react_styles_1.css)(drawer_1.default.modifiers.resizing));
        isResizing = false;
        onResize && onResize(e, resizeState.current.currWidth, id);
        document.removeEventListener('mousemove', callbackMouseMove);
        document.removeEventListener('mouseup', callbackMouseUp);
    };
    const handleTouchEnd = (e) => {
        e.stopPropagation();
        if (!isResizing) {
            return;
        }
        isResizing = false;
        onResize && onResize(e, resizeState.current.currWidth, id);
        document.removeEventListener('touchmove', callbackTouchMove);
        document.removeEventListener('touchend', callbackTouchEnd);
    };
    const callbackMouseMove = (0, react_1.useCallback)(handleMouseMove, []);
    const callbackTouchEnd = (0, react_1.useCallback)(handleTouchEnd, []);
    const callbackTouchMove = (0, react_1.useCallback)(handleTouchMove, []);
    const callbackMouseUp = (0, react_1.useCallback)(handleMouseup, []);
    const handleKeys = (e) => {
        const isRTL = (0, util_1.getLanguageDirection)(panel.current) === 'rtl';
        const key = e.key;
        if (key !== 'Escape' &&
            key !== 'Enter' &&
            key !== 'ArrowUp' &&
            key !== 'ArrowDown' &&
            key !== 'ArrowLeft' &&
            key !== 'ArrowRight') {
            if (isResizing) {
                e.preventDefault();
            }
            return;
        }
        e.preventDefault();
        if (key === 'Escape' || key === 'Enter') {
            onResize && onResize(e, resizeState.current.currWidth, id);
        }
        const panelRect = panel.current.getBoundingClientRect();
        newSize = position === 'bottom' ? panelRect.height : panelRect.width;
        let delta = 0;
        if (key === 'ArrowRight') {
            if (isRTL) {
                delta = position === 'left' || position === 'start' ? -increment : increment;
            }
            else {
                delta = position === 'left' || position === 'start' ? increment : -increment;
            }
        }
        else if (key === 'ArrowLeft') {
            if (isRTL) {
                delta = position === 'left' || position === 'start' ? increment : -increment;
            }
            else {
                delta = position === 'left' || position === 'start' ? -increment : increment;
            }
        }
        else if (key === 'ArrowUp') {
            delta = increment;
        }
        else if (key === 'ArrowDown') {
            delta = -increment;
        }
        newSize = newSize + delta;
        if (position === 'bottom') {
            panel.current.style.overflowAnchor = 'none';
        }
        panel.current.style.setProperty(c_drawer__panel_md_FlexBasis_1.default.name, newSize + 'px');
        resizeState.current.currWidth = newSize;
        setSeparatorValue(calcValueNow());
    };
    const boundaryCssVars = {};
    if (defaultSize) {
        boundaryCssVars[c_drawer__panel_md_FlexBasis_1.default.name] = defaultSize;
    }
    if (minSize) {
        boundaryCssVars[c_drawer__panel_md_FlexBasis_min_1.default.name] = minSize;
    }
    if (maxSize) {
        boundaryCssVars[c_drawer__panel_md_FlexBasis_max_1.default.name] = maxSize;
    }
    const isValidFocusTrap = (focusTrap === null || focusTrap === void 0 ? void 0 : focusTrap.enabled) && !isStatic;
    const Component = isValidFocusTrap ? FocusTrap_1.FocusTrap : 'div';
    const focusTrapProps = {
        tabIndex: -1,
        'aria-modal': true,
        role: 'dialog',
        active: isFocusTrapActive,
        'aria-labelledby': (focusTrap === null || focusTrap === void 0 ? void 0 : focusTrap['aria-labelledby']) || id || panelId,
        focusTrapOptions: {
            fallbackFocus: () => panel.current,
            onActivate: () => {
                if (previouslyFocusedElement.current !== document.activeElement) {
                    previouslyFocusedElement.current = document.activeElement;
                }
            },
            onDeactivate: () => {
                previouslyFocusedElement.current &&
                    previouslyFocusedElement.current.focus &&
                    previouslyFocusedElement.current.focus();
            },
            clickOutsideDeactivates: true,
            returnFocusOnDeactivate: false,
            // FocusTrap's initialFocus can accept false as a value to prevent initial focus.
            // We want to prevent this in case false is ever passed in.
            initialFocus: (focusTrap === null || focusTrap === void 0 ? void 0 : focusTrap.elementToFocusOnExpand) || undefined,
            escapeDeactivates: false
        }
    };
    return ((0, jsx_runtime_1.jsx)(Component, Object.assign({}, (isValidFocusTrap && focusTrapProps), { id: id || panelId, className: (0, react_styles_1.css)(drawer_1.default.drawerPanel, isResizable && drawer_1.default.modifiers.resizable, hasNoGlass && 'pf-m-no-glass', isGlass && drawer_1.default.modifiers.glass, isPlain && drawer_1.default.modifiers.plain, isNoPlainOnGlass && drawer_1.default.modifiers.noPlainOnGlass, hasNoBorder && drawer_1.default.modifiers.noBorder, (0, util_1.formatBreakpointMods)(widths, drawer_1.default), colorVariant === Drawer_1.DrawerColorVariant.noBackground && drawer_1.default.modifiers.noBackground, colorVariant === Drawer_1.DrawerColorVariant.secondary && drawer_1.default.modifiers.secondary, className), onTransitionEnd: (ev) => {
            if (ev.target === panel.current) {
                if (!hidden && ev.nativeEvent.propertyName === 'transform') {
                    onExpand(ev);
                }
                setIsExpandedInternal(!hidden);
                // We also need to collapse the space when the panel is hidden to prevent automation from scrolling to it
                if (hidden && ev.nativeEvent.propertyName === 'transform') {
                    setShouldCollapseSpace(true);
                }
                if (isValidFocusTrap && ev.nativeEvent.propertyName === 'transform') {
                    setIsFocusTrapActive((prevIsFocusTrapActive) => !prevIsFocusTrapActive);
                }
            }
        }, hidden: hidden, style: Object.assign(Object.assign({}, ((defaultSize || minSize || maxSize) && boundaryCssVars)), style) }, (shouldCollapseSpace && { inert: inert_1.IS_INERT }), props, { ref: panel, children: isExpandedInternal && ((0, jsx_runtime_1.jsxs)(react_1.Fragment, { children: [isResizable && ((0, jsx_runtime_1.jsxs)(react_1.Fragment, { children: [(0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(drawer_1.default.drawerSplitter, position !== 'bottom' && drawer_1.default.modifiers.vertical), role: "separator", tabIndex: 0, "aria-orientation": position === 'bottom' ? 'horizontal' : 'vertical', "aria-label": resizeAriaLabel, "aria-valuenow": separatorValue, "aria-valuemin": 0, "aria-valuemax": 100, "aria-controls": id || panelId, onMouseDown: handleMousedown, onKeyDown: handleKeys, onTouchStart: handleTouchStart, ref: splitterRef, children: (0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(drawer_1.default.drawerSplitterHandle), "aria-hidden": true }) }), (0, jsx_runtime_1.jsx)("div", { className: (0, react_styles_1.css)(drawer_1.default.drawerPanelMain), children: children })] })), !isResizable && children] })) })));
};
exports.DrawerPanelContent = DrawerPanelContent;
exports.DrawerPanelContent.displayName = 'DrawerPanelContent';
//# sourceMappingURL=DrawerPanelContent.js.map