import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { CompassMainHeaderContent } from './CompassMainHeaderContent';
import { CompassMainHeaderTitle } from './CompassMainHeaderTitle';
import { CompassMainHeaderToolbar } from './CompassMainHeaderToolbar';
import { Panel, PanelMain, PanelMainBody } from '../Panel';
import styles from '@patternfly/react-styles/css/components/Compass/compass.mjs';
import { css } from '@patternfly/react-styles';
export const CompassMainHeader = (_a) => {
    var { className, title, toolbar, children, panelProps } = _a, props = __rest(_a, ["className", "title", "toolbar", "children", "panelProps"]);
    const _content = title !== undefined || toolbar !== undefined ? (_jsx(Panel, Object.assign({}, panelProps, { children: _jsx(PanelMain, { children: _jsx(PanelMainBody, { children: _jsxs(CompassMainHeaderContent, { children: [title && _jsx(CompassMainHeaderTitle, { children: title }), toolbar && _jsx(CompassMainHeaderToolbar, { children: toolbar })] }) }) }) }))) : (children);
    return (_jsx("div", Object.assign({ className: css(`${styles.compass}__main-header`, className) }, props, { children: _content })));
};
CompassMainHeader.displayName = 'CompassMainHeader';
//# sourceMappingURL=CompassMainHeader.js.map