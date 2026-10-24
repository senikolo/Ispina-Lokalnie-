import type {Forecast} from './weather.mjs';
import type {IMGWAlert} from './imgw-warnings.mjs';
export interface SmartEntry {kind:string; text:string; source?:string}
export interface SmartBrief {period:string; entries:SmartEntry[]}
export function summarizeWeatherSmart(data:Forecast,nowMs?:number,officialWarnings?:unknown[]):SmartBrief;
