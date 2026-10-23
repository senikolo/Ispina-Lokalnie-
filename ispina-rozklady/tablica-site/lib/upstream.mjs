// Module cache coalesces concurrent callers; CDN caching supplies the shared outer layer.
export function cachedLoader(load,ttl,failureTtl=10000){
 let value,expires=0,pending,error;
 return async()=>{
  if(Date.now()<expires){if(error)throw error;return value;}
  if(pending)return pending;
  pending=(async()=>{try{value=await load();error=null;expires=Date.now()+ttl;return value;}catch(e){error=e;expires=Date.now()+failureTtl;throw e;}finally{pending=null;}})();
  return pending;
 };
}
export async function bounded(url,max){
 const r=await fetch(url,{signal:AbortSignal.timeout(7000),redirect:'manual',headers:{'User-Agent':'IspinaLokalnieBoard/1.1'},cache:'no-store'});
 if(!r.ok)throw new Error('Upstream '+r.status);
 const text=await r.text();if(text.length>max)throw new Error('Upstream too large');return text;
}
