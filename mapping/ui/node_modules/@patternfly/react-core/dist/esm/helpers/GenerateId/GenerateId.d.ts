/// <reference types="react" />
export interface GenerateIdProps {
    /** String to prefix the id with */
    prefix?: string;
    /** Component to be rendered with the generated id */
    children(id: string): React.ReactNode;
    /** @deprecated Has no effect. Kept for backward compatibility. */
    isRandom?: boolean;
}
declare const GenerateId: {
    ({ prefix, children }: GenerateIdProps): import("react").ReactElement<any, string | import("react").JSXElementConstructor<any>>;
    displayName: string;
};
export { GenerateId };
//# sourceMappingURL=GenerateId.d.ts.map