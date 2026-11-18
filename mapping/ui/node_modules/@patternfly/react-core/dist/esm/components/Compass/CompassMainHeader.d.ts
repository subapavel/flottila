/// <reference types="react" />
import { PanelProps } from '../Panel';
/** The wrapper component for header content in the main Compass area. When building out a custom implementation,
 * you should ensure any content within the main header is rendered inside a Panel, PanelMain, PanelMainBody stack and main header content wrappers.
 */
export interface CompassMainHeaderProps extends Omit<React.HTMLProps<HTMLDivElement>, 'title'> {
    /** Custom main header content. To opt into a default styling, use the title and toolbar props instead. */
    children?: React.ReactNode;
    /** Additional classes added to the main header */
    className?: string;
    /** Styled title. If title or toolbar is provided, the children will be ignored. */
    title?: React.ReactNode;
    /** Styled toolbar. If title or toolbar is provided, the children will be ignored. */
    toolbar?: React.ReactNode;
    /** Additional props passed to the Panel that wraps the main header content when using the title or toolbar props. When using the
     * children prop, you should pass your own Panel.
     */
    panelProps?: Omit<PanelProps, 'children'>;
}
export declare const CompassMainHeader: React.FunctionComponent<CompassMainHeaderProps>;
//# sourceMappingURL=CompassMainHeader.d.ts.map