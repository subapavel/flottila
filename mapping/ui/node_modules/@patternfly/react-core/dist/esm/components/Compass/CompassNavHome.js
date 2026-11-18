import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useRef } from 'react';
import styles from '@patternfly/react-styles/css/components/Compass/compass.mjs';
import { css } from '@patternfly/react-styles';
import { Button } from '../Button';
import { Tooltip } from '../Tooltip';
const CompassHomeIcon = () => (_jsxs("svg", { width: "1em", height: "1em", className: "pf-v6-svg", viewBox: "0 0 20 20", fill: "none", xmlns: "http://www.w3.org/2000/svg", "aria-hidden": "true", children: [_jsx("path", { d: "M8.33268 13.334H11.666", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" }), _jsx("path", { d: "M1.66602 6.66602L9.73102 2.63351C9.89994 2.54905 10.0988 2.54905 10.2677 2.63351L18.3327 6.66602", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" }), _jsx("path", { d: "M16.6673 9.16602V15.4993C16.6673 16.6039 15.7719 17.4993 14.6673 17.4993H5.33398C4.22941 17.4993 3.33398 16.6039 3.33398 15.4993V9.16602", stroke: "currentcolor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round" })] }));
export const CompassNavHome = (_a) => {
    var { 'aria-label': ariaLabel = 'Home', tooltipContent = 'Home', className, onClick } = _a, props = __rest(_a, ['aria-label', "tooltipContent", "className", "onClick"]);
    const buttonRef = useRef(null);
    return (_jsx("div", Object.assign({ className: css(styles.compassNav + '-home', className) }, props, { children: _jsx(Tooltip, { content: tooltipContent, position: "left", aria: "none", "aria-live": "off", triggerRef: buttonRef, children: _jsx(Button, { isCircle: true, variant: "plain", icon: _jsx(CompassHomeIcon, {}), "aria-label": ariaLabel, onClick: onClick, ref: buttonRef }) }) })));
};
CompassNavHome.displayName = 'CompassNavHome';
//# sourceMappingURL=CompassNavHome.js.map