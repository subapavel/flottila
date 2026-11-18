import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useContext } from 'react';
import { css } from '@patternfly/react-styles';
import styles from '@patternfly/react-styles/css/components/Card/card.mjs';
import { CardContext } from './Card';
import { CardSubtitle } from './CardSubtitle';
export const CardTitle = (_a) => {
    var { children, className, component = 'div', subtitle } = _a, props = __rest(_a, ["children", "className", "component", "subtitle"]);
    const { cardId } = useContext(CardContext);
    const Component = component;
    const titleId = cardId ? `${cardId}-title` : '';
    const subtitleId = cardId ? `${cardId}-subtitle` : '';
    return (_jsxs("div", { className: css(styles.cardTitle), children: [_jsx(Component, Object.assign({ className: css(styles.cardTitleText, className), id: titleId || undefined }, props, { children: children })), subtitle && _jsx(CardSubtitle, { id: subtitleId, children: subtitle })] }));
};
CardTitle.displayName = 'CardTitle';
//# sourceMappingURL=CardTitle.js.map