import { ImageResponse } from "next/og";
import { createElement as h, type CSSProperties } from "react";
import { createWeatherLoader, type Forecast, type WeatherResult } from "../../../lib/weather.mjs";
import { summarizeWeatherChanges } from "../../../lib/weather-trends.mjs";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
const load = createWeatherLoader();
const C = { navy: "#174875", blue: "#367CAD", pale: "#F3F7FC", ink: "#30485F", muted: "#526477", border: "#DAE6F1" };
function describe(code: number | undefined): string {
  if (code === 0) return "Bezchmurnie";
  if (code === 1) return "Pogodnie";
  if (code === 2) return "Częściowo pochmurno";
  if (code === 3) return "Pochmurno";
  if (code === 45 || code === 48) return "Mgła";
  if ([51, 53, 55, 56, 57].includes(code ?? -1)) return "Mżawka";
  if ([61, 63, 65, 66, 67, 80, 81, 82].includes(code ?? -1)) return "Deszcz";
  if ([71, 73, 75, 77, 85, 86].includes(code ?? -1)) return "Śnieg";
  if ([95, 96, 99].includes(code ?? -1)) return "Burza";
  return "Zmienna pogoda";
}

function n(value: number, suffix = ""): string {
  return `${Math.round(value)}${suffix}`;
}
function label(t: string, size: number, color = C.navy, weight = 400) {
  return h("div", { style: { display: "flex", fontSize: size, color, fontWeight: weight, lineHeight: 1.25, flexShrink: 0 } }, t);
}
const col = (gap: number): CSSProperties => ({ display: "flex", flexDirection: "column", gap });
function icon(code: number, size = 60, isDay = true) {
  const sun = code < 3;
  const rain = [51,53,55,56,57,61,63,65,66,67,80,81,82,95,96,99].includes(code);
  const snow = [71,73,75,77,85,86].includes(code);
  return h("svg", { width: size, height: size, viewBox: "0 0 64 64", fill: "none" },
    sun && (isDay
      ? h("g", { stroke: "#C89537", strokeWidth: 3, strokeLinecap: "round" }, h("circle", {cx: 28, cy: 25, r: 11, fill: "#FFF2D7"}), h("path", {d: "M28 6v4M28 40v4M9 25h4M43 25h4M14 11l3 3M39 36l3 3M14 39l3-3M39 14l3-3"}))
      : h("path", {d: "M37 8a19 19 0 1 0 16 28A18 18 0 0 1 37 8Z", fill: "#E5EDF8", stroke: C.blue, strokeWidth: 3})),
    code !== 0 && h("path", {d: "M15 43a11 11 0 0 1 0-22 16 16 0 0 1 30-1 12 12 0 0 1 2 24H15Z", fill: "#E5F0F8", stroke: C.blue, strokeWidth: 3}),
    rain && h("path", {d: "M22 50l-3 7M35 50l-3 7M48 50l-3 7", stroke: C.blue, strokeWidth: 3, strokeLinecap: "round"}),
    snow && h("g", {fill: C.blue}, ...[23,35,47].map(cx => h("circle", {cx, cy: 54, r: 2.5}))),
    [45,48].includes(code) && h("path", {d: "M12 49h40M18 56h30", stroke: C.blue, strokeWidth: 3, strokeLinecap: "round"})
  );
}
function dayCard(d: Forecast, i: number, compact: boolean) {
  const title = ["Dziś", "Jutro", "Pojutrze"][i];
  const daily = d.daily;
  const temps = `${n(daily.temperature_2m_max[i], "°")} / ${n(daily.temperature_2m_min[i], "°")}`;
  if (compact) return h("div", { style: { ...col(5), flexShrink: 0, padding: "14px 18px", borderRadius: 18, background: "#FFFFFF", border: `1px solid ${C.border}` } },
    h("div", {style: {display: "flex", alignItems: "center", justifyContent: "space-between"}},
      label(title, 34, C.navy, 700), icon(daily.weather_code[i], 50), label(temps, 38, C.navy, 700)),
    h("div", {style: {display: "flex", justifyContent: "space-between"}}, label(describe(daily.weather_code[i]), 28, C.ink), label(`Opady: ${daily.precipitation_probability_max[i]}%`, 28, C.muted))
  );
  return h("div", { style: { ...col(12), flexShrink: 0, width: 210, padding: "22px 18px", borderRadius: 18, border: `1px solid ${C.border}`, background: "#FFFFFF" } },
    label(title, 27, C.blue, 700), icon(daily.weather_code[i], 58), label(temps, 35, C.navy, 700),
    label(describe(daily.weather_code[i]), 25, C.ink), label(`Opady: ${daily.precipitation_probability_max[i]}%`, 24, C.muted)
  );
}
function makeCard(result: WeatherResult | undefined, compact: boolean, part: string | null) {
  const d = result?.data, c = d?.current;
  const height = part === "brief" ? 374 : part === "now" ? 550 : part === "daily" ? 610 : part === "trend" ? 330 : compact ? 1080 : 500;
  const size = {width: part === "brief" ? 720 : compact ? 600 : 1200, height};
  const root: CSSProperties = { ...size, ...col(18), padding: compact ? "24px" : "26px 30px", background: C.pale, borderRadius: 28, border: `2px solid ${C.border}`, fontFamily: "sans-serif", color: C.navy };
  if ((part === "trend" || part === "brief") && (!result || !d || !c)) return h("div", {style:{...root,
    padding:"21px 23px", gap:15, justifyContent:"center", background:"#FFFFFF", borderRadius:20}},
    label("CO ZMIENI SIĘ DZIŚ?", 30, C.navy, 700),
    label("Prognoza chwilowo niedostępna.", 28, C.ink, 600),
    label("Sprawdź ponownie za chwilę.", 23, C.muted),
    label("Źródło: Open-Meteo", 19, C.muted));
  if (!result || !d || !c) return h("div", {style: {...root, justifyContent: "center", gap: 24}},
    label("POGODA W ISPINIE", compact ? 34 : 30, C.blue, 700),
    label("Dane chwilowo niedostępne", compact ? 46 : 52, C.navy, 700),
    label("Spróbuj ponownie później.", compact ? 32 : 30, C.ink),
    label("Nie pokazujemy starej prognozy jako aktualnej.", compact ? 32 : 30, C.muted),
    label("Źródło: Open-Meteo.com", compact ? 30 : 26, C.muted));
  const checked = new Intl.DateTimeFormat("pl-PL", { timeZone: "Europe/Warsaw", day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }).format(result.fetchedAt);
  const currentTime = new Intl.DateTimeFormat("pl-PL", {timeZone: "Europe/Warsaw", hour: "2-digit", minute: "2-digit"}).format(c.time * 1000);
  const current = h("div", {style: { ...col(compact ? 10 : 12), width: compact ? "100%" : 414, justifyContent: "center" }},
    label("POGODA W ISPINIE", compact ? 34 : 26, C.blue, 700),
    h("div", {style: {display: "flex", alignItems: "center", justifyContent: "space-between"}}, label(n(c.temperature_2m, "°C"), compact ? 106 : 100, C.navy, 700), icon(c.weather_code, compact ? 100 : 88, c.is_day === 1)),
    label(describe(c.weather_code), compact ? 36 : 32, C.navy, 700),
    label(`Odczuwalna ${n(c.apparent_temperature, "°C")}`, compact ? 34 : 28, C.ink),
    label(`Wiatr ${n(c.wind_speed_10m, " km/h")}`, compact ? 34 : 28, C.ink),
    label(`Opady ${c.precipitation.toFixed(1).replace(".", ",")} mm / 15 min`, compact ? 32 : 26, C.ink)
  );
  const forecast = h("div", {style: {...col(compact ? 10 : 12), ...(compact ? {} : {flex: 1}), flexShrink: 0}},
    label("PROGNOZA · MAKS. / MIN.", compact ? 30 : 24, C.blue, 700),
    h("div", {style: compact ? col(10) : {display: "flex", gap: 12}}, ...[0,1,2].map(i => dayCard(d, i, compact)))
  );
  if (part === "brief") {
    const messages = summarizeWeatherChanges(d, result.fetchedAt).slice(0, 2);
    const colorFor = (message: string) => {
      const txt = message.toLowerCase();
      if (/burz|bardzo silne/.test(txt)) return {bg:"#FDEBF5", text:"#84295D", accent:"#AF2777"};
      if (/wiatr|porywy/.test(txt)) return {bg:"#FFF1DE", text:"#8B4C13", accent:"#D58A21"};
      if (/deszcz|śnieg|opad/.test(txt) && !/bez większych|mało prawdopodobne|brak wyraźnego/.test(txt))
        return {bg:"#DDEFFD", text:"#14548A", accent:"#2084C8"};
      return {bg:"#DFF8E9", text:"#176646", accent:"#2B946B"};
    };
    const bits = messages.map(message => {
      const colors = colorFor(message);
      return h("div", {style:{display:"flex", alignItems:"center", flex:1, background:colors.bg,
          borderRadius:12, padding:"11px 14px", gap:13}},
        h("div", {style:{width:7,height:30,background:colors.accent,borderRadius:6,flexShrink:0}}),
        h("div", {style:{display:"flex", fontSize:26, lineHeight:1.18, color:colors.text,
            fontWeight:650, flexWrap:"wrap"}}, message));
    });
    return h("div", {style:{...size,...col(0),padding:0,overflow:"hidden",
        borderRadius:24,background:"#F8FBFF",border:"2px solid #BDD8F2",fontFamily:"sans-serif"}},
      h("div", {style:{display:"flex",height:54,background:"#14518A",
          alignItems:"center",justifyContent:"space-between",padding:"0 22px"}},
        label("ISPINA  •  POGODA",27,"#FFFFFF",750),
        label("DZISIAJ",19,"#DCEFFF",700)),
      h("div", {style:{display:"flex",height:121,background:"#E8F3FF",
          justifyContent:"space-between",alignItems:"center",padding:"5px 20px",gap:10}},
        h("div", {style:{display:"flex",alignItems:"center",gap:12}},
          icon(c.weather_code,74,c.is_day===1),
          label(n(c.temperature_2m,"°C"),74,"#174875",750)),
        h("div", {style:{...col(6),alignItems:"flex-end",flexShrink:0}},
          label(describe(c.weather_code),26,"#174875",700),
          label(`Wiatr ${n(c.wind_speed_10m)} km/h`,21,"#46647D",500))),
      h("div", {style:{...col(9),padding:"12px 18px 7px",flex:1}},
        ...bits),
      h("div", {style:{display:"flex",height:35,justifyContent:"space-between",
          alignItems:"center",padding:"0 20px",gap:8}},
        label("Co może zmienić Twoje plany?",19,"#365A77",650),
        label(`Aktualizacja: ${checked}`,17,"#647D93",500))
    );
  }
  if (part === "trend") {
    const messages = summarizeWeatherChanges(d, result.fetchedAt);
    const tone = (message: string) => {
      const m = message.toLowerCase();
      if (/burz|bardzo silne/.test(m)) return {bg:"#FFF1F1", accent:"#B54B54", text:"#873942"};
      if (/wiatr|porywy/.test(m)) return {bg:"#FBF4E9", accent:"#BA843F", text:"#715025"};
      if (/opad|deszcz|śnieg|mżaw/.test(m) && !/bez większych opadów|mało prawdopodobne|brak wyraźnego sygnału/.test(m))
        return {bg:"#EFF6FD", accent:"#367CAD", text:"#174875"};
      if (/bez większych opadów|mało prawdopodobne|stabiln|przejaśnien|brak wyraźnego sygnału/.test(m))
        return {bg:"#EDF7F3", accent:"#3C8C78", text:"#285C4F"};
      return {bg:"#F2F6FB", accent:"#6485AC", text:C.navy};
    };
    return h("div", {style: {...root, padding:"15px 17px", gap:8, borderRadius:20, background:"#FFFFFF",
      border:`1px solid ${C.border}`, justifyContent:"space-between"}},
      h("div", {style:{display:"flex", justifyContent:"space-between", alignItems:"center", gap:8, flexShrink:0}},
        label("CO ZMIENI SIĘ DZIŚ?", 30, C.navy, 700),
        label("ISPINA", 20, C.blue, 700)),
      h("div", {style: {...col(7), justifyContent:"center", flex:1}},
        ...messages.map(message=>{
          const t=tone(message);
          return h("div", {style:{display:"flex", alignItems:"center", padding:"8px 11px", gap:11,
            borderRadius:11, background:t.bg, flexShrink:0}},
            h("div", {style:{display:"flex", width:6, height:28, borderRadius:6,
              background:t.accent, flexShrink:0}}),
            h("div", {style:{display:"flex", flexWrap:"wrap", fontSize:26,
              lineHeight:1.22, color:t.text, fontWeight:600}}, message));
        })),
      label(`Model Open-Meteo · sprawdzono ${checked}`, 19, C.muted));
  }
  if (part === "now") return h("div", {style: {...root, justifyContent: "space-between"}}, current,
    h("div", {style: col(3)}, label(`Dane modelowe na ${currentTime}`, 29, C.muted), label(`Sprawdzono ${checked}`, 29, C.muted)));
  if (part === "daily") return h("div", {style: {...root, justifyContent: "space-between"}}, forecast,
    h("div", {style: col(3)}, label(`Sprawdzono ${checked}`, 29, C.muted), label("Open-Meteo · odświeżanie ok. 30 min", 28, C.muted)));
  return h("div", {style: root},
    compact ? current : h("div", {style: {display: "flex", gap: 24, flex: 1}}, current, forecast),
    compact && forecast,
    h("div", {style: {...col(3), borderTop: `1px solid ${C.border}`, paddingTop: 12}},
      label(`Sprawdzono ${checked}`, compact ? 30 : 24, C.muted),
      label(`Dane na ${currentTime} · Open-Meteo.com`, compact ? 30 : 24, C.muted),
      label("Odświeżanie ok. 30 min", compact ? 28 : 22, C.muted))
  );
}

export async function GET(request: Request): Promise<Response> {
  const params = new URL(request.url).searchParams;
  const part = ["now", "daily", "trend", "brief"].includes(params.get("part") || "") ? params.get("part") : null;
  const compact = !!part || params.get("layout") === "compact";
  let result: WeatherResult | undefined;
  try { result = await load(); } catch { /* A quiet error card, never stale weather. */ }
  const ttl = result ? Math.max(0, Math.floor((result.expires - Date.now()) / 1000)) : 0;
  const response = new ImageResponse(makeCard(result, compact, part), { width: part === "brief" ? 720 : compact ? 600 : 1200, height: part === "brief" ? 374 : part === "now" ? 550 : part === "daily" ? 610 : part === "trend" ? 330 : compact ? 1080 : 500 });
  // Buffer rendering here so image generation errors are observed before sending headers.
  const png = await response.arrayBuffer();
  return new Response(png, {headers: {
    "Content-Type": "image/png",
    "Cache-Control": "public, max-age=0, must-revalidate",
    "CDN-Cache-Control": ttl ? `public, s-maxage=${ttl}, must-revalidate` : "no-store",
    "Vercel-CDN-Cache-Control": ttl ? `public, s-maxage=${ttl}, must-revalidate` : "no-store",
    "X-Content-Type-Options": "nosniff",
    "X-Weather-Status": result ? "ok" : "unavailable",
    "X-Weather-Location": "50.11336,20.37746;Europe/Warsaw",
    ...(result ? { "X-Weather-Checked": new Date(result.fetchedAt).toISOString(), "X-Weather-Data-Time": new Date(result.data.current.time * 1000).toISOString(), "X-Weather-Expires": new Date(result.expires).toISOString() } : {})
  }});
}
