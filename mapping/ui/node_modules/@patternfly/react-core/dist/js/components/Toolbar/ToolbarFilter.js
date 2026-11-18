"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ToolbarFilter = void 0;
const tslib_1 = require("tslib");
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const ReactDOM = tslib_1.__importStar(require("react-dom"));
const ToolbarItem_1 = require("./ToolbarItem");
const ToolbarUtils_1 = require("./ToolbarUtils");
const Label_1 = require("../Label");
const getCategoryKey = (categoryName) => {
    if (typeof categoryName === 'string') {
        return categoryName;
    }
    return categoryName.key;
};
class ToolbarFilter extends react_1.Component {
    constructor(props) {
        super(props);
        this.state = {
            isMounted: false
        };
    }
    componentDidMount() {
        this.context.updateNumberFilters(getCategoryKey(this.props.categoryName), this.props.labels.length);
        this.setState({ isMounted: true });
    }
    componentDidUpdate() {
        this.context.updateNumberFilters(getCategoryKey(this.props.categoryName), this.props.labels.length);
    }
    componentWillUnmount() {
        this.context.updateNumberFilters(getCategoryKey(this.props.categoryName), 0);
    }
    render() {
        var _a, _b;
        const _c = this.props, { children, labels, deleteLabelGroup, deleteLabel, labelGroupExpandedText, labelGroupCollapsedText, categoryName, showToolbarItem, isExpanded, expandableLabelContainerRef } = _c, props = tslib_1.__rest(_c, ["children", "labels", "deleteLabelGroup", "deleteLabel", "labelGroupExpandedText", "labelGroupCollapsedText", "categoryName", "showToolbarItem", "isExpanded", "expandableLabelContainerRef"]);
        const { isExpanded: managedIsExpanded, labelGroupContentRef } = this.context;
        const _isExpanded = isExpanded !== undefined ? isExpanded : managedIsExpanded;
        const categoryKey = getCategoryKey(categoryName);
        const labelGroup = labels.length ? ((0, jsx_runtime_1.jsx)(ToolbarItem_1.ToolbarItem, { variant: "label-group", children: (0, jsx_runtime_1.jsx)(Label_1.LabelGroup, { categoryName: typeof categoryName === 'string' ? categoryName : categoryName.name, isClosable: deleteLabelGroup !== undefined, onClick: () => deleteLabelGroup(categoryName), collapsedText: labelGroupCollapsedText, expandedText: labelGroupExpandedText, children: labels.map((label) => typeof label === 'string' ? ((0, jsx_runtime_1.jsx)(Label_1.Label, { variant: "outline", onClose: () => deleteLabel === null || deleteLabel === void 0 ? void 0 : deleteLabel(categoryKey, label), children: label }, label)) : ((0, jsx_runtime_1.jsx)(Label_1.Label, { onClose: () => deleteLabel === null || deleteLabel === void 0 ? void 0 : deleteLabel(categoryKey, label), children: label.node }, label.key))) }, categoryKey) })) : null;
        if (!_isExpanded && this.state.isMounted) {
            const collapsedLabelPortalTarget = (_b = (_a = labelGroupContentRef === null || labelGroupContentRef === void 0 ? void 0 : labelGroupContentRef.current) === null || _a === void 0 ? void 0 : _a.firstElementChild) !== null && _b !== void 0 ? _b : null;
            return ((0, jsx_runtime_1.jsxs)(react_1.Fragment, { children: [showToolbarItem && (0, jsx_runtime_1.jsx)(ToolbarItem_1.ToolbarItem, Object.assign({}, props, { children: children })), collapsedLabelPortalTarget !== null && ReactDOM.createPortal(labelGroup, collapsedLabelPortalTarget)] }));
        }
        return ((0, jsx_runtime_1.jsx)(ToolbarUtils_1.ToolbarContentContext.Consumer, { children: ({ labelContainerRef }) => {
                var _a;
                const labelContainer = (_a = labelContainerRef === null || labelContainerRef === void 0 ? void 0 : labelContainerRef.current) !== null && _a !== void 0 ? _a : null;
                return ((0, jsx_runtime_1.jsxs)(react_1.Fragment, { children: [showToolbarItem && (0, jsx_runtime_1.jsx)(ToolbarItem_1.ToolbarItem, Object.assign({}, props, { children: children })), labelContainer !== null && ReactDOM.createPortal(labelGroup, labelContainer), expandableLabelContainerRef &&
                            expandableLabelContainerRef.current &&
                            ReactDOM.createPortal(labelGroup, expandableLabelContainerRef.current)] }));
            } }));
    }
}
exports.ToolbarFilter = ToolbarFilter;
ToolbarFilter.displayName = 'ToolbarFilter';
ToolbarFilter.contextType = ToolbarUtils_1.ToolbarContext;
ToolbarFilter.defaultProps = {
    labels: [],
    showToolbarItem: true
};
//# sourceMappingURL=ToolbarFilter.js.map