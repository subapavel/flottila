import { type ComponentClass } from 'react';
export interface SVGPathObject {
    path: string;
    className?: string;
}
/** Icon data format */
export interface IconData {
    name?: string;
    width: number;
    height: number;
    xOffset?: number;
    yOffset?: number;
    svgClassName?: string;
    svgPathData?: string | SVGPathObject[];
}
/** Public API props */
export interface IconDefinition {
    name?: string;
    width: number;
    height: number;
    xOffset?: number;
    yOffset?: number;
    svgPath?: string | SVGPathObject[];
    svgClassName?: string;
    rhUiIcon?: IconData | null;
}
/** Additional svg props */
export interface SVGIconProps extends Omit<React.HTMLProps<SVGElement>, 'ref'> {
    title?: string;
    className?: string;
    /** Indicates the icon should render using alternate svg data for the unified theme */
    set?: 'default' | 'rh-ui';
    /** Indicates the icon should render without its default styling specified in its IconDefinition.svgClassName. */
    noDefaultStyle?: boolean;
}
/** Public API for creating icons. Will be maintained up to breaking releases. */
export declare function createIcon(props: IconDefinition): ComponentClass<SVGIconProps>;
//# sourceMappingURL=createIcon.d.ts.map