'use client';
import {useEffect,useRef,useState} from 'react';
type Departure={line:string;time:string;scheduled:number;direction:string;destination:string;stop_name:string;stop:string;source:string;pdf:string;trip_id?:string;kind:string;expected:number|null;live_until:number|null;propagated?:boolean};
type Day={date:string;departures:Departure[];warnings:string[]};
type Board={server_now:number;schedule_valid_until:number;generated_at:string;days:Day[];error?:string;live_available:boolean;gminna_notice:string};
const wp='https://ispinalokalnie.wordpress.com/odjazdy-autobusow/';
const clock=(n:number)=>new Intl.DateTimeFormat('pl-PL',{timeZone:'Europe/Warsaw',hour:'2-digit',minute:'2-digit'}).format(new Date(n*1000));
const dayLabel=(s:string)=>new Intl.DateTimeFormat('pl-PL',{timeZone:'Europe/Warsaw',weekday:'long',day:'numeric',month:'long'}).format(new Date(s+'T12:00:00+01:00'));
const civil=(n:number)=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Europe/Warsaw'}).format(new Date(n*1000));
export default function Page(){
 const [data,setData]=useState<Board|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(true),[localNow,setLocalNow]=useState(0),[offset,setOffset]=useState(0),[scheduleOnly,setScheduleOnly]=useState(false);
 const [selected,setSelected]=useState(''),[line,setLine]=useState('ALL'),[allDay,setAllDay]=useState(false),[direction,setDirection]=useState('ALL');
 const active=useRef(false);
 async function refresh(){
  if(active.current)return;active.current=true;setBusy(true);
  try{const res=await fetch('/api/odjazdy',{cache:'no-store',signal:AbortSignal.timeout(28000)});const value=await res.json() as Board;
   if(!res.ok||value.error){setData(null);throw new Error(value.error||'Nie udało się pobrać rozkładu.');}
   setData(value);setOffset(value.server_now-Date.now()/1000);setScheduleOnly(false);setError('');
  }catch(e){
   // Independent read-only schedule fallback; no cached live predictions survive.
   try{const r=await fetch('https://raw.githubusercontent.com/senikolo/Ispina-Lokalnie-/main/ispina-rozklady/out/tablica.json',{cache:'no-store',signal:AbortSignal.timeout(10000)});
    if(!r.ok)throw new Error('Brak rozkładu');const s=await r.json() as Board & {schema:number};const timestamp=Date.parse(s.generated_at)/1000;
    const current=Date.now()/1000;
    if(s.schema!==1||s.error||!Array.isArray(s.days)||!Number.isFinite(timestamp)||current-timestamp>30*3600||timestamp>current+300)throw new Error('Rozkład wymaga aktualizacji.');
    setData({...s,server_now:current,schedule_valid_until:timestamp+30*3600,live_available:false,days:s.days.map(d=>({...d,departures:d.departures.map(x=>({...x,kind:'scheduled',expected:null,live_until:null}))}))});
    setOffset(0);setScheduleOnly(true);setError('');
   }catch{setError(e instanceof Error?e.message:'Brak połączenia. Spróbuj odświeżyć.');}
  }finally{setBusy(false);active.current=false;}
 }
 useEffect(()=>{refresh();const interval=setInterval(()=>{if(!document.hidden)refresh();},30000);const wake=()=>{if(!document.hidden)refresh();};document.addEventListener('visibilitychange',wake);return()=>{clearInterval(interval);document.removeEventListener('visibilitychange',wake);};},[]);
 useEffect(()=>{const id=setInterval(()=>setLocalNow(Date.now()/1000),1000);return()=>clearInterval(id);},[]);
 const now=(localNow||Date.now()/1000)+offset;
 const stale=!!data&&now>data.schedule_valid_until;
 const today=civil(now);
 const selectedDay=selected||today;
 const day=data?.days.find(d=>d.date===selectedDay);
 const isToday=selectedDay===today;
 const rows=stale?[]:(day?.departures||[]).map(r=>{
  if(r.live_until&&r.live_until<now)return {...r,kind:'scheduled',expected:null};return r;
 }).filter(r=>line==='ALL'||r.line===line).filter(r=>direction==='ALL'||r.direction===direction);
 const showAll=allDay||!isToday;
 const effective=(r:Departure)=>r.kind==='live'&&r.expected!==null?r.expected:r.scheduled;
 const upcoming=rows.filter(r=>showAll||effective(r)>=now-30).sort((a,b)=>effective(a)-effective(b));
 const boardRows=(dir:string)=>upcoming.filter(r=>r.direction===dir).slice(0,showAll?200:6);
 const remaining=(r:Departure)=>{if(!isToday)return '';const minutes=Math.ceil((effective(r)-now)/60);return minutes<0?'wcześniej':minutes===0?'teraz':minutes>=60?`za ${Math.floor(minutes/60)} godz. ${minutes%60} min`:`za ${minutes} min`;};
 return <><a className="skip" href="#odjazdy">Przejdź do odjazdów</a><header className="site-header"><a className="brand" href={wp}><span className="brand-mark" aria-hidden="true">il.</span><span>Ispina <strong>Lokalnie</strong></span></a><a className="back" href={wp}>Wróć do strony <span aria-hidden="true">↗</span></a></header>
 <main><div className="intro"><p className="eyebrow">KOMUNIKACJA W TWOJEJ OKOLICY</p><h1>Ispina Most<span className="heading-dot" aria-hidden="true">.</span></h1><p className="lead">Najbliższe odjazdy w obu kierunkach.</p><p className="sublead">A4, A7, A65 oraz linia gminna — orientacyjnie według Domu Ludowego.</p></div>
 <div className="statusbar"><span className="status-text"><span className={'dot '+(error||stale?'muted':'')} aria-hidden="true"/>{busy?'Odświeżanie danych…':error?'Połączenie wymaga sprawdzenia':`Stan na ${data?clock(data.server_now):'—'}`}</span><span className="refresh-label">Automatycznie co 30 sekund</span><button className="refresh" onClick={refresh} disabled={busy} aria-label="Odśwież odjazdy">↻ <span>Odśwież</span></button></div>
 <section className="controls" aria-label="Wybierz rozkład"><div className="control-top"><label>Dzień podróży<select value={selectedDay} onChange={e=>{setSelected(e.target.value);setAllDay(e.target.value!==today);}}>{(data?.days||[{date:today}]).map(d=><option key={d.date} value={d.date}>{d.date===today?'Dzisiaj · ':''}{dayLabel(d.date)}</option>)}</select></label><div className="segmented" aria-label="Zakres godzin"><button aria-pressed={!showAll} disabled={!isToday} onClick={()=>setAllDay(false)}>Najbliższe</button><button aria-pressed={showAll} onClick={()=>setAllDay(true)}>Cały dzień</button></div></div><div className="filter-bottom"><div className="line-filters" aria-label="Filtr linii">{[['ALL','Wszystkie'],['A4','A4'],['A7','A7'],['A65','A65'],['GMINNA','Gminna']].map(([id,label])=><button key={id} className={line===id?'selected':''} aria-pressed={line===id} onClick={()=>setLine(id)}>{label}</button>)}</div><label className="direction-filter"><span className="sr-only">Kierunek</span><select value={direction} onChange={e=>setDirection(e.target.value)}><option value="ALL">Oba kierunki</option><option value="north">Nowe Brzesko / Kraków</option><option value="south">Bochnia / Podłęże / Mikluszowice</option></select></label></div></section>
 <div className="legend"><span className="live-label">● Na żywo</span><span>prognoza przewoźnika</span><span className="schedule-label">Rozkład</span><span>gdy nie ma aktualnej prognozy</span></div>
 <aside className="municipal-note"><span className="note-icon" aria-hidden="true">i</span><p><strong>Linia gminna: godziny „około”.</strong> Punktem odniesienia jest Ispina Dom Ludowy (Ispina III). Dla Mostu traktuj je orientacyjnie, z przyjętą tolerancją około 2 minut. Oficjalny PDF nie podaje osobnego odjazdu z Mostu.</p></aside>
 {scheduleOnly&&<p className="fallback-note" role="status">Prognozy na żywo są chwilowo niedostępne. Pokazuję aktualny, sprawdzony rozkład.</p>}
 {(error||stale)&&<div className="error" role="status"><strong>{stale?'Rozkład wymaga aktualizacji. Godziny zostały ukryte.':error}</strong>{data&&!stale&&<p>Pozostałe godziny są rozkładowe. Prognozy tracą oznaczenie „na żywo” po 3 minutach.</p>}<p><a href={wp}>Sprawdź KiedyPrzyjedzie i rozkłady źródłowe</a></p></div>}
 {day?.warnings.map(w=><p className="error" key={w}>{w}</p>)}
 <div id="odjazdy" className="board-grid" tabIndex={-1}>{[['north','W stronę Nowego Brzeska','Proszowice · Kraków Czyżyny','01'],['south','W stronę Bochni / Podłęża','Mikluszowice (linia gminna)','02']].filter(([id])=>direction==='ALL'||direction===id).map(([dir,title,subtitle,no])=><section className="direction-card" key={dir} aria-labelledby={'dir-'+dir}><div className="card-heading"><span className="platform" aria-hidden="true">{no}</span><div><h2 id={'dir-'+dir}>{title}</h2><p>{subtitle}</p></div><span className="arrow" aria-hidden="true">{dir==='north'?'↗':'↙'}</span></div><div className="columns-label" aria-hidden="true"><span>GODZINA</span><span>LINIA / KIERUNEK</span></div>
 <ol className="departures">{boardRows(dir).map(r=>{const municipal=r.line==='GMINNA';const live=r.kind==='live';const cancelled=['cancelled','skipped'].includes(r.kind);return <li className={'departure '+(cancelled?'cancelled':'')} key={r.line+'-'+r.scheduled+'-'+r.trip_id}><div className="time-cell"><time dateTime={new Date(effective(r)*1000).toISOString()}>{municipal&&<small>ok.</small>}{clock(effective(r))}</time>{live&&<span className="original-time">plan {r.time}</span>}<span className="countdown">{cancelled?'':(municipal&&remaining(r)&&remaining(r)!=='wcześniej'?'ok. ':'')+remaining(r)}</span></div><div className="trip-cell"><div className="trip-title"><span className={'line-badge '+(municipal?'municipal':'')}>{municipal?'Gminna':r.line}</span><strong>{r.destination.replace(/ \(NŻ\)/g,'')}</strong></div><p className="trip-stop">{municipal?'Wg Domu Ludowego · orientacyjnie':'Ispina Most · na żądanie'}</p><span className={live?'live-label':cancelled?'cancel-label':'schedule-label'}>{live?'● Na żywo':r.kind==='cancelled'?'Kurs odwołany':r.kind==='skipped'?'Pomija przystanek':municipal?'Rozkład · około':'Rozkład'}</span>{live&&r.propagated&&<span className="prediction-note"> według ostatniego pomiaru</span>}</div></li>;})}</ol>
 {!boardRows(dir).length&&<div className="empty">{busy&&!data?'Pobieram sprawdzone odjazdy…':stale||(!data&&error)?'Brak potwierdzonych godzin.':!day?'Brak potwierdzonego rozkładu na ten dzień.':showAll?'Brak kursów wybranych linii w tym dniu.':'Brak dalszych odjazdów dzisiaj. Wybierz kolejny dzień.'}</div>}
 {!showAll&&upcoming.filter(r=>r.direction===dir).length>6&&<button className="show-all" onClick={()=>setAllDay(true)}>Zobacz cały dzień <span aria-hidden="true">→</span></button>}
 <a className="kiedy" href={'https://kolejemalopolskie.kiedyprzyjedzie.pl/#/stops/690378:'+(dir==='north'?'741882':'741881')+'/departures'} target="_blank" rel="noopener noreferrer">Otwórz KiedyPrzyjedzie <span aria-hidden="true">↗</span></a></section>)}</div>
 <section className="explanation"><h2>Sprawdzone godziny, jasno opisane źródła.</h2><div className="explanation-grid"><div><h3>Co oznacza „na żywo”?</h3><p>Prognoza z oficjalnego GTFS Realtime KMŁ. Gdy przewoźnik udostępnia tylko opóźnienie z wcześniejszego przystanku, godzina jest prognozą według ostatniego pomiaru. Po 3 minutach bez świeżych danych wraca oznaczenie „Rozkład”.</p></div><div><h3>Ważne przed podróżą</h3><p>Linia gminna ma wyłącznie orientacyjne godziny rozkładowe. A7 korzysta z oficjalnego PDF ze względu na różnicę minuty w GTFS. Sprawdź komunikaty o zmianach tras i przyjdź na przystanek wcześniej.</p></div></div><details><summary>Źródła i kontrola aktualności</summary><p>Kontrola rozkładu: {data?new Date(data.generated_at).toLocaleString('pl-PL',{timeZone:'Europe/Warsaw'}):'oczekiwanie na dane'}. Rozkład jest sprawdzany automatycznie 6 razy na dobę. Dane starsze niż 30 godzin są ukrywane. Audyt obecnych wersji obejmuje okres do 30 listopada 2026; zmiana PDF wymaga ponownej kontroli.</p><ul><li><a href="https://kolejemalopolskie.com.pl/pl/rozklad-jazdy/rozklady-autobusowe" target="_blank" rel="noopener noreferrer">Oficjalne rozkłady MLD i komunikaty KMŁ</a></li><li><a href="https://www.drwinia.pl/mieszkaniec/rozklady-jazdy-busow-i-autobusow" target="_blank" rel="noopener noreferrer">Oficjalny rozkład linii gminnej — Gmina Drwinia</a></li><li><a href="https://gtfs.kolejemalopolskie.com.pl/" target="_blank" rel="noopener noreferrer">Dane GTFS i GTFS Realtime KMŁ</a></li></ul></details></section>
 </main><footer><a className="brand" href={wp}>Ispina <strong>Lokalnie</strong></a><p>Blisko domu. Bliżej informacji.</p><a href={wp}>Wszystkie informacje o komunikacji ↗</a></footer></>;
}
