import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import { forwardRef } from 'react';
import { css } from '@patternfly/react-styles';
import styles from '@patternfly/react-styles/css/components/Table/table.mjs';
const TfootBase = (_a) => {
    var { children, className, innerRef } = _a, props = __rest(_a, ["children", "className", "innerRef"]);
    return (_jsx("tfoot", Object.assign({ className: css(styles.tableTfoot, className), ref: innerRef }, props, { children: children })));
};
export const Tfoot = forwardRef((props, ref) => (_jsx(TfootBase, Object.assign({}, props, { innerRef: ref }))));
Tfoot.displayName = 'Tfoot';
//# sourceMappingURL=Tfoot.js.map