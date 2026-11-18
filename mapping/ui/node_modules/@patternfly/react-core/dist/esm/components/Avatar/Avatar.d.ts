/// <reference types="react" />
export interface AvatarProps extends React.DetailedHTMLProps<React.ImgHTMLAttributes<HTMLImageElement>, HTMLImageElement> {
    /** Content rendered inside the avatar, such as custom svgs or icons. */
    children?: React.ReactNode;
    /** Additional classes added to the avatar. */
    className?: string;
    /** Specifies the URL of the image for the avatar. */
    src?: string;
    /** Specifies the alternate text of the image for the avatar. Will instead set the aria-label when using children or initials; to hide the avatar
     * from assistive technologies when passing children or initials, pass an empty string to the alt prop.
     */
    alt: string;
    /** Flag to indicate the avatar should have a border. */
    isBordered?: boolean;
    /** Size variant of avatar. */
    size?: 'sm' | 'md' | 'lg' | 'xl';
    /** Color of the avatar. */
    color?: 'red' | 'orangered' | 'orange' | 'yellow' | 'green' | 'teal' | 'blue' | 'purple' | 'gray';
    /** Initials of the avatar. */
    initials?: string;
}
export declare const Avatar: React.FunctionComponent<AvatarProps>;
//# sourceMappingURL=Avatar.d.ts.map