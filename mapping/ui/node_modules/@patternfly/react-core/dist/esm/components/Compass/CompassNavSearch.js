import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useRef } from 'react';
import styles from '@patternfly/react-styles/css/components/Compass/compass.mjs';
import { css } from '@patternfly/react-styles';
import { Button } from '../Button';
import { Tooltip } from '../Tooltip';
const CompassSearchIcon = () => (_jsxs("svg", { width: "1em", height: "1em", className: "pf-v6-svg", viewBox: "0 0 20 20", fill: "none", xmlns: "http://www.w3.org/2000/svg", "aria-hidden": "true", children: [_jsx("path", { d: "M14.166 14.166L17.4993 17.4993", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" }), _jsx("path", { d: "M2.5 9.16667C2.5 12.8486 5.48477 15.8333 9.16667 15.8333C11.0108 15.8333 12.6801 15.0846 13.887 13.8744C15.0897 12.6685 15.8333 11.0044 15.8333 9.16667C15.8333 5.48477 12.8486 2.5 9.16667 2.5C5.48477 2.5 2.5 5.48477 2.5 9.16667Z", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" })] }));
export const CompassNavSearch = (_a) => {
    var { 'aria-label': ariaLabel = 'Search', tooltipContent = 'Search', className, onClick } = _a, props = __rest(_a, ['aria-label', "tooltipContent", "className", "onClick"]);
    const buttonRef = useRef(null);
    return (_jsx("div", Object.assign({ className: css(styles.compassNav + '-search', className) }, props, { children: _jsx(Tooltip, { content: tooltipContent, aria: "none", "aria-live": "off", triggerRef: buttonRef, children: _jsx(Button, { isCircle: true, variant: "plain", icon: _jsx(CompassSearchIcon, {}), "aria-label": ariaLabel, onClick: onClick, ref: buttonRef }) }) })));
};
CompassNavSearch.displayName = 'CompassNavSearch';
//# sourceMappingURL=CompassNavSearch.js.map