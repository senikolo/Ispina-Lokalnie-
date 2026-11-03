export function metnoFixture(now=Date.now()) {
  const start=Math.floor(now/3600e3)*3600e3;
  return {type:'Feature',geometry:{type:'Point',coordinates:[20.3774,50.1133,182]},properties:{
    meta:{updated_at:new Date(now-2*3600e3).toISOString(),units:{air_temperature:'celsius',wind_speed:'m/s',precipitation_amount:'mm',cloud_area_fraction:'%'}},
    timeseries:Array.from({length:120},(_,i)=>({time:new Date(start+i*3600e3).toISOString(),data:{
      instant:{details:{air_temperature:6+Math.sin(i/4)*3,wind_speed:3,cloud_area_fraction:75}},
      next_1_hours:{summary:{symbol_code:'cloudy'},details:{precipitation_amount:0}}
    }}))}};
}
