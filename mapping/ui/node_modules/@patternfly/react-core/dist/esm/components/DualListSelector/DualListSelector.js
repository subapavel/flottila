import { __rest } from "tslib";
import { jsx as _jsx } from "react/jsx-runtime";
import styles from '@patternfly/react-styles/css/components/DualListSelector/dual-list-selector.mjs';
import { css } from '@patternfly/react-styles';
import { useSSRSafeId, useOUIAProps, useHasAnimations } from '../../helpers';
import { DualListSelectorContext } from './DualListSelectorContext';
export const DualListSelector = (_a) => {
    var { className, children, id, isTree = false, hasAnimations: hasAnimationsProp, ouiaId, ouiaSafe = true } = _a, props = __rest(_a, ["className", "children", "id", "isTree", "hasAnimations", "ouiaId", "ouiaSafe"]);
    const hasAnimations = useHasAnimations(hasAnimationsProp);
    const randomId = useSSRSafeId();
    const ouiaProps = useOUIAProps(DualListSelector.displayName, ouiaId, ouiaSafe);
    return (_jsx(DualListSelectorContext.Provider, { value: { isTree, hasAnimations }, children: _jsx("div", Object.assign({ className: css(styles.dualListSelector, hasAnimations && isTree && styles.modifiers.animateExpand, className), id: id || randomId }, props, ouiaProps, { children: children })) }));
};
DualListSelector.displayName = 'DualListSelector';
//# sourceMappingURL=DualListSelector.js.map