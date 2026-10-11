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
