export interface IMGWAlert { id:string; kind:string; level:number; from:number; to:number; text:string }
export interface IMGWData {warnings: IMGWAlert[]; checkedAt:number}
export const IMGW_URL:string;
export const BOCHNIA_TERYT:string;
export const IMGW_SOURCE:string;
export function parseWarsawTime(value:unknown):number;
export function localImgwWarnings(data:unknown,nowMs?:number):IMGWAlert[];
export function createImgwLoader(fetcher?:typeof fetch,clock?:()=>number):()=>Promise<IMGWData|null>;
