import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useEffect, useState } from 'react';
import styles from '@patternfly/react-styles/css/components/MultipleFileUpload/multiple-file-upload.mjs';
import { css } from '@patternfly/react-styles';
import { ExpandableSection } from '../ExpandableSection';
import { useSSRSafeId } from '../../helpers';
import RhUiInProgressIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-in-progress-icon';
import RhUiCheckCircleFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-check-circle-fill-icon';
import RhUiCloseCircleFillIcon from '@patternfly/react-icons/dist/esm/icons/rh-ui-close-circle-fill-icon';
export const MultipleFileUploadStatus = (_a) => {
    var { children, className, statusToggleText, statusToggleIcon, 'aria-label': ariaLabel } = _a, props = __rest(_a, ["children", "className", "statusToggleText", "statusToggleIcon", 'aria-label']);
    const expandableSectionId = useSSRSafeId('pf-expandable-section-');
    const [icon, setIcon] = useState();
    const [isOpen, setIsOpen] = useState(true);
    useEffect(() => {
        switch (statusToggleIcon) {
            case 'danger':
                setIcon(_jsx(RhUiCloseCircleFillIcon, {}));
                break;
            case 'success':
                setIcon(_jsx(RhUiCheckCircleFillIcon, {}));
                break;
            case 'inProgress':
                setIcon(_jsx(RhUiInProgressIcon, {}));
                break;
            default:
                setIcon(statusToggleIcon);
        }
    }, [statusToggleIcon]);
    const toggle = (_jsxs("div", { className: styles.multipleFileUploadStatusProgress, children: [_jsx("div", { className: `${styles.multipleFileUploadStatusProgress}-icon`, children: icon }), _jsx("div", { className: `${styles.multipleFileUploadStatusProgress}-text`, children: statusToggleText })] }));
    const toggleExpandableSection = () => {
        setIsOpen(!isOpen);
    };
    return (_jsx("div", Object.assign({ className: css(styles.multipleFileUploadStatus, className) }, props, { children: _jsx(ExpandableSection, { contentId: `${expandableSectionId}-content`, toggleId: `${expandableSectionId}-toggle`, toggleContent: toggle, isExpanded: isOpen, onToggle: toggleExpandableSection, children: _jsx("ul", { className: `${styles.multipleFileUploadStatus}-list`, role: "list", "aria-label": ariaLabel, children: children }) }) })));
};
MultipleFileUploadStatus.displayName = 'MultipleFileUploadStatus';
//# sourceMappingURL=MultipleFileUploadStatus.js.map