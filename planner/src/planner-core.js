(function(){
 'use strict';
 var searchGeneration=0,refreshTimer=0,visibleDirect=12,visibleTransfers=18,lastQuery='',lastTransferKeys=[],hasSearched=false;
 function platform(name){var v=ilCorridorKey(name),aliases={'ispina':'ispina most','ispina ii':'ispina most','ispina iii':'ispina dom ludowy','ispina iv':'ispina wislisko','nowe brzesko':'nowe brzesko rynek','wawrzenczyce':'wawrzenczyce cmentarz','wawrzenczyce ii':'wawrzenczyce centrum','wawrzenczyce iii':'wawrzenczyce wygon','wawrzenczyce iv':'wawrzenczyce zabudowanie','wawrzenczyce v':'wawrzenczyce granica'};return aliases[v]||v}
 // MLD names identify shared platforms; municipal labels remain searchable aliases.
 var cityPlatforms={'czyzyny dworzec':1,'rondo czyzynskie':1,'bienczycka':1,'rondo kocmyrzowskie im ks gorzelanego':1,'os zgody':1,'plac centralny im r reagana':1,'os na skarpie':1,'os wandy':1,'klasztorna':1,'fort mogila':1,'giedroycia':1,'brama nr 5':1,'meksyk':1,'koksochemia':1,'cmentarz pleszow':1,'pleszow':1,'brama nr 8 01':1,'brama nr 8 02':1,'szymanskiego':1,'wyciaska':1,'wyciaze polnoc':1,'clo 01':1,'clo 03':1};
 function corridorReference(direction){
  var source=RS()||{},stops=source.stops||{},best=[];
  (source.trips||[]).forEach(function(tr){if(tr.route!=='A50'&&tr.route!=='A57')return;var pts=tr.stopTimes||[],start=-1,end=-1;
   for(var i=0;i<pts.length;i++){var key=platform((stops[pts[i].stopId]||{}).name);if(key===(direction==='KRAKOW'?'nowe brzesko rynek':'czyzyny dworzec'))start=i;else if(start>=0&&key===(direction==='KRAKOW'?'czyzyny dworzec':'nowe brzesko rynek')){end=i;break}}
   if(start<0||end<=start||end-start+1<=best.length)return;
   best=pts.slice(start,end+1).map(function(p){var value=String(p.departure||p.arrival||'0:00:00').split(':');return {id:p.stopId,name:(stops[p.stopId]||{}).name,key:platform((stops[p.stopId]||{}).name),seconds:Number(value[0])*3600+Number(value[1])*60+Number(value[2]||0)}});
  });return best;
 }
 function normalizeMunicipal(data){
  if(!data||!data.stops||!data.serviceGroups)return data;
  var result=JSON.parse(JSON.stringify(data)),mld=(RS()||{}).stops||{},names={},byKey={},ids={};
  Object.keys(mld).forEach(function(id){var name=mld[id].name,key=platform(name);if(!names[key])names[key]=name});
  result.stops.forEach(function(stop){var original=stop.name,key=platform(original);stop.aliases=(stop.aliases||[]).slice();if(stop.aliases.indexOf(original)<0)stop.aliases.push(original);if(names[key]&&key!=='ispina most')stop.name=names[key];byKey[key]=stop;ids[stop.id]=stop});
  function sharedStop(point){var stop=byKey[point.key];if(!stop){stop={id:'mld_corridor_'+point.id,name:point.name,aliases:[],timingSource:'interpolated-MLD-corridor'};result.stops.push(stop);byKey[point.key]=stop;ids[stop.id]=stop}return stop.id}
  function mostToMarket(){var seconds=0;(RS().trips||[]).some(function(tr){if(tr.route!=='A7')return false;var pts=tr.stopTimes||[],a=-1;for(var i=0;i<pts.length;i++){var key=platform((mld[pts[i].stopId]||{}).name);if(key==='ispina most')a=i;if(a>=0&&key==='nowe brzesko rynek'){var first=String(pts[a].departure).split(':'),last=String(pts[i].arrival||pts[i].departure).split(':'),gap=(+last[0]-+first[0])*3600+(+last[1]-+first[1])*60+(+last[2]-+first[2]);if(gap>0){seconds=gap;return true}}}return false});return seconds}
  result.serviceGroups.forEach(function(group){
   var reference=corridorReference(group.direction),positions={};reference.forEach(function(p,i){positions[p.key]=i});
   var route=(group.route||[]).slice(),offsets=(group.offsets||[]).slice();
   // The old Kraków-bound model omitted the market between Most and Nękanowice.
   if(group.direction==='KRAKOW'&&reference.length&&route.indexOf(sharedStop(reference[0]))<0){
    var firstCorridor=reference[1],localSeconds=mostToMarket();
    for(var i=0;i<route.length-1;i++)if(platform(ids[route[i]].name)==='ispina most'&&firstCorridor&&platform(ids[route[i+1]].name)===firstCorridor.key&&localSeconds){var corridorSeconds=firstCorridor.seconds-reference[0].seconds;var estimate=Number(offsets[i])+Math.round((Number(offsets[i+1])-Number(offsets[i]))*localSeconds/(localSeconds+corridorSeconds));route.splice(i+1,0,sharedStop(reference[0]));offsets.splice(i+1,0,estimate);break}
   }
   var expanded=[],times=[];
   for(var i=0;i<route.length;i++){
    expanded.push(route[i]);times.push(Number(offsets[i]));if(i===route.length-1)continue;
    var a=positions[platform(ids[route[i]].name)],b=positions[platform(ids[route[i+1]].name)];if(a===undefined||b===undefined||b<=a+1)continue;
    var span=reference[b].seconds-reference[a].seconds;
    for(var k=a+1;k<b;k++){var proportion=span>0?(reference[k].seconds-reference[a].seconds)/span:(k-a)/(b-a);expanded.push(sharedStop(reference[k]));times.push(Number(offsets[i])+Math.round((Number(offsets[i+1])-Number(offsets[i]))*proportion))}
   }
   group.route=expanded;group.offsets=times;
   group.timingQuality=group.timingQuality||{};group.timingQuality.interpolatedCorridorStops=true;
  });
  result.routeModel=result.routeModel||{};result.routeModel.sharedKrakowNoweBrzesko='A50/A57';result.routeModel.canonicalNames='MLD';result.routeModel.normalizationVersion='R14.2';return result;
 }
 var baseApplyMunicipal=applyRG;
 applyRG=function(data){baseApplyMunicipal(normalizeMunicipal(data))};
 if(RG)applyRG(RG);
 function groupValue(text){return String(text||'').replace(/\s*[–-]\s*wszystkie przystanki\s*$/i,'').trim()}
 function isGroup(text){return /[–-]\s*wszystkie przystanki\s*$/i.test(text)}
 function groupMatches(name,city){var a=platform(name),b=rn(city);return (b==='krakow'&&!!cityPlatforms[a])||a===b||a.indexOf(b+' ')===0}
 function catalog(){
  var entries={},stops=(RS()||{}).stops||{};
  function add(name,id,src,aliases){if(!name)return;var key=platform(name);if(key==='ispina most')name='Ispina Most';if(!entries[key])entries[key]={n:name,key:'stop:'+key,ids:[],aliases:[],src:src||'Przystanek'};var entry=entries[key];if(entry.ids.indexOf(id)<0)entry.ids.push(id);[name].concat(aliases||[]).forEach(function(a){if(entry.aliases.indexOf(a)<0)entry.aliases.push(a)});if(cityPlatforms[key]&&entry.aliases.indexOf('Kraków, '+name)<0)entry.aliases.push('Kraków, '+name)}
  Object.keys(stops).forEach(function(id){add(stops[id].name,'M:'+id,'Rozkład MLD')});
  (RG&&RG.stops||[]).forEach(function(s){add(s.name,'G:'+s.id,'Linia gminna',s.aliases)});
  var cities=['Ispina','Mikluszowice','Grobla','Dziewin','Drwinia','Zielona','Świniary','Gawłówek','Kraków','Nowe Brzesko','Bochnia','Proszowice','Niepołomice','Podłęże','Koszyce','Igołomia','Wawrzeńczyce','Szpitary','Hebdów'];
  var rows=Object.keys(entries).map(function(k){var row=entries[k];row.ids.sort();row.key='stop:'+row.ids.join('|');return row});
  cities.forEach(function(city){if(rows.some(function(s){return groupMatches(s.n,city)}))rows.push({n:city+' – wszystkie przystanki',key:'group:'+rn(city),ids:[],src:'Miejscowość: wszystkie przystanki'})});return rows;
 }
 function resolve(text){
  var value=String(text||'').trim(),rows=catalog(),target=platform(value),group=isGroup(value);
  if(group){target=rn(groupValue(value));return rows.filter(function(s){return s.key==='group:'+target})[0]||null}
  var exact=rows.filter(function(s){return s.key.indexOf('stop:')===0&&platform(s.n)===target})[0];if(exact)return exact;
  // Complete town names are explicit whole-town choices, never prefix matches.
  return rows.filter(function(s){return s.key==='group:'+rn(value)})[0]||null;
 }
 function resolveInput(input){var key=input.getAttribute('data-il-stop-key'),label=input.getAttribute('data-il-stop-label');if(key&&label===input.value.trim()){var current=catalog(),ids=[];try{ids=JSON.parse(input.getAttribute('data-il-stop-ids')||'[]')}catch(e){}var retained=current.filter(function(s){return key.indexOf('group:')===0?s.key===key:s.ids.some(function(id){return ids.indexOf(id)>=0})})[0];if(retained)return retained}return resolve(input.value)}
 window.IL_STOP_CATALOG=catalog;window.IL_STOP_RESOLVE=resolve;
 function matches(point,selection,run){var name=typeof point==='string'?point:point.name;if(selection.key.indexOf('group:')===0)return groupMatches(name,groupValue(selection.n));if(run&&point.id!==undefined)return selection.ids.indexOf((run.tripId?'M:':'G:')+point.id)>=0;return platform(name)===platform(selection.n)}
 function routeLeg(run,start,end){var a=run.points[start],b=run.points[end];return {runId:run.id,tripId:run.tripId,serviceDate:run.date,stopId:a.id,seq:a.seq,w:new Date(a.dep),a:new Date(b.arr),l:run.line,d:run.head,s:a.name,e:b.name,estimated:!!(a.estimated||b.estimated),departureEstimated:!!a.estimated,k:run.id+'|'+start+'|'+end,end:{tripId:run.tripId,serviceDate:run.date,stopId:b.id,seq:b.seq,w:new Date(b.arr),arrivalEvent:true,estimated:!!b.estimated}}}
 // All temporal qualification uses this function, including LIVE refreshes.
 function prediction(event){var live=ilLive(event),ms=+event.w,estimated=event.departureEstimated!==undefined?event.departureEstimated:event.estimated;return {ms:ms+(live&&live.delay!==undefined?live.delay*1000:0),live:live,blocked:!!(live&&(live.cancelled||live.skipped)),source:live&&live.delay!==undefined?(live.estimated?'szacunek LIVE':'LIVE'):estimated?'szacunek':'rozkład'}}
 function legTimes(leg){return {departure:prediction(leg),arrival:prediction(leg.end)}}
 function evaluate(first,second,minWait){
  var a=legTimes(first),b=second&&legTimes(second),blocked=a.departure.blocked||a.arrival.blocked||(b&&(b.departure.blocked||b.arrival.blocked));
  var gap=b?(b.departure.ms-a.arrival.ms)/60000:0;
  return {first:a,second:b,blocked:!!blocked,gap:gap,viable:!blocked&&(!b||gap>=minWait),w:new Date(a.departure.ms),a:new Date(b?b.arrival.ms:a.arrival.ms)};
 }
 window.IL_PREDICTION=prediction;
 function runsAsync(date,valid,done){
  var src=RS()||{},cache=IL_TRANSFER_RUN_CACHE,key=rdate(date);
  if(cache&&cache.source===src&&cache.g===RG&&cache.day===key){done(cache.runs);return}
  var trips=src.trips||[],stops=src.stops||{},groups=RG&&RG.serviceGroups||[],names={};
  (RG&&RG.stops||[]).forEach(function(s){names[s.id]=s.name});
  var runs=[],dayIndex=-2,part=0,groupIndex=0,departureIndex=0,day=null,calendar={},times={},dayType='';
  function nextDay(){dayIndex++;if(dayIndex>0){IL_TRANSFER_RUN_CACHE={source:src,g:RG,day:key,runs:runs};done(runs);return false}day=new Date(date);day.setHours(0,0,0,0);day.setDate(day.getDate()+dayIndex);part=groupIndex=departureIndex=0;calendar={};times={};dayType=dy(day)===6?'SATURDAY':dy(day)?'WEEKDAY':'';return true}
  function stamp(t){return times[t]===undefined?(times[t]=+rtime(day,t)):times[t]}
  function tick(){if(!valid())return;if(!day&&!nextDay())return;var count=0;
   while(part<trips.length&&count++<22){var tr=trips[part++];if(calendar[tr.serviceId]===undefined)calendar[tr.serviceId]=ractive(tr.serviceId,day);if(!rmline(tr.route)||!calendar[tr.serviceId])continue;
    var points=(tr.stopTimes||[]).map(function(s){var name=(stops[s.stopId]||{}).name||'';return {name:name,key:platform(name),id:s.stopId,seq:s.seq,arr:stamp(s.arrival||s.departure),dep:stamp(s.departure||s.arrival),estimated:false}});
    runs.push({id:'M|'+rdate(day)+'|'+tr.tripId,tripId:tr.tripId,date:rdate(day),line:tr.route,head:tr.headsign||'',points:points});
   }
   if(part<trips.length){setTimeout(tick,0);return}count=0;
   while(groupIndex<groups.length&&count++<8){var g=groups[groupIndex];if(!dayType||g.dayType!==dayType){groupIndex++;departureIndex=0;continue}var anchors={};((g.timingQuality||{}).officialAnchors||[]).forEach(function(id){anchors[id]=true});
    while(departureIndex<(g.departures||[]).length&&count++<8){var index=departureIndex++,hour=g.departures[index];var pts=(g.route||[]).map(function(id,pos){var name=names[id]||'',time=+ad(hour,day,Number(g.offsets[pos]));return {name:name,key:platform(name),id:id,arr:time,dep:time,estimated:!anchors[id]}});runs.push({id:'G|'+rdate(day)+'|'+groupIndex+'|'+index,date:rdate(day),line:'GMINNA',head:g.direction==='KRAKOW'?'Kraków':'Mikluszowice',points:pts})}
    if(departureIndex>=(g.departures||[]).length){groupIndex++;departureIndex=0}
   }
   if(groupIndex<groups.length){setTimeout(tick,0);return}if(nextDay())setTimeout(tick,0);
  }setTimeout(tick,0);
 }
 function sortMode(){return q('ilPlannerSort')?q('ilPlannerSort').value:'departure'}
 function compare(a,b){var mode=sortMode();if(mode==='arrival')return +a.predA-+b.predA||+a.predW-+b.predW;if(mode==='wait')return (a.liveWait||0)-(b.liveWait||0)||+a.predA-+b.predA||+a.predW-+b.predW;return +a.predW-+b.predW||+a.predA-+b.predA}
 function directAsync(runs,from,to,date,until,valid,done){var index=0,found={};
  function tick(){if(!valid())return;var count=0;
   while(index<runs.length&&count++<22){var run=runs[index++],pts=run.points;
    for(var a=0;a<pts.length-1;a++){if(!matches(pts[a],from,run))continue;var b=-1;
     for(var t=a+1;t<pts.length;t++)if(matches(pts[t],to,run)&&pts[t].arr>pts[a].dep){b=t;if(to.key.indexOf('group:')!==0)break}if(b<0)continue;
     var row=routeLeg(run,a,b),v=evaluate(row,null,0);if(v.blocked||+v.w<date||+v.w>=until||+v.a<=+v.w)continue;row.predW=v.w;row.predA=v.a;row.sources=[v.first.departure.source,v.first.arrival.source];var key=run.id+'|'+platform(row.s),old=found[key];if(!old||+row.predW<+old.predW)found[key]=row;
    }
   }if(index<runs.length){setTimeout(tick,0);return}done(Object.keys(found).map(function(k){return found[k]}).sort(compare));
  }setTimeout(tick,0);
 }
 function transfersAsync(runs,from,to,date,until,minWait,valid,done){var endpoints={},candidates=[],found={},index=0;
  function prepare(){if(!valid())return;var count=0;
   while(index<runs.length&&count++<22){var run=runs[index++],pts=run.points,toFlags=pts.map(function(p){return matches(p,to,run)}),fromFlags=pts.map(function(p){return matches(p,from,run)});if(fromFlags.some(Boolean))candidates.push({run:run,fromFlags:fromFlags,toFlags:toFlags});
    for(var b=0;b<pts.length-1;b++){var c=-1;for(var t=b+1;t<pts.length;t++){if(fromFlags[t])break;if(toFlags[t]&&pts[t].arr>pts[b].dep){c=t;if(to.key.indexOf('group:')!==0)break}}if(c<0||!pts[b].name)continue;var leg=routeLeg(run,b,c),v=legTimes(leg);if(v.departure.blocked||v.arrival.blocked||v.departure.ms>=until)continue;var key=platform(pts[b].name);if(!endpoints[key])endpoints[key]=[];endpoints[key].push({run:run,b:b,c:c,leg:leg,time:v.departure.ms})}
   }if(index<runs.length){setTimeout(prepare,0);return}Object.keys(endpoints).forEach(function(k){endpoints[k].sort(function(a,b){return a.time-b.time})});index=0;setTimeout(process,0);
  }
  function process(){if(!valid())return;var count=0,maxWait=Math.max(180,minWait+15);
   while(index<candidates.length&&count++<13){var item=candidates[index++],run=item.run,pts=run.points;
    for(var a=0;a<pts.length-1;a++){if(!item.fromFlags[a])continue;var boarding=prediction({tripId:run.tripId,serviceDate:run.date,stopId:pts[a].id,seq:pts[a].seq,w:new Date(pts[a].dep)});if(boarding.blocked||boarding.ms<date||boarding.ms>=until)continue;
     for(var b=a+1;b<pts.length;b++){if(item.toFlags[b])break;if(item.fromFlags[b]||pts[b].arr<=pts[a].dep||pts[b].arr-pts[a].dep>180*60000)continue;var first=routeLeg(run,a,b),arrival=prediction(first.end);if(arrival.blocked)continue;var list=endpoints[platform(pts[b].name)]||[],lo=0,hi=list.length;
      while(lo<hi){var mid=(lo+hi)>>1;if(list[mid].time<arrival.ms+minWait*60000)lo=mid+1;else hi=mid}
      for(var k=lo;k<list.length;k++){var opt=list[k],gap=(opt.time-arrival.ms)/60000;if(gap>maxWait)break;if(opt.run.id===run.id||opt.run.line===run.line)continue;
       if(((run.line==='A7'&&(opt.run.line==='A50'||opt.run.line==='A57'))||(run.line==='A57'&&opt.run.line==='A7'))&&platform(pts[b].name)!=='nowe brzesko rynek')continue;
       var second=opt.leg,v=evaluate(first,second,minWait);if(!v.viable||+v.a<=+v.w||+v.a-+v.w>270*60000)continue;
       var wait=(+second.w-+first.a)/60000,id=first.k+'>'+second.k;found[id]={k:id,legs:[first,second],w:first.w,a:second.a,predW:v.w,predA:v.a,wait:wait,liveWait:v.gap,minWait:minWait,liveRescued:wait<minWait&&v.gap>=minWait,estimated:first.estimated||second.estimated};
      }
     }
    }
   }if(index<candidates.length){setTimeout(process,0);return}
   var all=Object.keys(found).map(function(k){return found[k]}),byRun={};runs.forEach(function(r){byRun[r.id]=r});
   all=all.filter(function(row){var second=byRun[row.legs[1].runId];return !second||!second.points.some(function(p){if(!matches(p,from,second))return false;var v=prediction({tripId:second.tripId,serviceDate:second.date,stopId:p.id,seq:p.seq,w:new Date(p.dep)});return !v.blocked&&v.ms>=date&&v.ms<+prediction(row.legs[1]).ms})});
   all=all.filter(function(row){return !IL_PLAN_ROWS.some(function(d){return +d.predW>=+row.predW&&+d.predA<=+row.predA&&matches(d.e,to)})});
   var collapsed={};all.forEach(function(row){var key=row.legs[0].runId+'|'+row.legs[1].runId,old=collapsed[key];if(!old||(+row.predW<+old.predW)||(+row.predW===+old.predW&&row.liveWait>=6&&old.liveWait<6)||(+row.predW===+old.predW&&(row.liveWait>=6)===(old.liveWait>=6)&&+row.legs[0].a<+old.legs[0].a))collapsed[key]=row});
   var best={};Object.keys(collapsed).forEach(function(k){var row=collapsed[k],key=row.legs[0].runId+'|'+row.legs[0].stopId,old=best[key],safe=row.liveWait>=6;if(!old||(safe&&old.liveWait<6)||(safe===(old.liveWait>=6)&&((sortMode()==='wait'&&row.liveWait<old.liveWait)||(sortMode()!=='wait'&&+row.predA<+old.predA)||(+row.predA===+old.predA&&row.liveWait<old.liveWait))))best[key]=row});
   done(Object.keys(best).map(function(k){return best[k]}).sort(compare));
  }setTimeout(prepare,0);
 }
 function dayHeading(date){return '<h4 class="il-r14-day">'+xh(pdl(date))+'</h4>'}
 function timeText(ms,source){return hm(new Date(ms))+' <small>'+xh(source)+'</small>'}
 function directRender(rows){var box=d0.createElement('section');box.id='ilDirectResults';box.setAttribute('aria-label','Połączenia bezpośrednie');q('pres').appendChild(box);
  function show(){var lastDay='';box.innerHTML='<h3 class="il-r12-direct-title">Bezpośrednie ('+rows.length+')</h3>'+rows.slice(0,visibleDirect).map(function(row,i){var v=evaluate(row,null,0),key=rdate(v.w),header=key!==lastDay?dayHeading(v.w):'';lastDay=key;return header+'<article class="pr line-'+xh(row.l.toLowerCase())+'" data-direct-index="'+i+'"><div class="prtop"><span class="prline">'+xh(row.l==='GMINNA'?'gminna':row.l)+'</span><span class="prtime">'+timeText(v.w,v.first.departure.source)+'</span><span class="prarr">→ '+timeText(v.a,v.first.arrival.source)+'</span></div><div class="prroute"><b>'+xh(row.s)+'</b><span>→</span><b>'+xh(row.e)+'</b></div><div class="prmeta">'+xh(pdl(v.w))+' • '+Math.max(1,Math.round((v.a-v.w)/60000))+' min • bezpośrednio</div></article>'}).join('');
   if(!rows.length)box.innerHTML+='<p class="plempty">Brak połączenia bezpośredniego w wybranym dniu.</p>';if(visibleDirect<rows.length){var more=d0.createElement('button');more.type='button';more.id='ilDirectMore';more.className='il-r14-more';more.textContent='Pokaż kolejne połączenia ('+(rows.length-visibleDirect)+' pozostałych)';more.onclick=function(){visibleDirect=Math.min(rows.length,visibleDirect+12);show()};box.appendChild(more)}ilPlanBadges();
  }show();
 }
 ilTransferLive=function(){var section=q('ilTransferResults');if(!section)return;section.querySelectorAll('[data-transfer-index]').forEach(function(card){var row=IL_TRANSFER_ROWS[Number(card.getAttribute('data-transfer-index'))];if(!row)return;var v=evaluate(row.legs[0],row.legs[1],row.minWait),gap=Math.floor(v.gap),note='Przewidywany czas na przesiadkę: '+gap+' min. Minimum: '+row.minWait+' min.';
   if(v.blocked)note='⚠ Kurs odwołany lub przystanek pominięty. Przeliczam połączenia.';else if(v.gap<row.minWait)note='⚠ Poniżej wybranego minimum '+row.minWait+' min: aktualny zapas '+gap+' min. Połączenie nie spełnia warunków. Przeliczam wyniki.';else if(row.liveRescued)note+=' ● Opóźnienie drugiego autobusu umożliwia tę przesiadkę.';
   if(v.viable&&v.gap<=5)note+=' ⚠ Ryzykowna przesiadka.';if(v.viable&&v.gap>45)note+=' Długie oczekiwanie na przesiadkę.';
   card.classList.toggle('il-r14-invalid',!v.viable);card.querySelector('.il-transfer-live').innerHTML='<div><b>'+xh(row.legs[0].l)+'</b> '+ilLabel(row.legs[0],false)+'</div><div><b>'+xh(row.legs[1].l)+'</b> '+ilLabel(row.legs[1],false)+'</div><div class="hint" role="status">'+xh(note)+'</div>';
  })};
 ilTransferRender=function(rows){var box=d0.createElement('section');box.id='ilTransferResults';box.setAttribute('aria-label','Połączenia z jedną przesiadką');q('pres').appendChild(box);
  function show(){var lastDay='';box.innerHTML='<h3 class="sectitle">Z jedną przesiadką ('+rows.length+')</h3><p class="hint">Wybrane minimum: '+ilTransferMinWait()+' min. Przesiadki 1–5 min są ryzykowne. Godziny LIVE mogą się zmienić.</p>'+rows.slice(0,visibleTransfers).map(function(row,i){var v=evaluate(row.legs[0],row.legs[1],row.minWait),key=rdate(v.w),header=key!==lastDay?dayHeading(v.w):'';lastDay=key;
    function part(leg,times){return '<div class="il-transfer-leg"><span class="prline">'+xh(leg.l==='GMINNA'?'gminna':leg.l)+'</span> <b>'+timeText(times.departure.ms,times.departure.source)+' → '+timeText(times.arrival.ms,times.arrival.source)+'</b><div class="prroute"><b>'+xh(leg.s)+'</b><span>→</span><b>'+xh(leg.e)+'</b></div><div class="hint">'+xh(pdl(new Date(times.departure.ms)))+' • Kierunek: '+xh(leg.d)+'</div></div>'}
    return header+'<article class="card il-transfer-card" data-transfer-index="'+i+'"><div class="prmeta">'+xh(pdl(v.w))+' • '+Math.round((v.a-v.w)/60000)+' min • 1 przesiadka</div>'+part(row.legs[0],v.first)+'<div class="il-transfer-wait">Przesiadka: <b>'+xh(row.legs[0].e)+'</b><br>'+Math.floor(v.gap)+' min na zmianę autobusu</div>'+part(row.legs[1],v.second)+(row.estimated?'<p class="hint">Część godzin linii gminnej jest szacunkowa.</p>':'')+'<div class="il-transfer-live"></div></article>';
   }).join('');if(!rows.length)box.innerHTML+='<p class="plempty">Brak przesiadek spełniających warunki w wybranym dniu.</p>';if(visibleTransfers<rows.length){var more=d0.createElement('button');more.type='button';more.id='ilTransferMore';more.className='il-r14-more';more.textContent='Pokaż kolejne połączenia ('+(rows.length-visibleTransfers)+' pozostałych)';more.onclick=function(){visibleTransfers+=18;show()};box.appendChild(more)}ilTransferLive();
  }show();
 };
 function nearby(from,to,date){if(platform(groupValue(from.n))!=='ispina lesniczowka')return;var stop=resolve('Ispina Most'),box=d0.createElement('section');box.id='ilR7Near';box.className='il-r7-near';box.innerHTML='<h3>🚶 Dojście do przystanku Ispina Most</h3><p>Możesz sprawdzić dodatkowe połączenia z przystanku Ispina Most. Odjazdy będą z przystanku Ispina Most, a nie z przystanku Ispina Leśniczówka.</p><label for="ilR7WalkMinutes">Orientacyjny czas dojścia: </label><select id="ilR7WalkMinutes">'+[10,15,20,30].map(function(v){return '<option value="'+v+'"'+(v===Number(window.IL_R7_WALK_MIN||15)?' selected':'')+'>'+v+' min</option>'}).join('')+'</select><p class="hint">Czas jest orientacyjny. Sprawdź odległość i warunki dojścia.</p><button type="button" id="ilR7UseStop" class="il-r14-more">Szukaj z przystanku Ispina Most po doliczeniu dojścia</button>';q('pres').appendChild(box);q('ilR7UseStop').onclick=function(){var minutes=Number(q('ilR7WalkMinutes').value),n=new Date(+date+minutes*60000);q('pfrom').value=stop.n;q('pfrom').dispatchEvent(new Event('change',{bubbles:true}));q('pdate').value=n.getFullYear()+'-'+z(n.getMonth()+1)+'-'+z(n.getDate());q('ptime').value=hm(n);window.IL_R7_WALK_MIN=minutes;plan()}}
 function query(){return ['pfrom','pto','pdate','ptime','ilTransferMode','ilTransferMin','ilPlannerSort'].map(function(id){return q(id)?q(id).value:''}).join('|')}
 plan=function(liveRefresh){
  var isLive=liveRefresh===true,myGeneration=++searchGeneration,previous=IL_TRANSFER_ROWS.slice();clearTimeout(refreshTimer);refreshTimer=0;IL_PLAN_ROWS=[];IL_TRANSFER_ROWS=[];hasSearched=true;window.IL_R14_HAS_SEARCHED=true;
  var signature=query();if(signature!==lastQuery){visibleDirect=12;visibleTransfers=18;lastTransferKeys=[]}lastQuery=signature;
  var from=resolveInput(q('pfrom')),to=resolveInput(q('pto')),n=q('pdate').value?new Date(q('pdate').value+'T'+(q('ptime').value||'00:00')):new Date(),pres=q('pres');
  if(!from||!to){pres.innerHTML='<div class="plempty" role="alert">Wybierz pełny przystanek z podpowiedzi albo opcję „wszystkie przystanki”. '+(!from?'Popraw miejsce startu.':'Popraw cel podróży.')+'</div>';return}if(!isFinite(+n)){pres.innerHTML='<div class="plempty" role="alert">Ustaw poprawną datę i godzinę.</div>';return}
  q('pfrom').value=from.n;q('pto').value=to.n;['pfrom','pto'].forEach(function(id){q(id).dispatchEvent(new Event('change',{bubbles:true}))});
  var until=new Date(n);until.setHours(0,0,0,0);until.setDate(until.getDate()+1);pres.innerHTML='<p class="hint" id="ilPlannerHorizon">Tylko '+xh(pdl(n))+' • od '+hm(n)+' do końca dnia</p><div id="ilTransferBusy" class="card" role="status">Szukam połączeń…</div>';
  function valid(){return myGeneration===searchGeneration}
  runsAsync(n,valid,function(runs){if(!valid())return;directAsync(runs,from,to,+n,+until,valid,function(rows){if(!valid())return;IL_PLAN_ROWS=rows;directRender(rows);var mode=q('ilTransferMode').value;
    function finish(transfers){if(!valid())return;var busy=q('ilTransferBusy');if(busy)busy.remove();IL_TRANSFER_ROWS=transfers;
     if(mode!=='direct')ilTransferRender(transfers);nearby(from,to,n);
     var invalid=previous.filter(function(row){return !evaluate(row.legs[0],row.legs[1],ilTransferMinWait()).viable});if(isLive&&invalid.length){var note=d0.createElement('p');note.className='il-r14-refresh-note';note.setAttribute('role','status');note.textContent='LIVE: usunięto '+invalid.length+' połączeń. Kurs odwołano, pominięto przystanek albo zapas spadł poniżej minimum '+ilTransferMinWait()+' min.';pres.insertBefore(note,pres.firstChild)}
     lastTransferKeys=transfers.map(function(r){return r.k});
    }if(mode==='direct')finish([]);else transfersAsync(runs,from,to,+n,+until,ilTransferMinWait(),valid,finish);
   })});
 };
 ilPoll=function(){if(document.hidden)return;try{var raw=window.IspinaNative.getMldRealtimeJson()||'',changed=false;if(raw!==IL_LAST_RAW){IL_LAST_RAW=raw;var p=raw?ilParse(JSON.parse(raw)):{items:{},stamp:0};IL_RT=p.items;IL_RT_TIME=p.stamp;LT=p.stamp*1000;L=[];changed=true}if(!ilFresh(IL_RT_TIME)&&IL_RT_TIME){IL_RT_TIME=0;IL_RT={};LT=0;changed=true}if(changed){deps();ilPlanBadges();if(hasSearched){searchGeneration++;clearTimeout(refreshTimer);refreshTimer=setTimeout(function(){plan(true)},250)}}rs.textContent=ilFresh(IL_RT_TIME)?'● Na żywo • '+hm(new Date(IL_RT_TIME*1000))+' • odświeżanie co 30 s':'Rozkład planowy • brak bieżących danych'}catch(e){}};
 q('pgo').onclick=function(){plan()};
 window.IL_R6_GMINNA_META=function(){return RG&&RG.stops&&RG.serviceGroups?{stops:RG.stops.map(function(s){return s.name}),days:RG.serviceGroups.map(function(s){return s.dayType})}:null};
 window.IL_PLANNER_FIX_VERSION='planner-r14-2-manual-day-corridor-20261010';
})();
