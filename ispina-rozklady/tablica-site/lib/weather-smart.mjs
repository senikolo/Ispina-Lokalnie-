// Concise, deterministic local forecast; no generative AI or hourly temperature list.
const TZ='Europe/Warsaw';
const parts=new Intl.DateTimeFormat('en-CA',{timeZone:TZ,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',hourCycle:'h23'});
const dateOf=t=>parts.format(new Date(t*1000)).slice(0,10);
const hourOf=t=>Number(new Intl.DateTimeFormat('en-GB',{timeZone:TZ,hour:'2-digit',hourCycle:'h23'}).format(new Date(t*1000)));
const rainy=new Set([51,53,55,56,57,61,63,65,66,67,80,81,82,95,96,99]);
const snowy=new Set([71,73,75,77,85,86]);
const thunder=new Set([95,96,99]);
const foggy=new Set([45,48]);
const dayPlus=(day,n)=>{
  const date=new Date(day+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+n);return date.toISOString().slice(0,10);
};
function rowsOf(data,nowMs){
  const h=data?.hourly;
  if(!h?.time)return [];
  return h.time.map((t,i)=>({
    time:t,day:dateOf(t),hour:hourOf(t),code:h.weather_code[i],
    prob:h.precipitation_probability[i],mm:h.precipitation[i],
    gust:h.wind_gusts_10m[i],wind:h.wind_speed_10m[i]
  })).filter(x=>x.time*1000>=nowMs-3600e3);
}
const eventTypes=[
 {kind:'storm',severity:100,matching:r=>thunder.has(r.code)&&r.prob>=30,minCount:1},
 {kind:'wind',severity:85,matching:r=>r.gust>=55||r.wind>=33,minCount:1},
 {kind:'fog',severity:75,matching:r=>foggy.has(r.code),minCount:2},
 {kind:'snow',severity:65,matching:r=>snowy.has(r.code)&&r.prob>=45,minCount:2},
 {kind:'rain',severity:55,matching:r=>(rainy.has(r.code)||r.mm>=0.2)&&r.prob>=45,minCount:2}
];
function extractEvents(rows){
  const events=[];
  for(const type of eventTypes){
    let run=[];
    const push=()=>{
      if(run.length>=type.minCount || (type.kind==='rain'&&run.some(x=>x.prob>=75)) ||
        (type.kind==='fog'&&run.some(x=>x.code===48))) {
        const first=run[0],last=run[run.length-1];
        events.push({...type,first,last,count:run.length,
          maxGust:Math.max(...run.map(x=>x.gust)),peakProb:Math.max(...run.map(x=>x.prob))});
      }
      run=[];
    };
    for(const r of rows){
      if(type.matching(r))run.push(r);
      else if(run.length)push();
    }
    if(run.length)push();
  }
  return events.sort((a,b)=>b.severity-a.severity||a.first.time-b.first.time);
}
function eventToMessage(event,scope,nowMs){
  const hour=String(event.first.hour).padStart(2,'0')+':00';
  let when;
  if(event.first.time*1000<=nowMs+45*60000)when='W najbliższych godzinach';
  else if(scope==='morning') when='Jutro około '+hour;
  else if(scope==='night') when=event.first.day===dateOf(nowMs/1000)?'Dziś około '+hour:'W nocy około '+hour;
  else when='Około '+hour;
  if(event.kind==='storm')return {kind:'storm',text:when+' możliwe burze.'};
  if(event.kind==='wind')return {kind:'wind',text:when+' silne porywy wiatru, do ok. '+Math.round(event.maxGust/5)*5+' km/h.'};
  if(event.kind==='fog')return {kind:'fog',text:when+' możliwa mgła i gorsza widoczność.'};
  if(event.kind==='snow')return {kind:'snow',text:when+' możliwe opady śniegu.'};
  return {kind:'rain',text:when+' możliwy deszcz.'};
}
// State clearly whether the forecast expects precipitation, not whether it is "large".
// Thresholds are conservative presentation bands, not guaranteed probabilities
// for the entire period: the input is an hourly forecast.
function periodOutlook(scope, rows, currentHour) {
  if (rows.length < 2) return null;
  const maxChance = Math.max(...rows.map(r => r.prob));
  const precipitationSignal = rows.some(r => r.mm >= 0.2 ||
    rainy.has(r.code) || snowy.has(r.code));
  const lowChance = maxChance < 20 && !precipitationSignal;
  const moderateChance = maxChance < 35 && !precipitationSignal;
  const period = scope==='night'?'W nocy':
    scope==='morning'?(currentHour<5?'Dziś rano':'Jutro rano'):'Do końca dnia';
  if (lowChance) {
    const text = scope==='night'?'Spokojna noc. Prognoza nie wskazuje opadów.':
      scope==='morning'?period+' prognoza nie wskazuje opadów.':
      'Do końca dnia prognoza nie wskazuje opadów.';
    return {kind:scope==='night'?'calm':'stable',text};
  }
  if (moderateChance) {
    return {kind:'stable',text:scope==='day'
      ? 'Do końca dnia opady są mało prawdopodobne.'
      : period+' opady są mało prawdopodobne.'};
  }
  return {kind:'rain',text:scope==='day'
    ? 'Do końca dnia możliwe opady. Dokładna pora jest niepewna.'
    : period+' możliwe opady. Dokładna pora jest niepewna.'};
}
export function summarizeWeatherSmart(data,nowMs=Date.now(),officialWarnings=[]){
  const now=nowMs/1000, hour=hourOf(now), day=dateOf(now);
  const tomorrow=dayPlus(day,1);
  const all=rowsOf(data,nowMs);
  const evening=hour>=18||hour<5;
  const night=all.filter(r=>hour>=18?
    ((r.day===day&&r.hour>=18)||(r.day===tomorrow&&r.hour<7)):
    (r.day===day&&r.hour<7));
  const morningDay=hour<5?day:tomorrow;
  const morning=all.filter(r=>r.day===morningDay&&r.hour>=7&&r.hour<12);
  const daytime=all.filter(r=>r.day===day&&r.hour>=hour);
  const messages=[];
  // Official warning for *this* county takes absolute display precedence.
  if(Array.isArray(officialWarnings)&&officialWarnings.length){
    const warning=officialWarnings[0];
    messages.push({kind:'warning',text:warning.text,source:'imgw'});
  }
  if(evening){
    const nightEvents=extractEvents(night);
    const morningEvents=extractEvents(morning);
    const primary=nightEvents[0];
    const secondary=morningEvents[0];
    // Do not claim a calm night if only one observation remains.
    if(primary) messages.push(eventToMessage(primary,'night',nowMs));
    else if(night.length>=2)messages.push(periodOutlook('night',night,hour));
    if(secondary)messages.push(eventToMessage(secondary,'morning',nowMs));
    else if(morning.length>=3&&messages.length<2)messages.push(periodOutlook('morning',morning,hour));
  }else{
    const events=extractEvents(daytime);
    if(events.length){
      messages.push(eventToMessage(events[0],'day',nowMs));
      const another=events.find(x=>x.kind!==events[0].kind &&
         Math.abs(x.first.time-events[0].first.time)>=3600);
      if(another)messages.push(eventToMessage(another,'day',nowMs));
      else if(events[0].kind==='rain'){
        const after=daytime.filter(r=>r.time>events[0].last.time);
        if(after.length>=2&&after.every(r=>r.mm<0.2&&r.prob<35))
           messages.push({kind:'stable',text:'Po około '+String(after[0].hour).padStart(2,'0')+':00 opady powinny ustąpić.'});
      }
    }else if(daytime.length>=2)messages.push(periodOutlook('day',daytime,hour));
  }
  if(!messages.length)messages.push({kind:'stable',text:'Brak pełnych danych o zmianach pogody.'});
  return {period:evening?(hour<5?'NOC I PORANEK':'NOC I JUTRO'):'DZIŚ',
    entries:messages.slice(0,2)};
}
