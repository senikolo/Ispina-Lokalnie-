export const MAX_LIVE_AGE=180;
export function warsawDate(unix){return new Intl.DateTimeFormat('sv-SE',{timeZone:'Europe/Warsaw',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(unix*1000));}
export function applyRealtime(rows,feed,now){
 const stamp=Number(feed?.header?.timestamp);
 const fresh=feed?.header?.incrementality==='FULL_DATASET' && Number.isFinite(stamp) && now-stamp>=-30 && now-stamp<=MAX_LIVE_AGE;
 const updates=new Map();
 if(fresh)for(const e of feed.entity||[]){const t=e.trip_update;if(!e.is_deleted&&t?.trip?.trip_id){const id=t.trip.trip_id;if(updates.has(id))updates.set(id,null);else updates.set(id,t);}}
 return rows.map(r=>{
  const out={...r,kind:'scheduled',expected:null,live_until:null};
  if(!fresh||!r.trip_id||r.service_date!==warsawDate(now).replaceAll('-',''))return out;
  const t=updates.get(r.trip_id);if(!t)return out;
  const td=t.trip, ts=Number(t.timestamp??stamp);
  if(!Number.isFinite(ts)||now-ts>MAX_LIVE_AGE||ts>now+30||td.route_id!==r.route_id||(td.start_date&&td.start_date!==r.service_date))return out;
  const expires=Math.min(stamp,ts)+MAX_LIVE_AGE;
  if(td.schedule_relationship==='CANCELED')return {...out,kind:'cancelled',live_until:expires};
  if(td.schedule_relationship&&td.schedule_relationship!=='SCHEDULED')return out;
  const stops=t.stop_time_update||[];
  const candidates=stops.filter(s=>Number.isInteger(s.stop_sequence)&&s.stop_sequence<=r.sequence).sort((a,b)=>a.stop_sequence-b.stop_sequence);
  let delay=null,exact=null;
  for(const s of candidates){
   const rel=s.schedule_relationship||'SCHEDULED';
   if(s.stop_sequence===r.sequence&&s.stop_id&&s.stop_id!==r.stop_id)return out;
   if(rel==='NO_DATA'){delay=null;exact=null;continue;}
   if(rel==='SKIPPED'){if(s.stop_sequence===r.sequence)return {...out,kind:'skipped',live_until:expires};continue;}
   if(rel!=='SCHEDULED'){delay=null;exact=null;continue;}
   const ev=s.departure??s.arrival;
   if(!ev){delay=null;exact=null;continue;}
   if(ev.uncertainty!==undefined&&Number(ev.uncertainty)>120){delay=null;exact=null;continue;}
   if(Number.isFinite(ev.delay))delay=ev.delay;else delay=null;
   exact=s.stop_sequence===r.sequence&&Number.isFinite(Number(ev.time))?Number(ev.time):null;
  }
  const prediction=exact??(delay===null?null:r.gtfs_scheduled+delay);
  if(prediction===null||!Number.isFinite(prediction)||Math.abs(prediction-r.gtfs_scheduled)>3600)return out;
  return {...out,kind:'live',expected:prediction,live_until:expires,observed_at:ts,propagated:exact===null};
 });
}
