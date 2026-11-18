import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Component, createRef } from 'react';
import styles from '@patternfly/react-styles/css/components/ExpandableSection/expandable-section.mjs';
import { css } from '@patternfly/react-styles';
import lineClamp from '@patternfly/react-tokens/dist/esm/c_expandable_section_m_truncate__content_LineClamp';
import RhMicronsCaretDownIcon from '@patternfly/react-icons/dist/esm/icons/rh-microns-caret-down-icon';
import { debounce } from '../../helpers/util';
import { getResizeObserver } from '../../helpers/resizeObserver';
import { GenerateId, getOUIAProps } from '../../helpers';
import { Button } from '../Button';
export var ExpandableSectionVariant;
(function (ExpandableSectionVariant) {
    ExpandableSectionVariant["default"] = "default";
    ExpandableSectionVariant["truncate"] = "truncate";
})(ExpandableSectionVariant || (ExpandableSectionVariant = {}));
const directionClassMap = {
    up: styles.modifiers.expandTop,
    down: styles.modifiers.expandBottom
};
const setLineClamp = (lines, element) => {
    if (!element || lines < 1) {
        return;
    }
    element.style.setProperty(lineClamp.name, lines.toString());
};
class ExpandableSection extends Component {
    constructor(props) {
        super(props);
        this.expandableContentRef = createRef();
        this.observer = () => { };
        this.checkToggleVisibility = () => {
            var _a;
            if ((_a = this.expandableContentRef) === null || _a === void 0 ? void 0 : _a.current) {
                const maxLines = this.props.truncateMaxLines || parseInt(lineClamp.value);
                const totalLines = this.expandableContentRef.current.scrollHeight /
                    parseInt(getComputedStyle(this.expandableContentRef.current).lineHeight);
                this.setState({
                    hasToggle: totalLines > maxLines
                });
            }
        };
        this.resize = () => {
            if (this.expandableContentRef.current) {
                const { offsetWidth } = this.expandableContentRef.current;
                if (this.state.previousWidth !== offsetWidth) {
                    this.setState({ previousWidth: offsetWidth });
                    this.checkToggleVisibility();
                }
            }
        };
        this.handleResize = debounce(this.resize, 250);
        this.state = {
            isExpanded: props.isExpanded,
            hasToggle: true,
            previousWidth: undefined
        };
    }
    calculateToggleText(toggleText, toggleTextExpanded, toggleTextCollapsed, propOrStateIsExpanded) {
        if (propOrStateIsExpanded && toggleTextExpanded !== '') {
            return toggleTextExpanded;
        }
        if (!propOrStateIsExpanded && toggleTextCollapsed !== '') {
            return toggleTextCollapsed;
        }
        return toggleText;
    }
    componentDidMount() {
        if (this.props.variant === ExpandableSectionVariant.truncate) {
            const expandableContent = this.expandableContentRef.current;
            this.setState({ previousWidth: expandableContent.offsetWidth });
            this.observer = getResizeObserver(expandableContent, this.handleResize, false);
            if (this.props.truncateMaxLines) {
                setLineClamp(this.props.truncateMaxLines, expandableContent);
            }
            this.checkToggleVisibility();
        }
    }
    componentDidUpdate(prevProps) {
        if (this.props.variant === ExpandableSectionVariant.truncate &&
            (prevProps.truncateMaxLines !== this.props.truncateMaxLines || prevProps.children !== this.props.children)) {
            const expandableContent = this.expandableContentRef.current;
            setLineClamp(this.props.truncateMaxLines, expandableContent);
            this.checkToggleVisibility();
        }
    }
    componentWillUnmount() {
        if (this.props.variant === ExpandableSectionVariant.truncate) {
            this.observer();
        }
    }
    render() {
        const _a = this.props, { onToggle: onToggleProp, className, toggleText, toggleTextExpanded, toggleTextCollapsed, toggleContent, toggleAriaLabel, toggleAriaLabelledBy, toggleIcon = _jsx(RhMicronsCaretDownIcon, {}), hasToggleIcon = true, children, isExpanded, isDetached, displaySize, isWidthLimited, isIndented, contentId, toggleId, variant, 
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        truncateMaxLines, direction, toggleWrapper = 'div', ouiaId, ouiaSafe } = _a, props = __rest(_a, ["onToggle", "className", "toggleText", "toggleTextExpanded", "toggleTextCollapsed", "toggleContent", "toggleAriaLabel", "toggleAriaLabelledBy", "toggleIcon", "hasToggleIcon", "children", "isExpanded", "isDetached", "displaySize", "isWidthLimited", "isIndented", "contentId", "toggleId", "variant", "truncateMaxLines", "direction", "toggleWrapper", "ouiaId", "ouiaSafe"]);
        if (isDetached && !toggleId) {
            /* eslint-disable no-console */
            console.warn('ExpandableSection: The toggleId value must be passed in and must match the toggleId of the ExpandableSectionToggle.');
        }
        let onToggle = onToggleProp;
        let propOrStateIsExpanded = isExpanded;
        // uncontrolled
        if (isExpanded === undefined) {
            propOrStateIsExpanded = this.state.isExpanded;
            onToggle = (event, isOpen) => {
                this.setState({ isExpanded: isOpen }, () => onToggleProp(event, this.state.isExpanded));
            };
        }
        const computedToggleText = this.calculateToggleText(toggleText, toggleTextExpanded, toggleTextCollapsed, propOrStateIsExpanded);
        const computedToggleContent = typeof toggleContent === 'function' ? toggleContent(propOrStateIsExpanded) : toggleContent;
        const ToggleWrapper = toggleWrapper;
        return (_jsx(GenerateId, { prefix: "expandable-section-content-", children: (genContentId) => (_jsx(GenerateId, { prefix: "expandable-section-toggle-", children: (genToggleId) => {
                    const uniqueContentId = contentId || genContentId;
                    const uniqueToggleId = toggleId || genToggleId;
                    const expandableToggle = !isDetached && (_jsx(ToggleWrapper, { className: `${styles.expandableSection}__toggle`, children: _jsx(Button, Object.assign({ variant: "link" }, (variant === ExpandableSectionVariant.truncate && { isInline: true }), { "aria-expanded": propOrStateIsExpanded, "aria-controls": uniqueContentId, id: uniqueToggleId, onClick: (event) => onToggle(event, !propOrStateIsExpanded) }, (variant !== ExpandableSectionVariant.truncate &&
                            hasToggleIcon && {
                            icon: _jsx("span", { className: css(styles.expandableSectionToggleIcon), children: toggleIcon })
                        }), { "aria-label": toggleAriaLabel, "aria-labelledby": toggleAriaLabelledBy, children: computedToggleContent || computedToggleText })) }));
                    return (_jsxs("div", Object.assign({ className: css(styles.expandableSection, propOrStateIsExpanded && styles.modifiers.expanded, displaySize === 'lg' && styles.modifiers.displayLg, isWidthLimited && styles.modifiers.limitWidth, isIndented && styles.modifiers.indented, isDetached && direction && directionClassMap[direction], isDetached && direction && 'pf-m-detached', variant === ExpandableSectionVariant.truncate && styles.modifiers.truncate, className) }, props, getOUIAProps(ExpandableSection.displayName, ouiaId, ouiaSafe), { children: [variant === ExpandableSectionVariant.default && expandableToggle, _jsx("div", { ref: this.expandableContentRef, className: css(styles.expandableSectionContent), hidden: variant !== ExpandableSectionVariant.truncate && !propOrStateIsExpanded, id: uniqueContentId, "aria-labelledby": uniqueToggleId, role: "region", children: children }), variant === ExpandableSectionVariant.truncate && this.state.hasToggle && expandableToggle] })));
                } })) }));
    }
}
ExpandableSection.displayName = 'ExpandableSection';
ExpandableSection.defaultProps = {
    className: '',
    toggleText: '',
    toggleTextExpanded: '',
    toggleTextCollapsed: '',
    toggleAriaLabel: undefined,
    toggleAriaLabelledBy: undefined,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    onToggle: (event, isExpanded) => undefined,
    isDetached: false,
    displaySize: 'default',
    isWidthLimited: false,
    isIndented: false,
    variant: 'default',
    ouiaSafe: true
};
export { ExpandableSection };
//# sourceMappingURL=ExpandableSection.js.map