function fixture(second='06:59:00'){
const calendar={all:{startDate:'20261001',endDate:'20261231',monday:'1',tuesday:'1',wednesday:'1',thursday:'1',friday:'1',saturday:'1',sunday:'1'}};
const point=(id,time,seq)=>({stopId:id,arrival:time,departure:time,seq});
return {fetchedAt:+new Date('2026-10-09T10:00:00+02:00'),calendar,calendarDates:[],stops:{s:{name:'Test Start'},h:{name:'Nowe Brzesko Rynek'},e:{name:'Test Cel'}},trips:[
{tripId:'first',route:'A7',serviceId:'all',headsign:'Nowe Brzesko',stopTimes:[point('s','06:50:00',1),point('h','07:00:00',2)]},
{tripId:'second',route:'A50',serviceId:'all',headsign:'Test Cel',stopTimes:[point('h',second,1),point('e','07:50:00',2)]},
...['A4','A65','A57'].map((route,i)=>({tripId:'unused'+i,route,serviceId:'off',headsign:'Unused',stopTimes:[]}))]};}
function feed(e,delays={first:0,second:600},canceled=false){const ts=Math.floor(e.clock.now/1000);e.core.rt=JSON.stringify({header:{timestamp:ts},entity:['first','second'].map(id=>({trip_update:{trip:{trip_id:id,start_date:'20261010',...(canceled&&id==='first'?{schedule_relationship:'CANCELED'}:{})},timestamp:ts,stop_time_update:(id==='first'?[['s',1],['h',2]]:[['h',1],['e',2]]).map(([stop_id,stop_sequence])=>({stop_id,stop_sequence,arrival:{delay:delays[id]},departure:{delay:delays[id]}}))}}))});e.w.__AUDIT.poll();}

module.exports={fixture,feed};
