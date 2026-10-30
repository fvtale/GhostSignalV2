type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
export declare class WebGame {
    constructor(missionJson: string, routeJson: Nullable<string>, catalogJson: Nullable<string>, soundtrackId: Nullable<string>, completions: number);
    missionJson(): string;
    start(nowMs: number, progressJson: Nullable<string>): string;
    position(lat: number, lon: number, accuracyM: number, timeMs: number, speedMps: Nullable<number>, courseDeg: Nullable<number>, simulated: boolean): string;
    phoneHeading(deg: number, nowMs: number, inHand: boolean): void;
    listenerHeading(nowMs: number): Nullable<number>;
    listenerPitch(nowMs: number): Nullable<number>;
    tick(nowMs: number): string;
    input(action: string, nowMs: number): string;
    heard(token: string, text: string, isFinal: boolean, confidence: number): string;
    listenEnded(token: string): string;
    speechFinished(token: string): string;
    rhythmPlayed(token: string, nowMs: number): string;
    characterReplied(requestId: number, text: Nullable<string>): string;
    recordingFinished(path: Nullable<string>): string;
    broadcast(speaker: string, text: string): string;
    forceLock(): string;
    forceSolve(): string;
    jumpTo(signalIndex: number): string;
    scene(): string;
    state(): string;
    progressJson(): string;
}
/** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
export declare namespace WebGame.$metadata$ {
    const constructor: abstract new () => WebGame;
}
export declare function soundtrackChoices(missionJson: string, catalogJson: Nullable<string>, completions: number): string;
export declare function unlockedByNextCompletion(missionJson: string, catalogJson: Nullable<string>, completions: number): string;
export declare function validateMission(missionJson: string): string;
export declare function distanceAndBearing(lat1: number, lon1: number, lat2: number, lon2: number): Array<number>;