const TZ = 'Europe/Warsaw';
const LAT = 50.1133, LON = 20.3774;
const HOUR = 3600e3;
const number = (v, min, max) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
const dateOf = t => new Intl.DateTimeFormat('en-CA', {timeZone:TZ, year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(t));
const nextDay = (day,n) => {const d=new Date(day+'T12:00:00Z');d.setUTCDate(d.getUTCDate()+n);return d.toISOString().slice(0,10);};
export const metnoUrl = () => new URL(`https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=${LAT}&lon=${LON}`);
export function metnoCode(symbol) {
  const name=String(symbol || '').replace(/_(day|night|polartwilight)$/,'');
  if(name.includes('thunder'))return 95;
  if(name.includes('snow'))return 73;
  if(name.includes('sleet'))return 69;
  if(name.includes('rain'))return name.includes('heavy')?65:name.includes('light')?61:63;
  return ({clearsky:0,fair:1,partlycloudy:2,cloudy:3,fog:45})[name] ?? null;
}
export function normalizeMetno(raw, now=Date.now()) {
  const coord=raw?.geometry?.coordinates, meta=raw?.properties?.meta;
  const updatedAt=Date.parse(meta?.updated_at);
  if(raw?.geometry?.type!=='Point' || !number(coord?.[0],LON-.001,LON+.001) || !number(coord?.[1],LAT-.001,LAT+.001))throw new Error('Wrong MET Norway location');
  if(!Number.isFinite(updatedAt) || updatedAt>now+5*60000 || now-updatedAt>18*HOUR)throw new Error('Invalid MET Norway model age');
  const units=meta?.units;
  if(units?.air_temperature!=='celsius' || units?.wind_speed!=='m/s' || units?.precipitation_amount!=='mm' || units?.cloud_area_fraction!=='%')throw new Error('Wrong MET Norway units');
  const all=raw?.properties?.timeseries;
  if(!Array.isArray(all))throw new Error('Incomplete MET Norway forecast');
  const today=dateOf(now), lastDay=nextDay(today,2);
  const rows=[];
  let previous=-Infinity;
  for(const item of all){
    const time=Date.parse(item?.time);
    if(!Number.isFinite(time) || time<=previous)throw new Error('Invalid MET Norway chronology');
    previous=time;
    if(time<now-HOUR || dateOf(time)>lastDay)continue;
    const d=item?.data?.instant?.details;
    const period=item?.data?.next_1_hours || item?.data?.next_6_hours;
    const hours=item?.data?.next_1_hours?1:6;
    const symbol=period?.summary?.symbol_code;
    const code=metnoCode(symbol);
    if(!number(d?.air_temperature,-70,60) || !number(d?.wind_speed,0,100) || !number(d?.cloud_area_fraction,0,100) || code===null || !number(period?.details?.precipitation_amount,0,1000))throw new Error('Invalid MET Norway values');
    rows.push({time:time/1000,temperature:d.air_temperature,wind:d.wind_speed*3.6,cloud:d.cloud_area_fraction,code,symbol,
      apparent:units?.apparent_air_temperature==='celsius' && number(d.apparent_air_temperature,-100,80)?d.apparent_air_temperature:null,
      precipitation:period.details.precipitation_amount,hours});
  }
  const current=rows.filter(r=>r.time*1000<=now).at(-1);
  if(!current || now-current.time*1000>HOUR)throw new Error('Incomplete MET Norway current forecast');
  const future=rows.filter(r=>r.time>=current.time);
  if(future.some((r,i)=>i && r.time-future[i-1].time>6*3600))throw new Error('Incomplete MET Norway intervals');
  const days=[0,1,2].map(i=>{
    const day=nextDay(today,i), entries=future.filter(r=>dateOf(r.time*1000)===day);
    if(!entries.length || (i>0 && (entries.length<4 || entries.at(-1).time-entries[0].time<18*3600)))throw new Error('Incomplete MET Norway days');
    const representative=entries.reduce((a,b)=>Math.abs(new Date(b.time*1000).getUTCHours()-11)<Math.abs(new Date(a.time*1000).getUTCHours()-11)?b:a);
    return {date:day,min:Math.min(...entries.map(r=>r.temperature)),max:Math.max(...entries.map(r=>r.temperature)),code:representative.code};
  });
  const offset=Number(new Intl.DateTimeFormat('en-US',{timeZone:TZ,timeZoneName:'longOffset'}).format(new Date(now)).match(/GMT([+-]\d+)/)?.[1] || 0)*3600;
  return {source:'metno',modelUpdatedAt:updatedAt,latitude:LAT,longitude:LON,timezone:TZ,utc_offset_seconds:offset,
    current:{time:current.time,temperature_2m:current.temperature,apparent_temperature:current.apparent,precipitation:current.precipitation,
      precipitation_hours:current.hours,wind_speed_10m:current.wind,weather_code:current.code,is_day:current.symbol.endsWith('_night')?0:1},
    daily:{time:days.map(d=>Date.parse(d.date+'T00:00:00Z')/1000-offset),temperature_2m_max:days.map(d=>d.max),temperature_2m_min:days.map(d=>d.min),
      precipitation_probability_max:[null,null,null],weather_code:days.map(d=>d.code)},
    hourly:{time:future.map(r=>r.time),temperature_2m:future.map(r=>r.temperature),precipitation:future.map(r=>r.precipitation),precipitation_hours:future.map(r=>r.hours),
      precipitation_probability:future.map(()=>null),weather_code:future.map(r=>r.code),wind_speed_10m:future.map(r=>r.wind),wind_gusts_10m:future.map(()=>null),cloud_cover:future.map(r=>r.cloud)}};
}
export function validateMetnoForecast(data,now=Date.now()) {
  if(data?.source!=='metno' || !Number.isFinite(data.modelUpdatedAt) || now-data.modelUpdatedAt>18*HOUR ||
    data.current.time*1000>now+5*60000 || now-data.current.time*1000>HOUR || dateOf(data.current.time*1000)!==dateOf(now))throw new Error('Invalid cached MET Norway forecast');
  return data;
}
export function summarizeMetno(data, now=Date.now(), warnings=[]) {
  const h=data.hourly, today=dateOf(now);
  const rows=h.time.map((time,i)=>({time,code:h.weather_code[i],mm:h.precipitation[i],wind:h.wind_speed_10m[i]})).filter(r=>r.time*1000>=now-HOUR);
  const hour=Number(new Intl.DateTimeFormat('en-GB',{timeZone:TZ,hour:'2-digit',hourCycle:'h23'}).format(new Date(now)));
  const night=hour>=18 || hour<5;
  const morningDay=hour<5?today:nextDay(today,1);
  const localHour=t=>Number(new Intl.DateTimeFormat('en-GB',{timeZone:TZ,hour:'2-digit',hourCycle:'h23'}).format(new Date(t*1000)));
  const relevant=rows.filter(r=>night?
    (dateOf(r.time*1000)===today && hour>=18 || dateOf(r.time*1000)===morningDay && localHour(r.time)<12):dateOf(r.time*1000)===today);
  const entries=[];
  const warning=warnings.find(w=>typeof w?.text==='string');
  if(warning)entries.push({kind:'warning',text:warning.text});
  const storm=relevant.find(r=>r.code===95), snow=relevant.find(r=>[69,73].includes(r.code));
  const rain=relevant.find(r=>r.mm>=.2 || [61,63,65].includes(r.code));
  const windy=relevant.find(r=>r.wind>=33);
  const event=storm || windy || snow || rain;
  if(event){
    const time=new Intl.DateTimeFormat('pl-PL',{timeZone:TZ,hour:'2-digit',minute:'2-digit'}).format(new Date(event.time*1000));
    const when=event.time*1000<=now+45*60000?'W najbliższych godzinach':`${dateOf(event.time*1000)!==today?'Jutro około':'Około'} ${time}`;
    entries.push({kind:storm?'storm':windy?'wind':snow?'snow':'rain',text:`${when} prognozowane ${storm?'burze':windy?'silniejszy wiatr':snow?'opady śniegu lub deszczu ze śniegiem':'opady deszczu'}.`});
  }else entries.push({kind:'stable',text:night?'W najbliższych godzinach model nie przewiduje istotnych opadów.':'Do końca dnia model nie przewiduje istotnych opadów.'});
  entries.push({kind:'stable',text:`Prognoza na ${new Intl.DateTimeFormat('pl-PL',{timeZone:TZ,hour:'2-digit',minute:'2-digit'}).format(new Date(data.current.time*1000))}: ${Math.round(data.current.temperature_2m)}°C, wiatr ok. ${Math.round(data.current.wind_speed_10m)} km/h.`});
  return {period:night?'NOC I PORANEK':'DZIŚ',entries:entries.slice(0,2)};
}
export function createMetnoLoader(fetcher=fetch,clock=Date.now) {
  let raw, expires=0, pending, lastModified;
  return async function load(){
    if(raw && clock()<expires)return normalizeMetno(raw,clock());
    if(pending)return pending;
    pending=(async()=>{
      const r=await fetcher(metnoUrl(),{headers:{'User-Agent':'IspinaLokalnie/1.1 https://ispinalokalnie.wordpress.com/kontakt/',...(lastModified?{'If-Modified-Since':lastModified}:{})},
        cache:'force-cache',next:{revalidate:3600},signal:AbortSignal.timeout(8000)});
      if(!r.ok && !(r.status===304 && raw))throw Object.assign(new Error(`MET Norway HTTP ${r.status}`),{code:`http-${r.status}`});
      const payload=r.status===304?raw:await r.json(), data=normalizeMetno(payload,clock());
      const until=Date.parse(r.headers?.get('expires'));
      raw=payload;
      lastModified=r.headers?.get('last-modified') || lastModified;
      expires=Number.isFinite(until)&&until>clock()?until:clock()+15*60000;
      return data;
    })();
    try{return await pending;}finally{pending=undefined;}
  };
}
