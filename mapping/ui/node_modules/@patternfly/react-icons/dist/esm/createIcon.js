import { __rest } from "tslib";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Component } from 'react';
let currentId = 0;
/** Returns svg path(s) from a given svg data object. */
function getSvgPaths(svgPathData) {
    return svgPathData && Array.isArray(svgPathData) ? (svgPathData.map((pathObject, index) => (_jsx("path", { className: pathObject.className, d: pathObject.path }, `${pathObject.path}-${index}`)))) : (_jsx("path", { d: svgPathData }));
}
const createInnerSvg = (icon, iconClassName) => {
    const { xOffset, yOffset, width, height, svgPathData, svgClassName } = icon !== null && icon !== void 0 ? icon : {};
    const _xOffset = xOffset !== null && xOffset !== void 0 ? xOffset : 0;
    const _yOffset = yOffset !== null && yOffset !== void 0 ? yOffset : 0;
    const viewBox = [_xOffset, _yOffset, width, height].join(' ');
    const classNames = [];
    if (svgClassName) {
        classNames.push(svgClassName);
    }
    if (iconClassName) {
        classNames.push(iconClassName);
    }
    return (_jsx("svg", { viewBox: viewBox, className: classNames.join(' '), children: getSvgPaths(svgPathData) }));
};
/**
 * Internal API for creating icons. Subject to change at any time. Please use `createIcon` instead.
 * @internal
 */
export function createIconBase({ name, icon, rhUiIcon = null }) {
    var _a;
    if (icon == null) {
        // eslint-disable-next-line no-console
        console.warn(`@patternfly/react-icons: createIconBase is missing an \`icon\` definition (name: ${name !== null && name !== void 0 ? name : 'unknown'}).`);
    }
    return _a = class SVGIcon extends Component {
            constructor() {
                super(...arguments);
                this.id = `icon-title-${currentId++}`;
            }
            render() {
                const _b = this.props, { title, className: propsClassName, set, noDefaultStyle } = _b, props = __rest(_b, ["title", "className", "set", "noDefaultStyle"]);
                const hasTitle = Boolean(title);
                const classNames = ['pf-v6-svg'];
                if (propsClassName) {
                    classNames.push(propsClassName);
                }
                if (set === 'rh-ui' && rhUiIcon === null) {
                    // eslint-disable-next-line no-console
                    console.warn(`Set "rh-ui" was provided for ${name}, but no rh-ui icon data exists for this icon. The default icon will be rendered.`);
                }
                if ((set === undefined && rhUiIcon === null) || set !== undefined) {
                    const iconData = set !== undefined && set === 'rh-ui' && rhUiIcon !== null ? rhUiIcon : icon;
                    const { xOffset, yOffset, width, height, svgPathData, svgClassName } = iconData !== null && iconData !== void 0 ? iconData : {};
                    const _xOffset = xOffset !== null && xOffset !== void 0 ? xOffset : 0;
                    const _yOffset = yOffset !== null && yOffset !== void 0 ? yOffset : 0;
                    const viewBox = [_xOffset, _yOffset, width, height].join(' ');
                    if (svgClassName && !noDefaultStyle) {
                        classNames.push(svgClassName);
                    }
                    return (_jsxs("svg", Object.assign({ className: classNames.join(' '), fill: "currentColor", viewBox: viewBox, "aria-labelledby": hasTitle ? this.id : null, "aria-hidden": hasTitle ? null : true, role: "img", width: "1em", height: "1em" }, props, { children: [hasTitle && _jsx("title", { id: this.id, children: title }), getSvgPaths(svgPathData)] })));
                }
                return (_jsxs("svg", Object.assign({ className: classNames.join(' '), fill: "currentColor", "aria-labelledby": hasTitle ? this.id : null, "aria-hidden": hasTitle ? null : true, role: "img", width: "1em", height: "1em" }, props, { children: [hasTitle && _jsx("title", { id: this.id, children: title }), icon && createInnerSvg(icon, 'pf-v6-icon-default'), rhUiIcon && createInnerSvg(rhUiIcon, 'pf-v6-icon-rh-ui')] })));
            }
        },
        _a.displayName = name,
        _a.defaultProps = {
            noDefaultStyle: false
        },
        _a;
}
/** Public API for creating icons. Will be maintained up to breaking releases. */
export function createIcon(props) {
    const { rhUiIcon = null } = props, rest = __rest(props, ["rhUiIcon"]);
    const icon = {
        name: rest.name,
        width: rest.width,
        height: rest.height,
        xOffset: rest.xOffset,
        yOffset: rest.yOffset,
        svgClassName: rest.svgClassName,
        svgPathData: rest.svgPath
    };
    return createIconBase({ name: icon.name, icon, rhUiIcon });
}
//# sourceMappingURL=createIcon.js.map