/// <reference types="react" />
/** The main Hero component that allows adjusting of its background images and gradients in different color modes (such as light and dark). */
export interface HeroProps extends Omit<React.HTMLProps<HTMLDivElement>, 'content'> {
    /** Content of the hero */
    children?: React.ReactNode;
    /** Additional classes added to the hero */
    className?: string;
    /** Light theme background image path of the hero */
    backgroundSrcLight?: string;
    /** Dark theme background image path of the hero */
    backgroundSrcDark?: string;
    /** Light theme gradient of the hero, taking any valid CSS color values for each stop property. */
    gradientLight?: {
        stop1?: string;
        stop2?: string;
        stop3?: string;
    };
    /** Dark theme gradient of the hero, taking any valid CSS color values for each stop property. */
    gradientDark?: {
        stop1?: string;
        stop2?: string;
        stop3?: string;
    };
    /** @beta Flag indicating the hero has glass styling when glass theme is applied. */
    isGlass?: boolean;
    /** Modifies the width of the hero body. */
    bodyWidth?: string;
    /** Modifies the max-width of the hero body. */
    bodyMaxWidth?: string;
}
export declare const Hero: React.FunctionComponent<HeroProps>;
//# sourceMappingURL=Hero.d.ts.map