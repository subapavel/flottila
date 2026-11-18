/// <reference types="react" />
/** A wrapper component to pass a PatternFly Hero component into. */
export interface CompassHeroProps extends Omit<React.HTMLProps<HTMLDivElement>, 'content'> {
    /** Content of the hero */
    children?: React.ReactNode;
    /** Additional classes added to the hero */
    className?: string;
}
export declare const CompassHero: React.FunctionComponent<CompassHeroProps>;
//# sourceMappingURL=CompassHero.d.ts.map