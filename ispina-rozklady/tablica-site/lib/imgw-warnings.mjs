// Official IMGW-PIB public meteorological warnings. Never infer coverage from
// neighboring counties: Ispina (Drwinia) belongs to powiat bocheński, TERYT 1201.
export const IMGW_URL='https://danepubliczne.imgw.pl/api/data/warningsmeteo';
export const BOCHNIA_TERYT='1201';
export const IMGW_SOURCE='Źródłem pochodzenia danych jest Instytut Meteorologii i Gospodarki Wodnej – Państwowy Instytut Badawczy';
const fmt=new Intl.DateTimeFormat('pl-PL',{timeZone:'Europe/Warsaw',day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'});
// IMGW timestamps have Warsaw wall-clock time without an offset.
export function parseWarsawTime(raw) {
  if(typeof raw!=='string'|| !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(raw))return NaN;
  const provisional=Date.parse(raw.replace(' ','T')+'Z');
  if(!Number.isFinite(provisional))return NaN;
  // Check both possible offsets on DST transition days; for ambiguous 02:XX
  // use the earliest occurrence, avoiding a false negative for an active warning.
  const result=[];
  for(const offset of [1,2]) {
    const t=provisional-offset*3600e3;
    const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Warsaw',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(t);
    const obj=Object.fromEntries(parts.map(x=>[x.type,x.value]));
    if(`${obj.year}-${obj.month}-${obj.day} ${obj.hour}:${obj.minute}:${obj.second}`===raw)result.push(t);
  }
  return result.length?Math.min(...result):NaN;
}
export function localImgwWarnings(data, nowMs=Date.now()) {
  if(!Array.isArray(data)||data.length>4000) throw new Error('Nieprawidłowa lista IMGW');
  const result=[];
  for(const warning of data) {
    if(!warning || typeof warning!=='object' || !Array.isArray(warning.teryt) ||
      !warning.teryt.map(String).includes(BOCHNIA_TERYT))continue;
    const from=parseWarsawTime(warning.obowiazuje_od);
    const to=parseWarsawTime(warning.obowiazuje_do);
    const published=parseWarsawTime(warning.opublikowano);
    if(!Number.isFinite(from)||!Number.isFinite(to)||!Number.isFinite(published) ||
      to<=from || from>nowMs+36*3600e3 || to<nowMs ||
      published>nowMs+300000 || published<nowMs-7*86400000)continue;
    const level=Number(warning.stopien);
    const event=typeof warning.nazwa_zdarzenia==='string'?warning.nazwa_zdarzenia.trim().slice(0,65):'';
    if(!Number.isInteger(level)||level<1||level>3||!event)continue;
    const kind=/burz/i.test(event)?'storm':/wiatr/i.test(event)?'wind':/mgł/i.test(event)?'fog':/śnieg|zamieć/i.test(event)?'snow':/deszcz|opad/i.test(event)?'rain':'warning';
    const dateText=from<=nowMs?'teraz':from-nowMs<=24*3600e3?
      `od ${fmt.format(from)}`:`od ${fmt.format(from)}`;
    result.push({id:String(warning.id||''),kind,level,from,to,
      text:`IMGW: ${event}, stopień ${level}. ${dateText}.`});
  }
  return result.sort((a,b)=>b.level-a.level || a.from-b.from).slice(0,2);
}
export function createImgwLoader(fetcher=fetch, clock=Date.now) {
  let cached, expires=0, pending;
  return async function load() {
    const now=clock();
    if(now<expires)return cached;
    if(pending)return pending;
    pending=(async()=>{
      try{
        const response=await fetcher(IMGW_URL,{cache:'no-store',signal:AbortSignal.timeout(5500)});
        if(!response.ok)throw Error('IMGW unavailable');
        const data=await response.json();
        cached={warnings:localImgwWarnings(data,clock()),checkedAt:clock()};
        expires=clock()+12*60e3;
        return cached;
      }catch{
        // Fail closed. Never claim there are no warnings on an API outage.
        cached=null;
        expires=clock()+2*60e3;
        return null;
      }finally{pending=null;}
    })();
    return pending;
  };
}
