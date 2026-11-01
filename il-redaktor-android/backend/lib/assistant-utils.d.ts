export type GroundedSource = { title: string; url: string };

export declare const REQUIRED_BACKEND_ENV: readonly ['GOOGLE_GENERATIVE_AI_API_KEY'];
export declare function getBackendRedisConfig(env: Record<string, string | undefined>): { url: string; token: string };
export declare function getBackendReadiness(env: Record<string, string | undefined>): { ready: boolean; missing: string[] };

export declare const RESEARCH_PROFILES: readonly {
  readonly id: string;
  readonly title: string;
  readonly focus: string;
}[];

export declare function normalizeGroundedSources(items: unknown): GroundedSource[];
export declare function extractGroundingQueries(metadata: unknown): string[];
export declare function rateLimitExceeded(userSuccess: boolean, ipSuccess: boolean): boolean;
