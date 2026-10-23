import {cachedLoader,bounded} from '../../../lib/upstream.mjs';
import {applyRealtime} from '../../../lib/realtime.mjs';
export const dynamic='force-dynamic';
const DATA='https://raw.githubusercontent.com/senikolo/Ispina-Lokalnie-/main/ispina-rozklady/out/tablica.json';
const INDEX='https://gtfs.kolejemalopolskie.com.pl/';
const schedule=cachedLoader(async()=>{
 const value=JSON.parse(await bounded(DATA,1500000));
 if(value.schema!==1||!Array.isArray(value.days))throw new Error('Invalid schedule');
 return value;
},60000);
const live=cachedLoader(async()=>{
 const html=await bounded(INDEX,1000000);
 const matches=[...html.matchAll(/<a\b[^>]*href="([^"]+)"[^>]*>\s*trip_updates\.json\s*<\/a>/g)];
 if(matches.length!==1)throw new Error('Unknown realtime link');
 const url=new URL(matches[0][1].replaceAll('&amp;','&'),INDEX);
 if(url.origin!==new URL(INDEX).origin||!url.searchParams.has('download'))throw new Error('Invalid realtime origin');
 return JSON.parse(await bounded(url.href,2000000));
},20000);
export async function GET(){
 let now=Math.floor(Date.now()/1000);
 try{
  const [sResult,rResult]=await Promise.allSettled([schedule(),live()]);
  if(sResult.status!=='fulfilled'){console.error('schedule source failed',String(sResult.reason));}if(rResult.status!=='fulfilled')console.error('realtime source failed',String(rResult.reason));
  if(sResult.status!=='fulfilled')throw new Error('Nie udało się pobrać sprawdzonego rozkładu.');
  now=Math.floor(Date.now()/1000);
  const s=sResult.value,generated=Date.parse(s.generated_at)/1000;
  if(!Number.isFinite(generated)||now-generated>30*3600||generated>now+300||s.error)throw new Error('Rozkład wymaga aktualizacji. Godziny zostały ukryte.');
  const rt=rResult.status==='fulfilled'?rResult.value:null;
  const liveAge=now-Number(rt?.header?.timestamp);
  return Response.json({...s,server_now:now,schedule_valid_until:generated+30*3600,live_available:rt?.header?.incrementality==='FULL_DATASET'&&liveAge>=-30&&liveAge<=180,
   days:s.days.map((d:any)=>({...d,departures:applyRealtime(d.departures,rt,now).map((r:any)=>({line:r.line,time:r.time,scheduled:r.scheduled,direction:r.direction,destination:r.destination,trip_id:r.trip_id,kind:r.kind,expected:r.expected,live_until:r.live_until,propagated:r.propagated}))}))},{headers:{'Cache-Control':'public, max-age=0, s-maxage=20, must-revalidate','X-Content-Type-Options':'nosniff'}});
 }catch(e){return Response.json({error:e instanceof Error?e.message:'Dane chwilowo niedostępne',server_now:now,days:[]},{status:503,headers:{'Cache-Control':'no-store'}});}
}
