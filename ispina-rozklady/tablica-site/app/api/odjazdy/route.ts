import {applyRealtime} from '../../../lib/realtime.mjs';
export const dynamic='force-dynamic';
const DATA='https://raw.githubusercontent.com/senikolo/Ispina-Lokalnie-/main/ispina-rozklady/out/tablica.json';
const INDEX='https://gtfs.kolejemalopolskie.com.pl/';
let scheduleCache:any=null,liveCache:any=null;
async function bounded(url:string,max:number){
 const r=await fetch(url,{signal:AbortSignal.timeout(12000),redirect:'manual',headers:{'User-Agent':'IspinaLokalnieBoard/1.0'},cache:'no-store'});
 if(!r.ok)throw new Error('Upstream '+r.status);
 const text=await r.text();if(text.length>max)throw new Error('Upstream too large');return text;
}
async function schedule(){
 if(scheduleCache&&Date.now()-scheduleCache.at<60000)return scheduleCache.value;
 const value=JSON.parse(await bounded(DATA,1500000));
 if(value.schema!==1||!Array.isArray(value.days))throw new Error('Invalid schedule');
 scheduleCache={at:Date.now(),value};return value;
}
async function live(){
 if(liveCache&&Date.now()-liveCache.at<20000)return liveCache.value;
 const html=await bounded(INDEX,1000000);
 const matches=[...html.matchAll(/<a\b[^>]*href="([^"]+)"[^>]*>\s*trip_updates\.json\s*<\/a>/g)];
 if(matches.length!==1)throw new Error('Unknown realtime link');
 const url=new URL(matches[0][1].replaceAll('&amp;','&'),INDEX);
 if(url.origin!==new URL(INDEX).origin||!url.searchParams.has('download'))throw new Error('Invalid realtime origin');
 const value=JSON.parse(await bounded(url.href,2000000));liveCache={at:Date.now(),value};return value;
}
export async function GET(){
 const now=Math.floor(Date.now()/1000);
 try{
  const [sResult,rResult]=await Promise.allSettled([schedule(),live()]);
  if(sResult.status!=='fulfilled'){console.error('schedule source failed',String(sResult.reason));}if(rResult.status!=='fulfilled')console.error('realtime source failed',String(rResult.reason));
  if(sResult.status!=='fulfilled')throw new Error('Nie udało się pobrać sprawdzonego rozkładu.');
  const s=sResult.value,generated=Date.parse(s.generated_at)/1000;
  if(!Number.isFinite(generated)||now-generated>30*3600||generated>now+300||s.error)throw new Error('Rozkład wymaga aktualizacji. Godziny zostały ukryte.');
  const rt=rResult.status==='fulfilled'?rResult.value:null;
  const liveAge=now-Number(rt?.header?.timestamp);
  return Response.json({...s,server_now:now,schedule_valid_until:generated+30*3600,live_available:liveAge>=-30&&liveAge<=180,
   days:s.days.map((d:any)=>({...d,departures:applyRealtime(d.departures,rt,now)}))},{headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
 }catch(e){return Response.json({error:e instanceof Error?e.message:'Dane chwilowo niedostępne',server_now:now,days:[]},{status:503,headers:{'Cache-Control':'no-store'}});}
}
