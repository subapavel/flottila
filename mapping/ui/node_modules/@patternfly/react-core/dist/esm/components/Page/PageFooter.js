import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/Page/page.mjs';
import { css } from '@patternfly/react-styles';
export const PageFooter = (_a) => {
    var { className, children, component = 'footer' } = _a, props = __rest(_a, ["className", "children", "component"]);
    const Component = component;
    return (_jsx(Component, Object.assign({}, props, { className: css(styles.pageFooter, className), children: children })));
};
PageFooter.displayName = 'PageFooter';
//# sourceMappingURL=PageFooter.js.map