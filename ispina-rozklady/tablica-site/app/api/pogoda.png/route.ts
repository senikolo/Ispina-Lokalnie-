import { ImageResponse } from "next/og";
import { createElement as h } from "react";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const latitude = 50.11336;
const longitude = 20.37746;
const C = { navy: "#174875", blue: "#367CAD", pale: "#F3F7FC", ink: "#30485F", muted: "#657A8B", border: "#DAE6F1" };
type Forecast = {
  current?: { temperature_2m?: number; apparent_temperature?: number; relative_humidity_2m?: number; precipitation?: number; wind_speed_10m?: number; weather_code?: number };
  daily?: { time?: string[]; temperature_2m_max?: number[]; temperature_2m_min?: number[]; precipitation_probability_max?: number[]; weather_code?: number[] };
};

function describe(code: number | undefined): string {
  if (code === 0) return "Bezchmurnie";
  if (code === 1) return "Przeważnie pogodnie";
  if (code === 2) return "Częściowe zachmurzenie";
  if (code === 3) return "Pochmurno";
  if (code === 45 || code === 48) return "Mgła";
  if ([51, 53, 55, 56, 57].includes(code ?? -1)) return "Mżawka";
  if ([61, 63, 65, 66, 67, 80, 81, 82].includes(code ?? -1)) return "Deszcz";
  if ([71, 73, 75, 77, 85, 86].includes(code ?? -1)) return "Śnieg";
  if ([95, 96, 99].includes(code ?? -1)) return "Burza";
  return "Zmienna pogoda";
}
function n(value: number | undefined, suffix = ""): string {
  return typeof value === "number" && Number.isFinite(value) ? `${Math.round(value)}${suffix}` : "—";
}
function label(t: string, size: number, color = C.navy, weight = 400) {
  return h("div", { style: { fontSize: size, color, fontWeight: weight, lineHeight: 1.2 } }, t);
}
function dayCard(title: string, high?: number, low?: number, code?: number, rain?: number) {
  return h("div", { style: { display: "flex", flexDirection: "column", width: 180, padding: "17px 16px", borderRadius: 15, border: `1px solid ${C.border}`, background: "#FFFFFF", gap: 5 } },
    label(title, 18, C.blue, 700),
    label(`${n(high, "°")} / ${n(low, "°")}`, 25, C.navy, 700),
    label(describe(code), 14, C.ink),
    label(`Opady: ${n(rain, "%")}`, 13, C.muted)
  );
}
function makeCard(d?: Forecast, error = false) {
  const c = d?.current;
  const daily = d?.daily;
  const col = (size: number) => ({ display: "flex", flexDirection: "column", gap: size });
  const days = ["Dziś", "Jutro", "Pojutrze"];
  return h("div", { style: { width: "1200px", height: "360px", display: "flex", background: "#FFFFFF", borderRadius: 24, border: `2px solid ${C.border}`, padding: "28px 30px", fontFamily: "Arial, sans-serif", color: C.navy, gap: 28 } },
    h("div", { style: { ...col(9), width: "460px", padding: "12px 12px 10px 18px", background: C.pale, borderRadius: 18, justifyContent: "center" } },
      label("ISPINA · POGODA", 17, C.blue, 700),
      label(error ? "Dane chwilowo niedostępne" : `${n(c?.temperature_2m, "°C")}  ${describe(c?.weather_code)}`, error ? 32 : 39, C.navy, 700),
      label(error ? "Spróbuj ponownie później" : `Odczuwalna ${n(c?.apparent_temperature, "°C")}   ·   Wiatr ${n(c?.wind_speed_10m, " km/h")}`, 17, C.ink),
      label(error ? "Nie wyświetlamy nieaktualnej prognozy" : `Opady ${typeof c?.precipitation === "number" ? c.precipitation.toFixed(1) + " mm" : "—"}   ·   Wilgotność ${n(c?.relative_humidity_2m, "%")}`, 17, C.ink)
    ),
    h("div", { style: { ...col(10), flex: 1, justifyContent: "center" } },
      label("PROGNOZA NA 3 DNI", 16, C.blue, 700),
      h("div", { style: { display: "flex", gap: 11 } },
        ...days.map((name, i) => dayCard(name, daily?.temperature_2m_max?.[i], daily?.temperature_2m_min?.[i], daily?.weather_code?.[i], daily?.precipitation_probability_max?.[i]))
      ),
      label("Prognoza modelowa dla Ispiny · Źródło: Open-Meteo.com", 14, C.muted)
    )
  );
}

export async function GET(): Promise<ImageResponse> {
  try {
    const api = new URL("https://api.open-meteo.com/v1/forecast");
    api.searchParams.set("latitude", String(latitude));
    api.searchParams.set("longitude", String(longitude));
    api.searchParams.set("timezone", "Europe/Warsaw");
    api.searchParams.set("forecast_days", "3");
    api.searchParams.set("current", "temperature_2m,apparent_temperature,precipitation,relative_humidity_2m,weather_code,wind_speed_10m");
    api.searchParams.set("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code");
    const response = await fetch(api, { next: { revalidate: 1800 }, signal: AbortSignal.timeout(9000) });
    if (!response.ok) throw new Error("Open-Meteo not available");
    const data = (await response.json()) as Forecast;
    if (typeof data.current?.temperature_2m !== "number" || !Array.isArray(data.daily?.time)) throw new Error("Incomplete forecast");
    return new ImageResponse(makeCard(data), {
      width: 1200, height: 360,
      headers: { "Cache-Control": "public, max-age=300, s-maxage=1800, stale-while-revalidate=900" },
    });
  } catch {
    return new ImageResponse(makeCard(undefined, true), {
      width: 1200, height: 360,
      headers: { "Cache-Control": "public, max-age=60, s-maxage=60" },
    });
  }
}
