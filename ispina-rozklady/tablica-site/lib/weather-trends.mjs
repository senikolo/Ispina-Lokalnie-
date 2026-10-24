// Short, conservative forecast of *changes*, not an hourly weather report.
// Uses only verified hourly fields from the existing Open-Meteo response.
const TZ = 'Europe/Warsaw';
const localDay = (seconds) => new Intl.DateTimeFormat('en-CA',{timeZone:TZ,year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(seconds*1000));
const localHour = (seconds) => Number(new Intl.DateTimeFormat('en-GB',{timeZone:TZ,hour:'2-digit',hourCycle:'h23'}).format(new Date(seconds*1000)));
const rainy = new Set([51,53,55,56,57,61,63,65,66,67,80,81,82,95,96,99]);
const snowy = new Set([71,73,75,77,85,86]);
const thunder = new Set([95,96,99]);
const mean = arr => arr.reduce((sum,n)=>sum+n,0)/arr.length;
const partOfDay = hour => hour >= 18 ? 'Wieczorem' : hour >= 12 ? 'Po południu' : hour >= 6 ? 'Przed południem' : 'W nocy';
const dry = x => x.precipitation_probability < 35 && x.precipitation < 0.2 && !rainy.has(x.weather_code) && !snowy.has(x.weather_code);
const wet = x => x.precipitation_probability >= 65 || (x.precipitation_probability >= 45 && x.precipitation >= 0.1) ||
  ((rainy.has(x.weather_code) || snowy.has(x.weather_code)) && x.precipitation_probability >= 45);
function eventKind(rows) {
  return rows.some(x=>thunder.has(x.weather_code)) ? 'burze' :
    rows.some(x=>snowy.has(x.weather_code)) ? 'śnieg' : 'deszcz';
}
function eventText(kind) {
  return kind === 'burze' ? 'burze' : kind === 'śnieg' ? 'opady śniegu' : 'opady deszczu';
}
function buildRows(data, nowSeconds) {
  const h = data?.hourly;
  if(!h?.time) return [];
  const today = localDay(nowSeconds);
  return h.time.map((time,i) => ({
    time, hour:localHour(time), precipitation_probability:h.precipitation_probability[i],
    precipitation:h.precipitation[i], weather_code:h.weather_code[i],
    wind_speed_10m:h.wind_speed_10m[i], wind_gusts_10m:h.wind_gusts_10m[i],
    cloud_cover:h.cloud_cover[i]
  })).filter(x=>localDay(x.time)===today && x.time >= nowSeconds-3600);
}
export function summarizeWeatherChanges(data, nowMs=Date.now()) {
  const rows=buildRows(data,nowMs/1000);
  if (rows.length < 2) return ['Na resztę dnia prognoza godzinowa jest zbyt krótka, by ocenić zmiany.'];
  const messages=[];
  const wetFlags=rows.map(wet);
  const runs=[];
  for(let i=0;i<rows.length;){
    if(!wetFlags[i]){i++;continue;}
    const start=i;
    while(i<rows.length && wetFlags[i]) i++;
    const end=i;
    const span=end-start;
    const peak=Math.max(...rows.slice(start,end).map(x=>x.precipitation_probability));
    if(span>=2 || peak>=70)runs.push({start,end,kind:eventKind(rows.slice(start,end)),peak});
  }
  if(runs.length){
    const first=runs[0],startRow=rows[first.start];
    const phrase=eventText(first.kind);
    if(first.start===0 || startRow.time <= nowMs/1000+3600){
      messages.push(`W najbliższych godzinach możliwe ${phrase}.`);
    } else {
      messages.push(`Około ${String(startRow.hour).padStart(2,'0')}:00 możliwe ${phrase}.`);
    }
    const last=runs[runs.length-1],after=rows.slice(last.end);
    if(after.length>=2 && after.every(dry) && last.end>0){
      const hour=rows[last.end].hour;
      messages.push(`Po około ${String(hour).padStart(2,'0')}:00 opady powinny ustąpić.`);
    }
  } else {
    const maxProb=Math.max(...rows.map(r=>r.precipitation_probability));
    if(maxProb < 35 && rows.every(r=>r.precipitation < 0.2)) {
      messages.push('Do końca dnia bez większych opadów.');
    } else if (maxProb < 55) {
      messages.push('Na razie brak wyraźnego sygnału opadów.');
    }
  }
  const early=rows.slice(0,Math.min(3,rows.length));
  const later=rows.slice(Math.min(3,rows.length));
  if(later.length){
    const currentWind=mean(early.map(r=>r.wind_speed_10m));
    const currentGust=mean(early.map(r=>r.wind_gusts_10m));
    const laterPeak=later.reduce((m,r)=>r.wind_gusts_10m>m.wind_gusts_10m?r:m,later[0]);
    const lateStrong=laterPeak.wind_gusts_10m>=55 || laterPeak.wind_speed_10m>=32;
    const increase=laterPeak.wind_gusts_10m>=currentGust+18 || laterPeak.wind_speed_10m>=currentWind+12;
    if(lateStrong && increase){
      messages.push(`${partOfDay(laterPeak.hour)} wiatr się nasili, porywy do ok. ${Math.round(laterPeak.wind_gusts_10m/5)*5} km/h.`);
    } else if(Math.max(...rows.map(r=>r.wind_gusts_10m))>=70){
      messages.push('Możliwe bardzo silne porywy wiatru.');
    }
  }
  // Only mention a sustained, material cloud-cover change. Don't narrate noise.
  if(messages.length<2 && rows.length>=6){
    const left=mean(rows.slice(0,3).map(x=>x.cloud_cover));
    const right=mean(rows.slice(-3).map(x=>x.cloud_cover));
    if(right-left>=45) messages.push('W dalszej części dnia przybędzie chmur.');
    if(left-right>=45) messages.push('W dalszej części dnia możliwe przejaśnienia.');
  }
  if(!messages.length)messages.push('Pogoda powinna pozostać dość stabilna.');
  if(messages.length===1 && !runs.length && !messages[0].includes('opadów') && rows.every(dry))messages.push('Opady pozostaną mało prawdopodobne.');
  return messages.slice(0,3);
}


// A planning-focused brief. After 18:00 in Ispina, cover the coming
// night AND tomorrow morning; a dry evening alone is misleading.
export function summarizeWeatherBrief(data, nowMs=Date.now()) {
  const nowSeconds=nowMs/1000;
  const currentHour=localHour(nowSeconds);
  const today=localDay(nowSeconds);
  const nextDate=new Date(today+'T12:00:00Z');
  nextDate.setUTCDate(nextDate.getUTCDate()+1);
  const tomorrow=nextDate.toISOString().slice(0,10);
  const h=data?.hourly;
  const all=(h?.time||[]).map((time,i)=>({
    time, hour:localHour(time), date:localDay(time),
    precipitation_probability:h.precipitation_probability[i],
    precipitation:h.precipitation[i], weather_code:h.weather_code[i],
    wind_speed_10m:h.wind_speed_10m[i],
    wind_gusts_10m:h.wind_gusts_10m[i], cloud_cover:h.cloud_cover[i]
  }));
  const entry=(emoji,text,kind)=>({emoji,text,kind});
  const significant = rows => rows.filter(r=>wet(r) ||
    (thunder.has(r.weather_code) && r.precipitation_probability>=30));
  const pickWeather=(rows,label)=>{
    if(!rows.length)return null;
    const storms=rows.filter(r=>thunder.has(r.weather_code) && r.precipitation_probability>=30);
    if(storms.length)return entry('⛈️',label+' możliwe burze.','storm');
    const events=significant(rows);
    if(events.length>=2 || events.some(x=>x.precipitation_probability>=70)) {
      const snow=events.some(r=>snowy.has(r.weather_code));
      return entry(snow?'❄️':'🌧️',label+(snow?' możliwe opady śniegu.':' możliwy deszcz.'),snow?'snow':'rain');
    }
    const highWind=Math.max(...rows.map(r=>r.wind_gusts_10m));
    if(highWind>=55) return entry('💨',label+' silniejsze porywy wiatru, do ok. '+Math.round(highWind/5)*5+' km/h.','wind');
    return null;
  };
  if(currentHour>=18 || currentHour<5) {
    const night=all.filter(r=>r.time>=nowSeconds-1800 &&
      (currentHour<5 ? (r.date===today && r.hour<7) :
        ((r.date===today && r.hour>=18) || (r.date===tomorrow && r.hour<7))));
    const nextMorningDay=currentHour<5?today:tomorrow;
    const morning=all.filter(r=>r.date===nextMorningDay && r.hour>=7 && r.hour<12);
    const notes=[];
    const overnight=pickWeather(night,'W nocy');
    notes.push(overnight||entry('🌙','Spokojna noc, bez większych opadów.','calm'));
    const morningWeather=pickWeather(morning,currentHour<5?'Dziś rano':'Jutro rano');
    if(morningWeather)notes.push(morningWeather);
    else if(morning.length>=3 && morning.every(dry))
      notes.push(entry('🌤️',(currentHour<5?'Rano':'Jutro rano')+' bez większych opadów.','stable'));
    return {period:currentHour<5?'NOC I PORANEK':'NOC I JUTRO',entries:notes.slice(0,2)};
  }
  const daytime = summarizeWeatherChanges(data,nowMs).slice(0,2);
  const entries=daytime.map(text=>{
    const t=text.toLowerCase();
    if(t.includes('burz'))return entry('⛈️',text,'storm');
    if(t.includes('wiatr')||t.includes('porywy'))return entry('💨',text,'wind');
    if(t.includes('śnieg'))return entry('❄️',text,'snow');
    if((t.includes('deszcz')||t.includes('opad')) &&
      !/(bez większych|brak wyraźnego|mało prawdopodobne|ustąpi|pozostaną)/.test(t))
      return entry('🌧️',text,'rain');
    if(t.includes('przejaśnien'))return entry('🌤️',text,'stable');
    return entry('☀️',text,'stable');
  });
  return {period:'DZIŚ',entries};
}
