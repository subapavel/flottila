import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { forwardRef } from 'react';
import { css } from '@patternfly/react-styles';
import styles from '@patternfly/react-styles/css/components/Table/table-scrollable.mjs';
const InnerScrollContainerBase = (_a) => {
    var { children, className, innerRef } = _a, props = __rest(_a, ["children", "className", "innerRef"]);
    return (_jsx("div", Object.assign({ ref: innerRef, className: css(className, styles.scrollInnerWrapper) }, props, { children: children })));
};
export const InnerScrollContainer = forwardRef((props, ref) => (_jsx(InnerScrollContainerBase, Object.assign({ innerRef: ref }, props))));
InnerScrollContainer.displayName = 'InnerScrollContainer';
//# sourceMappingURL=InnerScrollContainer.js.map