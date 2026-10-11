import { buildNetwork, getStopSuggestions, searchConnections, minutesToTime, findWasteEvents } from "./planner.js";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const MLD_SOURCE = "https://kolejemalopolskie.com.pl/pl/rozklad-jazdy/rozklady-autobusowe";
const GMINNA_SOURCE = "https://www.drwinia.pl/aktualnosci/funkcjonowanie-linii-komunikacyjnych-na-terenie-gminy-drwinia-w-roku-2026,news-3151";
const NEWS_SOURCE = "https://ispinalokalnie.wordpress.com/aktualnosci/";
const state = {
  screen: "home",
  mld: null,
  gminna: null,
  waste: null,
  network: buildNetwork(null, null),
  news: [],
  newsError: "",
  history: [],
  historyError: "",
  weather: null,
  weatherError: "",
  route: { from: emptyStop(), to: emptyStop() },
  results: [],
  resultMessage: "",
  senior: false,
  readerRate: 1,
  pending: new Set(),
};

function emptyStop() { return { value: "", selected: null, accepted: false }; }
function escapeHtml(value) { return String(value ?? "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch]); }
function localDate() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
}
function localTime() {
  const now = new Date();
  return `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
}
function formatDate(value, options = { weekday: "long", day: "numeric", month: "long" }) {
  const date = new Date(`${value}T12:00:00`);
  if (!Number.isFinite(date.getTime())) return value;
  return new Intl.DateTimeFormat("pl-PL", options).format(date);
}
function announce(text) { const live = $("#liveRegion"); if (live) live.textContent = text; }
function native() { return window.ILNative || null; }
function setPending(key, value) { if (value) state.pending.add(key); else state.pending.delete(key); }
function isPending(key) { return state.pending.has(key); }

window.IL = {
  handleBack() {
    if (state.screen === "home") return false;
    setScreen("home");
    return true;
  },
  nativeData(kind, payload, error) {
    if (kind === "news") {
      if (Array.isArray(payload)) {
        state.news = payload;
        state.newsError = "";
        try { localStorage.setItem("il_news_cache", JSON.stringify({ savedAt: Date.now(), posts: payload })); } catch (_) { }
      } else state.newsError = error || "Nie udało się pobrać aktualności.";
      setPending("news", false);
    } else if (kind === "weather") {
      if (payload && payload.current) { state.weather = payload; state.weatherError = ""; try { localStorage.setItem("il_weather_cache", JSON.stringify(payload)); } catch (_) { } }
      else state.weatherError = error || "Nie udało się pobrać pogody.";
      setPending("weather", false);
    } else if (kind === "history") {
      if (Array.isArray(payload)) {
        state.history = payload.filter(page => ["historia-ispiny", "ciekawostki-historyczne"].includes(page.slug));
        state.historyError = "";
        try { localStorage.setItem("il_history_cache", JSON.stringify({ savedAt: Date.now(), pages: state.history })); } catch (_) { }
      } else state.historyError = error || "Nie udało się pobrać historii.";
      setPending("history", false);
    } else if (kind === "schedule") {
      if (payload && Array.isArray(payload.trips)) {
        state.mld = payload;
        state.network = buildNetwork(state.mld, state.gminna);
        state.resultMessage = "Rozkład MLD odświeżony.";
      } else state.resultMessage = error || "Nie udało się odświeżyć rozkładu.";
      setPending("schedule", false);
    } else if (kind === "schedule-ready") {
      try {
        state.mld = readCachedSchedule();
        if (!Array.isArray(state.mld?.trips)) throw new Error("Pobrany rozkład ma nieprawidłowy format.");
        state.network = buildNetwork(state.mld, state.gminna);
        state.resultMessage = "Rozkład MLD odświeżony.";
      } catch (exception) {
        state.resultMessage = exception?.message || error || "Nie udało się odczytać pobranego rozkładu.";
      }
      setPending("schedule", false);
    }
    if (state.screen === "home" || state.screen === kind || ((kind === "schedule" || kind === "schedule-ready") && state.screen === "planner")) render();
  },
};

async function loadData() {
  try {
    const [gminnaResponse, wasteResponse] = await Promise.all([
      fetch("./data/gminna-2026-09-25.json", { cache: "no-store" }),
      fetch("./data/waste-2026.json", { cache: "no-store" }),
    ]);
    if (gminnaResponse.ok) state.gminna = await gminnaResponse.json();
    if (wasteResponse.ok) state.waste = await wasteResponse.json();
  } catch (_) { /* the app still renders its navigation and links */ }
  const bridge = native();
  if (bridge) {
    try {
      state.mld = readCachedSchedule();
    } catch (_) { state.mld = null; }
  }
  try {
    const newsCache = JSON.parse(localStorage.getItem("il_news_cache") || "null");
    if (Array.isArray(newsCache?.posts)) state.news = newsCache.posts;
    const weatherCache = JSON.parse(localStorage.getItem("il_weather_cache") || "null");
    if (weatherCache?.current) state.weather = weatherCache;
    const historyCache = JSON.parse(localStorage.getItem("il_history_cache") || "null");
    if (Array.isArray(historyCache?.pages)) state.history = historyCache.pages;
  } catch (_) { }
  state.network = buildNetwork(state.mld, state.gminna);
  state.senior = localStorage.getItem("il_senior") === "1";
  const readerRate = Number(localStorage.getItem("il_reader_rate") || 1);
  state.readerRate = [0.8, 1, 1.2].includes(readerRate) ? readerRate : 1;
  document.body.classList.toggle("senior", state.senior);
  render();
  requestNews();
  requestWeather();
}

function readCachedSchedule() {
  const bridge = native();
  if (!bridge) return null;
  const size = Number(bridge.getCachedScheduleSize());
  if (!Number.isSafeInteger(size) || size <= 0 || size > 20 * 1024 * 1024) return null;
  const chunkSize = 64 * 1024;
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let text = "";
  for (let offset = 0; offset < size; offset += chunkSize) {
    const encoded = bridge.readCachedScheduleChunk(offset, chunkSize);
    if (!encoded) throw new Error("Nie udało się odczytać zapisanej kopii rozkładu.");
    const binary = atob(encoded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    text += decoder.decode(bytes, { stream: offset + chunkSize < size });
  }
  text += decoder.decode();
  return JSON.parse(text);
}

function requestNews() {
  if (!native() || isPending("news")) return;
  setPending("news", true);
  native().requestNews();
}
function requestWeather() {
  if (!native() || isPending("weather")) return;
  setPending("weather", true);
  native().requestWeather();
}
function requestHistory() {
  if (!native() || isPending("history")) return;
  setPending("history", true);
  if (state.screen === "history") render();
  native().requestHistory();
}
function requestSchedule() {
  if (!native() || isPending("schedule")) return;
  setPending("schedule", true);
  state.resultMessage = "Pobieram oficjalny rozkład MLD…";
  render();
  native().requestScheduleSync();
}
function openExternal(url) {
  if (native()) native().openExternal(url);
  else window.open(url, "_blank", "noopener,noreferrer");
}

function setScreen(screen) {
  state.screen = screen;
  native()?.setCurrentScreen(screen);
  state.results = [];
  state.resultMessage = "";
  render();
  $("#mainContent")?.focus({ preventScroll: true });
  if (screen === "news") requestNews();
  if (screen === "history") requestHistory();
  if (screen === "home") { requestNews(); requestWeather(); }
}

function render() {
  $$(".bottom-nav button").forEach(button => {
    if (button.dataset.screen === state.screen) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  const main = $("#mainContent");
  if (!main) return;
  const screens = {
    home: renderHome,
    planner: renderPlanner,
    news: renderNews,
    waste: renderWaste,
    more: renderMore,
    weather: renderWeather,
    radio: renderRadio,
    history: renderHistory,
    services: renderServices,
    settings: renderSettings,
    about: renderAbout,
  };
  main.innerHTML = `<div data-screen-content>${(screens[state.screen] || renderHome)()}</div>`;
  const content = $(`[data-screen-content]`, main);
  bindCommonActions(content);
  if (state.screen === "planner") bindPlanner(content);
  if (state.screen === "settings") bindSettings(content);
  if (state.screen === "news") bindNews(content);
  if (state.screen === "history") bindHistory(content);
}

function renderHome() {
  const latest = state.news[0];
  const nextWaste = findWasteEvents(state.waste || {})[0];
  return `<section class="hero-card card">
    <p class="eyebrow">Portal mieszkańców</p><h1>Witaj w Ispinie</h1>
    <p>Autobusy, aktualności, pogoda i codzienne sprawy w jednym miejscu.</p>
    <div class="row"><button class="button secondary" data-screen-go="planner">Zaplanuj podróż</button><button class="button tertiary" data-screen-go="more">Więcej funkcji</button></div>
  </section>
  <section class="card soft" aria-labelledby="homeWeatherTitle"><div class="row between"><h2 id="homeWeatherTitle">Pogoda w Ispinie</h2><button class="link-button" data-screen-go="weather">Szczegóły</button></div>${weatherSummary()}</section>
  <section class="quick-grid" aria-label="Szybkie skróty">
    <button class="quick-card" data-screen-go="planner"><span class="quick-icon" aria-hidden="true">🚌</span><strong>Odjazdy i planer</strong><span>Połączenia MLD i linii gminnej</span></button>
    <button class="quick-card" data-screen-go="waste"><span class="quick-icon" aria-hidden="true">♻</span><strong>Odpady</strong><span>${nextWaste ? `Najbliżej: ${formatDate(nextWaste.date)}` : "Sprawdź harmonogram"}</span></button>
    <button class="quick-card" data-screen-go="news"><span class="quick-icon" aria-hidden="true">▤</span><strong>Aktualności</strong><span>${latest ? escapeHtml(stripHtml(latest.title?.rendered || "Nowe wpisy z Ispiny")) : "Wiadomości z portalu"}</span></button>
    <button class="quick-card" data-screen-go="radio"><span class="quick-icon" aria-hidden="true">♫</span><strong>Radio i TV</strong><span>Otwórz oficjalny odtwarzacz</span></button>
  </section>
  <section class="card"><div class="row between"><div><p class="eyebrow">Karta dnia</p><h2>Spokojnego dnia w Ispinie</h2></div><span aria-hidden="true" style="font-size:28px">☀</span></div><p class="muted">Godziny wschodu i zachodu słońca znajdziesz razem z prognozą pogody.</p><button class="button secondary" data-screen-go="weather">Pogoda i Karta dnia</button></section>
  <p class="hint">Wersja 2.0.0-preview · dane pogodowe i rozkłady wymagają połączenia z internetem podczas pierwszego pobrania.</p>`;
}

function weatherSummary() {
  if (!state.weather?.current) return `<p class="muted">${state.weatherError ? escapeHtml(state.weatherError) : "Pobieram najnowszą prognozę…"}</p><button class="button tertiary" data-action="refresh-weather">Odśwież pogodę</button>`;
  const current = state.weather.current;
  const desc = weatherDescription(Number(current.weather_code));
  const today = state.weather.daily || {};
  return `<div class="row between"><div><div class="weather-value">${Math.round(current.temperature_2m)}°</div><strong>${desc}</strong></div><span aria-hidden="true" style="font-size:36px">${weatherEmoji(Number(current.weather_code))}</span></div>
    <div class="weather-grid"><div class="weather-cell"><small>Odczuwalna</small><strong>${Math.round(current.apparent_temperature)}°</strong></div><div class="weather-cell"><small>Min / max</small><strong>${today.temperature_2m_min?.[0] ?? "–"}° / ${today.temperature_2m_max?.[0] ?? "–"}°</strong></div><div class="weather-cell"><small>Wiatr</small><strong>${Math.round(current.wind_speed_10m || 0)} km/h</strong></div></div>
    <p class="hint">${today.sunrise?.[0] ? `Wschód ${today.sunrise[0].slice(11,16)} · zachód ${today.sunset?.[0]?.slice(11,16) || "–"}` : ""}</p>`;
}

function renderPlanner() {
  const mldDate = state.mld?.fetchedAt ? new Date(state.mld.fetchedAt) : null;
  const feedEnd = String(state.mld?.feedInfo?.endDate || "").replace(/^(\d{4})(\d{2})(\d{2})$/, "$1-$2-$3");
  const expiredMld = Boolean(feedEnd && localDate() > feedEnd);
  const mldStatus = mldDate
    ? `MLD pobrano ${new Intl.DateTimeFormat("pl-PL", { dateStyle: "medium" }).format(mldDate)}${feedEnd ? ` · do ${feedEnd}` : ""}${expiredMld ? " · rozkład wygasł" : ""}`
    : "Rozkład MLD nie został jeszcze pobrany na tym telefonie.";
  const expiredGminna = state.gminna?.validThrough && localDate() > state.gminna.validThrough;
  const gminnaNote = expiredGminna
    ? `<div class="data-note">Dane linii gminnej w aplikacji wygasły ${escapeHtml(state.gminna.validThrough)}. Sprawdź oficjalny rozkład gminy.</div>`
    : `<div class="data-note"><strong>Linia gminna — dane robocze.</strong> Czasy części przystanków są szacunkowe; przed podróżą potwierdź kurs w oficjalnym rozkładzie gminy.</div>`;
  return `<div class="page-head"><p class="eyebrow">Podróż po okolicy</p><h1>Odjazdy i planer</h1><p>Wyszukaj połączenie bezpośrednie lub z jedną przesiadką. Po zatwierdzeniu celu wyniki pojawią się od razu.</p></div>
  <section class="card">
    <div class="row between"><div><h2>Skąd i dokąd?</h2><span class="status"><i class="status-dot ${mldDate && !expiredMld ? "ok" : "warn"}"></i>${escapeHtml(mldStatus)}</span></div><button class="button tertiary" data-action="refresh-schedule" ${isPending("schedule") ? "disabled" : ""}>${isPending("schedule") ? "Pobieram…" : "Pobierz MLD"}</button></div>
    <div class="field" data-stop-field="from"><label class="input-label" for="fromInput">Jadę z</label><div class="field-row"><input id="fromInput" type="text" autocomplete="off" placeholder="Wpisz przystanek" aria-autocomplete="list" aria-expanded="false" aria-controls="fromSuggestions" value="${escapeHtml(state.route.from.value)}" ${state.route.from.accepted ? "readonly" : ""}><button class="button tertiary" data-action="swap-stops" aria-label="Zamień początek i cel">⇅</button></div><div class="suggestions" id="fromSuggestions" role="listbox" aria-label="Podpowiedzi przystanków"></div><div class="review ${state.route.from.selected && !state.route.from.accepted ? "open" : ""}" id="fromReview"></div></div>
    <div class="field" data-stop-field="to"><label class="input-label" for="toInput">Jadę do</label><input id="toInput" type="text" autocomplete="off" placeholder="Wpisz przystanek" aria-autocomplete="list" aria-expanded="false" aria-controls="toSuggestions" value="${escapeHtml(state.route.to.value)}" ${state.route.to.accepted ? "readonly" : ""}><div class="suggestions" id="toSuggestions" role="listbox" aria-label="Podpowiedzi przystanków"></div><div class="review ${state.route.to.selected && !state.route.to.accepted ? "open" : ""}" id="toReview"></div></div>
    <div class="form-grid"><div><label class="input-label" for="travelDate">Dzień podróży</label><input id="travelDate" type="date" value="${localDate()}"></div><div><label class="input-label" for="travelTime">Wyjazd od</label><input id="travelTime" type="time" value="${localTime()}"></div></div>
    <div class="transfer-tools" style="margin-top:12px"><div><label class="input-label" for="transferMinutes">Czas na przesiadkę</label><select id="transferMinutes"><option value="5">5 minut</option><option value="7" selected>7 minut</option><option value="10">10 minut</option><option value="15">15 minut</option><option value="20">20 minut</option></select></div><button class="button" data-action="search-plan">Szukaj połączeń</button></div>
    <p class="hint" id="plannerMessage">${escapeHtml(state.resultMessage || "Wyniki obejmują połączenia z dostępnych rozkładów na wybrany dzień.")}</p>
  </section>
  ${gminnaNote}
  <div class="row" style="margin-top:10px"><button class="link-button" data-action="official-mld">Aktualne rozkłady MLD</button><button class="link-button" data-action="official-gminna">Rozkład gminny i komunikaty</button><button class="link-button" data-action="live">Informacje LIVE / utrudnienia</button></div>
  <section id="planResults" class="results" aria-live="polite">${renderPlanResults()}</section>`;
}

function renderPlanResults() {
  if (!state.results.length) {
    if (state.resultMessage && state.resultMessage !== "Pobieram oficjalny rozkład MLD…") return `<div class="empty">${escapeHtml(state.resultMessage)}</div>`;
    return "";
  }
  return state.results.map((result, index) => `<article class="trip-card">
    <div class="row between"><strong>${result.transfers ? "Z przesiadką" : "Bezpośrednio"}</strong><span class="pill ${result.transfers ? "warn" : "blue"}">${result.transfers ? "1 przesiadka" : "Bez przesiadek"}</span></div>
    <div class="trip-times"><span>${minutesToTime(result.departure)}</span><span class="arrow" aria-hidden="true">→</span><span>${minutesToTime(result.arrival)}</span></div>
    ${result.legs.map((leg, i) => `<div class="trip-leg"><div class="row"><span class="pill blue">${escapeHtml(leg.route)}</span><span class="muted">${escapeHtml(leg.from)} → ${escapeHtml(leg.to)}</span></div><p class="hint">${i === 0 ? "Odjazd" : "Przesiadka"} ${minutesToTime(leg.departure)} · przyjazd ${minutesToTime(leg.arrival)}${leg.headsign ? ` · kierunek ${escapeHtml(leg.headsign)}` : ""}</p>${leg.estimatedLocalTimes ? `<span class="pill warn">Czasy pośrednie orientacyjne</span>` : ""}</div>`).join("")}
    ${result.transferStop ? `<p class="hint">Przesiadka: ${escapeHtml(result.transferStop)} · ${result.transferWait} min</p>` : ""}
    <div class="row"><button class="button tertiary" data-action="read-plan" data-index="${index}">Odczytaj</button><button class="button tertiary" data-action="share-plan" data-index="${index}">Udostępnij</button></div>
  </article>`).join("");
}

function bindPlanner(root) {
  bindStopField("from", root);
  bindStopField("to", root);
  root.addEventListener("click", event => {
    const button = event.target.closest("[data-action]");
    if (!button) return;
    const action = button.dataset.action;
    if (action === "refresh-schedule") requestSchedule();
    else if (action === "search-plan") runSearch();
    else if (action === "swap-stops") {
      [state.route.from, state.route.to] = [state.route.to, state.route.from];
      state.results = [];
      render();
    } else if (action === "read-plan") readPlan(Number(button.dataset.index));
    else if (action === "share-plan") sharePlan(Number(button.dataset.index));
  });
}

function bindStopField(name, root) {
  const input = $(`#${name}Input`, root);
  const list = $(`#${name}Suggestions`, root);
  const review = $(`#${name}Review`, root);
  const item = () => state.route[name];
  input.addEventListener("input", () => {
    const routeField = item();
    routeField.value = input.value;
    routeField.selected = null;
    routeField.accepted = false;
    state.results = [];
    renderSuggestions(name, input, list, review);
  });
  input.addEventListener("keydown", event => {
    if (event.key === "Escape") { closeSuggestions(input, list); return; }
    if (event.key === "ArrowDown") {
      const first = $(".suggestion", list);
      if (first) { event.preventDefault(); first.focus(); }
    }
    if (event.key === "Enter" && item().selected && !item().accepted) {
      event.preventDefault();
      acceptStop(name);
    }
  });
  list.addEventListener("click", event => {
    const suggestion = event.target.closest("[data-stop-index]");
    if (!suggestion) return;
    if (list.dataset.suppressClick === "1") { list.dataset.suppressClick = "0"; event.preventDefault(); return; }
    const stop = state.network.stops[Number(suggestion.dataset.stopIndex)];
    chooseStop(name, stop);
  });
  let pointer = null;
  list.addEventListener("pointerdown", event => {
    const target = event.target.closest("[data-stop-index]");
    if (!target) return;
    pointer = { id: event.pointerId, target, x: event.clientX, y: event.clientY, moved: false };
  });
  list.addEventListener("pointermove", event => {
    if (!pointer || pointer.id !== event.pointerId) return;
    if (Math.abs(event.clientX - pointer.x) > 9 || Math.abs(event.clientY - pointer.y) > 9) pointer.moved = true;
  });
  list.addEventListener("scroll", () => { if (pointer) pointer.moved = true; }, { passive: true });
  list.addEventListener("pointercancel", () => { pointer = null; });
  list.addEventListener("pointerup", event => {
    if (!pointer || pointer.id !== event.pointerId) return;
    const current = pointer;
    pointer = null;
    if (current.moved || !current.target.isConnected || !current.target.contains(event.target) && current.target !== event.target) return;
    const stop = state.network.stops[Number(current.target.dataset.stopIndex)];
    list.dataset.suppressClick = "1";
    chooseStop(name, stop);
    setTimeout(() => { list.dataset.suppressClick = "0"; }, 350);
  });
  syncStopField(name, input, list, review);
}

function renderSuggestions(name, input, list, review) {
  const routeField = state.route[name];
  const matches = getStopSuggestions(state.network.stops, input.value, 12);
  list.innerHTML = matches.map(stop => {
    const index = state.network.stops.indexOf(stop);
    const selected = routeField.selected?.id === stop.id ? "true" : "false";
    return `<button class="suggestion" type="button" role="option" aria-selected="${selected}" data-stop-index="${index}"><span>${escapeHtml(stop.name)}</span><small>${stop.source === "MLD" ? "MLD" : "Gminna"}</small></button>`;
  }).join("");
  list.classList.toggle("open", matches.length > 0 && !routeField.accepted);
  input.setAttribute("aria-expanded", matches.length > 0 && !routeField.accepted ? "true" : "false");
  if (!matches.length && input.value.trim().length >= 2 && !routeField.accepted) {
    list.innerHTML = `<div class="hint" style="padding:10px">Brak pasującego przystanku. Możesz odświeżyć rozkład MLD.</div>`;
    list.classList.add("open");
  }
  renderReview(name, review);
}

function chooseStop(name, stop) {
  if (!stop) return;
  state.route[name] = { value: stop.name, selected: stop, accepted: false };
  const input = $(`#${name}Input`);
  const list = $(`#${name}Suggestions`);
  const review = $(`#${name}Review`);
  input.value = stop.name;
  renderSuggestions(name, input, list, review);
  input.focus({ preventScroll: true });
  announce(`Wybrano wstępnie: ${stop.name}. Naciśnij Akceptuj, aby zatwierdzić.`);
}

function acceptStop(name) {
  const routeField = state.route[name];
  if (!routeField.selected) return;
  routeField.accepted = true;
  const input = $(`#${name}Input`);
  const list = $(`#${name}Suggestions`);
  const review = $(`#${name}Review`);
  input.value = routeField.selected.name;
  input.readOnly = true;
  closeSuggestions(input, list);
  renderReview(name, review);
  if (state.route.from.accepted && state.route.to.accepted) runSearch();
}

function renderReview(name, review) {
  const routeField = state.route[name];
  if (!review) return;
  if (!routeField.selected) { review.innerHTML = ""; review.classList.remove("open"); return; }
  const stopName = escapeHtml(routeField.selected.name);
  review.classList.add("open");
  if (routeField.accepted) {
    review.innerHTML = `<strong>✓ ${stopName}</strong><div class="review-actions"><button class="button tertiary" type="button" data-review-change="${name}">Zmień</button></div>`;
  } else {
    review.innerHTML = `<strong>Sprawdź przystanek: ${stopName}</strong><div class="review-actions"><button class="button" type="button" data-review-accept="${name}">Akceptuj: ${stopName}</button><button class="button tertiary" type="button" data-review-change="${name}">Wybierz ponownie</button></div>`;
  }
  review.querySelector("[data-review-accept]")?.addEventListener("click", () => acceptStop(name));
  review.querySelector("[data-review-change]")?.addEventListener("click", () => changeStop(name));
}

function changeStop(name) {
  const routeField = state.route[name];
  routeField.accepted = false;
  routeField.selected = null;
  const input = $(`#${name}Input`);
  input.readOnly = false;
  input.value = routeField.value;
  input.focus();
  renderSuggestions(name, input, $(`#${name}Suggestions`), $(`#${name}Review`));
}

function syncStopField(name, input, list, review) {
  const routeField = state.route[name];
  input.value = routeField.value;
  input.readOnly = routeField.accepted;
  if (routeField.selected) renderReview(name, review);
  if (!routeField.accepted && routeField.value) renderSuggestions(name, input, list, review);
}
function closeSuggestions(input, list) { list?.classList.remove("open"); input?.setAttribute("aria-expanded", "false"); }

function runSearch() {
  if (!state.route.from.accepted || !state.route.to.accepted) {
    state.resultMessage = "Najpierw wybierz i zaakceptuj przystanek początkowy oraz cel.";
    render();
    return;
  }
  const date = $("#travelDate")?.value || localDate();
  const time = $("#travelTime")?.value || localTime();
  const transferMinutes = Number($("#transferMinutes")?.value || 7);
  state.results = searchConnections(state.network, { from: state.route.from.selected, to: state.route.to.selected, date, time, transferMinutes, limit: 8 });
  state.resultMessage = state.results.length ? `${state.results.length} połączeń na ${formatDate(date)}.` : "Nie znaleziono połączenia w dostępnych rozkładach na ten dzień i godzinę.";
  render();
  if (!state.results.length) announce(state.resultMessage);
  const output = $("#planResults");
  output?.scrollIntoView({ behavior: "smooth", block: "start" });
}

function readPlan(index) {
  const result = state.results[index];
  if (!result || !native()) return;
  const text = result.legs.map(leg => `${leg.route}. Odjazd ${minutesToTime(leg.departure)} z przystanku ${leg.from}. Przyjazd ${minutesToTime(leg.arrival)} do ${leg.to}.`).join(" Przesiadka. ");
  native().speak(text, state.readerRate);
}

async function sharePlan(index) {
  const result = state.results[index];
  if (!result) return;
  const text = result.legs.map(leg => `${leg.route}: ${minutesToTime(leg.departure)} ${leg.from} — ${minutesToTime(leg.arrival)} ${leg.to}`).join("\n");
  if (navigator.share) { try { await navigator.share({ title: "Połączenie — Ispina Lokalnie", text }); } catch (_) { } }
  else if (navigator.clipboard?.writeText) { try { await navigator.clipboard.writeText(text); announce("Połączenie skopiowane."); } catch (_) { announce(text); } }
  else announce(text);
}

function renderNews() {
  const content = state.news.length
    ? state.news.map(post => renderPost(post)).join("")
    : `<div class="empty">${escapeHtml(state.newsError || "Pobieram aktualności z portalu…")}</div>`;
  return `<div class="page-head"><p class="eyebrow">Portal mieszkańców</p><h1>Aktualności</h1><p>Wiadomości i lokalne informacje z Ispiny.</p><button class="button tertiary" data-action="refresh-news">Odśwież wiadomości</button></div>
  <div id="newsList">${content}</div>
  <p class="hint">Wpisy pochodzą z portalu. Połączenie z internetem jest potrzebne do ich odświeżenia.</p>`;
}

function renderPost(post) {
  const id = escapeHtml(post.id);
  const title = sanitizeHtml(post.title?.rendered || "Wpis z Ispiny");
  const excerpt = sanitizeHtml(post.excerpt?.rendered || "");
  const body = sanitizeHtml(post.content?.rendered || "");
  const date = escapeHtml(String(post.date || "").slice(0, 10));
  return `<article class="post-card" data-post-id="${id}"><div class="post-date">${formatDate(date, { year: "numeric", month: "long", day: "numeric" })}</div><h2>${title}</h2><div class="post-excerpt">${excerpt}</div><details><summary class="link-button">Czytaj więcej</summary><div class="post-content">${body}</div></details><div class="row" style="margin-top:8px"><button class="button tertiary" data-action="speak-post" data-id="${id}">Czytaj głosem</button><button class="button tertiary" data-action="open-post" data-url="${escapeHtml(post.link || NEWS_SOURCE)}">Otwórz wpis</button></div><div class="row reader-controls" hidden><button class="link-button" data-action="pause-reader">Pauza</button><button class="link-button" data-action="resume-reader">Wznów</button><button class="link-button" data-action="stop-reader">Stop</button></div></article>`;
}

function bindNews(root) {
  root.addEventListener("click", event => {
    const button = event.target.closest("[data-action]");
    if (!button) return;
    const action = button.dataset.action;
    if (action === "refresh-news") {
      state.news = [];
      state.newsError = "Pobieram aktualności…";
      render();
      requestNews();
    } else if (action === "open-post") openExternal(button.dataset.url);
    else if (action === "speak-post") {
      const card = button.closest(".post-card");
      const post = state.news.find(x => String(x.id) === button.dataset.id);
      if (post && native()) {
        const expandedText = card?.querySelector("details[open] .post-content")?.innerText;
        native().speak(`${stripHtml(post.title?.rendered || "")}. ${expandedText || stripHtml(post.content?.rendered || post.excerpt?.rendered || "")}`, state.readerRate);
        card?.querySelector(".reader-controls")?.removeAttribute("hidden");
      }
    }
  });
}

function renderWaste() {
  const events = findWasteEvents(state.waste || {});
  const expired = state.waste?.validThrough && localDate() > state.waste.validThrough;
  const items = events.slice(0, 6).map(event => `<article class="card waste-event"><div><div class="waste-date">${new Date(`${event.date}T12:00:00`).getDate()}</div><strong>${formatDate(event.date, { month: "long" })}</strong></div><div><strong>${escapeHtml(event.categories.join(" · "))}</strong><p class="hint">Wystaw przed posesję do godz. ${escapeHtml(state.waste.putOutBy || "08:00")}.</p></div></article>`).join("");
  const message = expired
    ? `<div class="data-note">Harmonogram 2026 wygasł ${escapeHtml(state.waste.validThrough)}. Sprawdź nowy harmonogram na stronie gminy.</div>`
    : `<div class="status"><i class="status-dot ok"></i>Harmonogram dla miejscowości Ispina · ${escapeHtml(state.waste?.year || 2026)}</div>`;
  return `<div class="page-head"><p class="eyebrow">Gmina Drwinia</p><h1>Odpady</h1><p>Najbliższe terminy odbioru dla Ispiny.</p></div>
  ${message}${items || `<div class="empty">Brak przyszłych dat w zapisanym harmonogramie.</div>`}
  <section class="card"><h2>Przypomnienia</h2><p class="muted">Włącz powiadomienia dzień przed odbiorem. Aplikacja sprawdza terminy zapisane w harmonogramie na urządzeniu.</p><label class="row"><input id="wasteNotifyToggle" type="checkbox" style="width:24px;min-height:24px" ${localStorage.getItem("il_waste_notifications") === "1" ? "checked" : ""}><span>Przypominaj o odpadach</span></label><div class="row" style="margin-top:10px"><button class="button tertiary" data-action="test-notification">Wyślij testowe powiadomienie</button><button class="link-button" data-action="official-waste">Oficjalny harmonogram PDF</button></div></section>
  <p class="hint">Źródło: ${escapeHtml(state.waste?.sourceName || "Gmina Drwinia")}. Odbiór może ulec zmianie; sprawdź komunikaty gminy.</p>`;
}

function renderWeather() {
  const weather = state.weather;
  if (!weather?.current) return `<div class="page-head"><p class="eyebrow">Ispina · Małopolska</p><h1>Pogoda i Karta dnia</h1></div><div class="card">${weatherSummary()}</div><button class="button" data-action="refresh-weather">Odśwież prognozę</button>`;
  const days = (weather.daily?.time || []).map((date, i) => `<div class="card"><div class="row between"><strong>${i === 0 ? "Dzisiaj" : formatDate(date, { weekday: "long", day: "numeric", month: "long" })}</strong><span>${weatherEmoji(weather.daily.weather_code?.[i])} ${weatherDescription(weather.daily.weather_code?.[i])}</span></div><p class="weather-value">${weather.daily.temperature_2m_min?.[i] ?? "–"}° / ${weather.daily.temperature_2m_max?.[i] ?? "–"}°</p><p class="hint">Wschód ${weather.daily.sunrise?.[i]?.slice(11, 16) || "–"} · zachód ${weather.daily.sunset?.[i]?.slice(11, 16) || "–"}</p></div>`).join("");
  return `<div class="page-head"><p class="eyebrow">Ispina · Małopolska</p><h1>Pogoda i Karta dnia</h1><p>Prognoza bez klucza API, pobierana z Open-Meteo.</p></div><section class="card">${weatherSummary()}</section><h2>Najbliższe dni</h2>${days}<div class="row"><button class="button tertiary" data-action="refresh-weather">Odśwież prognozę</button><button class="link-button" data-action="weather-source">Źródło danych</button></div>`;
}

function renderRadio() {
  const items = [
    ["Radio Jedynka", "Program 1 Polskiego Radia", "https://jedynka.polskieradio.pl/", "Otwórz oficjalny player"],
    ["ESKA", "Odtwarzacz ESKA — wybierz dostępny kanał", "https://player.eska.pl/", "Otwórz ESKA"],
    ["RMF FM", "Radio online RMF FM", "https://www.rmf.fm/radio.html", "Otwórz RMF FM"],
    ["TVP3 Kraków", "Program regionalny i materiały TVP", "https://krakow.tvp.pl/", "Otwórz TVP3 Kraków"],
  ];
  return `<div class="page-head"><p class="eyebrow">Na wolną chwilę</p><h1>Radio i telewizja</h1><p>Uruchamiaj transmisje na oficjalnych stronach nadawców.</p></div><section class="card">${items.map(([title, desc, url, label]) => `<div class="radio-row"><div><h3>${title}</h3><p class="hint">${desc}</p></div><button class="button tertiary" data-action="open-external" data-url="${url}">${label}</button></div>`).join("")}</section><div class="data-note">Odtwarzanie odbywa się w oficjalnym playerze nadawcy. Wymaga internetu i może zużywać pakiet danych.</div>`;
}

function renderServices() {
  return `<div class="page-head"><p class="eyebrow">W okolicy</p><h1>Usługi</h1><p>Przydatne informacje i lokalne kontakty.</p></div><section class="card"><div class="menu-row"><div><h3>Usługi w okolicy</h3><p class="hint">Fachowcy i firmy z regionu</p></div><button class="button tertiary" data-action="open-external" data-url="https://ispinalokalnie.wordpress.com/uslugi-w-okolicy/">Otwórz</button></div><div class="menu-row"><div><h3>Kontakt z portalem</h3><p class="hint">Napisz do autora Ispina Lokalnie</p></div><button class="button tertiary" data-action="open-external" data-url="https://ispinalokalnie.wordpress.com/kontakt/">Otwórz</button></div><div class="menu-row"><div><h3>Komunikacja lokalna</h3><p class="hint">Informacje i pytania do gminy</p></div><button class="button tertiary" data-action="open-external" data-url="https://www.drwinia.pl/mieszkaniec/rozklady-jazdy-busow-i-autobusow">Sprawdź</button></div></section>`;
}

function renderSettings() {
  const seniorChecked = state.senior ? "checked" : "";
  const sound = localStorage.getItem("il_vibration") === "1" ? "checked" : "";
  const news = localStorage.getItem("il_news_notifications") === "1" ? "checked" : "";
  const waste = localStorage.getItem("il_waste_notifications") === "1" ? "checked" : "";
  const rate = [0.8, 1, 1.2].includes(state.readerRate) ? state.readerRate : 1;
  return `<div class="page-head"><p class="eyebrow">Dopasuj aplikację</p><h1>Ustawienia</h1><p>Ułatwienia, powiadomienia i lektor.</p></div><section class="card">
    <label class="menu-row"><span><strong>Tryb Senior</strong><small class="hint" style="display:block">Większy tekst i przyciski</small></span><input id="settingsSenior" type="checkbox" style="width:24px;min-height:24px" ${seniorChecked}></label>
    <label class="menu-row"><span><strong>Wibracja przy dotyku</strong><small class="hint" style="display:block">Krótki sygnał po naciśnięciu</small></span><input id="vibrationToggle" type="checkbox" style="width:24px;min-height:24px" ${sound}></label>
    <label class="menu-row"><span><strong>Nowe aktualności</strong><small class="hint" style="display:block">Sprawdzane w tle co kilka godzin</small></span><input id="newsNotifyToggle" type="checkbox" style="width:24px;min-height:24px" ${news}></label>
    <label class="menu-row"><span><strong>Odpady</strong><small class="hint" style="display:block">Przypomnienie dzień przed odbiorem</small></span><input id="settingsWasteToggle" type="checkbox" style="width:24px;min-height:24px" ${waste}></label>
    <div class="field"><label class="input-label" for="readerRate">Tempo lektora</label><select id="readerRate"><option value="0.8" ${rate === 0.8 ? "selected" : ""}>Wolniej</option><option value="1" ${rate === 1 ? "selected" : ""}>Normalnie</option><option value="1.2" ${rate === 1.2 ? "selected" : ""}>Szybciej</option></select></div>
    <button class="button tertiary" data-action="test-notification">Wyślij testowe powiadomienie</button>
  </section><p class="hint">Powiadomienia wymagają zgody Androida. Wartości wybrane w tej aplikacji są przechowywane na urządzeniu.</p>`;
}

function bindSettings(root) {
  $("#settingsSenior", root)?.addEventListener("change", event => toggleSenior(event.target.checked));
  $("#vibrationToggle", root)?.addEventListener("change", event => {
    localStorage.setItem("il_vibration", event.target.checked ? "1" : "0");
    native()?.setPreference("vibration", event.target.checked ? "1" : "0");
    if (event.target.checked) native()?.vibrate();
  });
  $("#newsNotifyToggle", root)?.addEventListener("change", event => setNotificationPreference("il_news_notifications", "news_notifications", event.target.checked));
  $("#settingsWasteToggle", root)?.addEventListener("change", event => setNotificationPreference("il_waste_notifications", "waste_notifications", event.target.checked));
  $("#readerRate", root)?.addEventListener("change", event => { state.readerRate = Number(event.target.value || 1); localStorage.setItem("il_reader_rate", String(state.readerRate)); });
}

function setNotificationPreference(localKey, nativeKey, enabled) {
  localStorage.setItem(localKey, enabled ? "1" : "0");
  native()?.setPreference(nativeKey, enabled ? "1" : "0");
  if (enabled) native()?.requestNotificationPermission();
}

function renderMore() {
  const items = [
    ["Radio i telewizja", "Radio Jedynka, ESKA, RMF FM, TVP3 Kraków", "radio"],
    ["Historia Ispiny", "Ciekawostki, dawne dokumenty i opowieści", "history"],
    ["Usługi", "Przydatne firmy i kontakty", "services"],
    ["Pogoda i Karta dnia", "Prognoza, wschód i zachód słońca", "weather"],
    ["Ustawienia", "Tryb Senior, lektor i powiadomienia", "settings"],
    ["O aplikacji", "Wersja, licencja i źródła danych", "about"],
  ];
  return `<div class="page-head"><p class="eyebrow">Pozostałe funkcje</p><h1>Więcej</h1><p>Wybierz dodatkową sekcję Ispina Lokalnie.</p></div><section class="card">${items.map(([title, desc, screen]) => `<button class="menu-row full-width" data-screen-go="${screen}" style="border:0;background:transparent;text-align:left"><span><strong>${title}</strong><small class="hint" style="display:block">${desc}</small></span><span aria-hidden="true">›</span></button>`).join("")}</section>`;
}

function renderAbout() {
  const scheduleDate = state.mld?.fetchedAt ? new Date(state.mld.fetchedAt).toISOString() : "Brak pobranego rozkładu MLD";
  return `<div class="page-head"><p class="eyebrow">Informacje</p><h1>O aplikacji</h1><p>Ispina Lokalnie — lokalne informacje i praktyczne narzędzia.</p></div><section class="card"><h2>Ispina Lokalnie 2.0.0-preview</h2><p>Identyfikator wydania F-Droid: <code>com.ispina.mldcheck.fdroid</code></p><p class="hint">Aktualny wariant produkcyjny 1.83.20 pozostaje osobną aplikacją. Ta wersja testowa nie zastępuje jej ani nie zmienia podpisu.</p><p class="hint">Ostatni pobrany rozkład MLD: ${escapeHtml(scheduleDate)}</p></section><section class="card"><h2>Źródła danych</h2><ul class="source-list"><li><button class="link-button" data-action="official-mld">Koleje Małopolskie — MLD</button></li><li><button class="link-button" data-action="official-gminna">Gmina Drwinia — komunikacja</button></li><li><button class="link-button" data-action="official-waste">Gmina Drwinia — odpady 2026</button></li><li><button class="link-button" data-action="weather-source">Open-Meteo — prognoza</button></li><li><button class="link-button" data-action="portal-source">Portal Ispina Lokalnie — aktualności</button></li></ul></section><section class="card"><h2>Prywatność</h2><p>Aplikacja nie ma reklam ani analityki. Przy odświeżaniu wysyła zapytania HTTPS do wybranego źródła danych; ustawienia i zapisany rozkład pozostają na telefonie.</p></section>`;
}

function bindCommonActions(root) {
  root.addEventListener("click", event => {
    const screenButton = event.target.closest("[data-screen-go]");
    if (screenButton) { setScreen(screenButton.dataset.screenGo); return; }
    const button = event.target.closest("[data-action]");
    if (!button) return;
    const action = button.dataset.action;
    if (action === "refresh-weather") { state.weatherError = "Pobieram prognozę…"; render(); requestWeather(); }
    else if (action === "test-notification") native()?.showTestNotification();
    else if (action === "refresh-history") {
      state.historyError = "Pobieram historię…";
      setPending("history", false);
      requestHistory();
    }
    else if (action === "official-waste") openExternal(state.waste?.sourcePdf || "https://www.drwinia.pl/aktualnosci/harmonogram-odbioru-odpadow-z-terenu-gminy-drwinia-2026-rok,news-3175");
    else if (action === "official-mld") openExternal(MLD_SOURCE);
    else if (action === "official-gminna") openExternal(GMINNA_SOURCE);
    else if (action === "live") openExternal("https://kolejemalopolskie.com.pl/pl/utrudnienia");
    else if (action === "weather-source") openExternal("https://open-meteo.com/");
    else if (action === "portal-source") openExternal("https://ispinalokalnie.wordpress.com/");
    else if (action === "open-external") openExternal(button.dataset.url);
    else if (action === "pause-reader") native()?.pauseReading();
    else if (action === "resume-reader") native()?.resumeReading();
    else if (action === "stop-reader") {
      native()?.stopReading();
      button.closest(".post-card")?.querySelector(".reader-controls")?.setAttribute("hidden", "");
    }
  });
  if (state.screen === "waste") {
    $("#wasteNotifyToggle", root)?.addEventListener("change", event => setNotificationPreference("il_waste_notifications", "waste_notifications", event.target.checked));
  }
}

function toggleSenior(enabled) {
  state.senior = Boolean(enabled);
  localStorage.setItem("il_senior", state.senior ? "1" : "0");
  document.body.classList.toggle("senior", state.senior);
  native()?.setPreference("senior", state.senior ? "1" : "0");
  const headerToggle = $("#seniorToggle");
  headerToggle?.setAttribute("aria-label", state.senior ? "Wyłącz tryb Senior" : "Włącz tryb Senior");
  announce(state.senior ? "Włączono tryb Senior." : "Wyłączono tryb Senior.");
}

const HISTORY_LINKS = {
  "historia-ispiny": "https://ispinalokalnie.wordpress.com/historia-ispiny/",
  "ciekawostki-historyczne": "https://ispinalokalnie.wordpress.com/ciekawostki-historyczne/",
};
function renderHistory() {
  const pages = ["historia-ispiny", "ciekawostki-historyczne"].map(slug => state.history.find(page => page.slug === slug)).filter(Boolean);
  const cards = pages.map(page => {
    const slug = escapeHtml(page.slug);
    const title = sanitizeHtml(page.title?.rendered || (page.slug === "historia-ispiny" ? "Historia Ispiny" : "Ciekawostki historyczne"));
    const content = sanitizeHtml(page.content?.rendered || "");
    return `<article class="post-card history-card" data-history-slug="${slug}"><h2>${title}</h2><div class="post-content history-content">${content}</div><div class="row"><button class="button tertiary" data-action="speak-history" data-slug="${slug}">Czytaj głosem</button><button class="button tertiary" data-action="open-external" data-url="${escapeHtml(HISTORY_LINKS[page.slug])}">Otwórz na portalu</button></div><div class="row reader-controls" hidden><button class="link-button" data-action="pause-reader">Pauza</button><button class="link-button" data-action="resume-reader">Wznów</button><button class="link-button" data-action="stop-reader">Stop</button></div></article>`;
  }).join("");
  const fallback = Object.entries(HISTORY_LINKS).map(([slug, url]) => {
    if (pages.some(page => page.slug === slug)) return "";
    const title = slug === "historia-ispiny" ? "Historia Ispiny" : "Ciekawostki historyczne";
    return `<div class="menu-row"><div><h3>${title}</h3><p class="hint">${escapeHtml(state.historyError || "Treść dostępna na portalu Ispina Lokalnie")}</p></div><button class="button tertiary" data-action="open-external" data-url="${url}">Otwórz</button></div>`;
  }).join("");
  return `<div class="page-head"><p class="eyebrow">Nasze miejsce</p><h1>Historia Ispiny</h1><p>Opowieści o dawnych czasach, dokumenty i ciekawostki o miejscowości.</p><button class="button tertiary" data-action="refresh-history" ${isPending("history") ? "disabled" : ""}>${isPending("history") ? "Pobieram…" : "Odśwież historię"}</button></div>
    ${cards || `<section class="card">${fallback}</section>`}
    <section class="card"><h2>Lektor</h2><p class="muted">Teksty można czytać głosem systemowym telefonu. W ustawieniach Androida wybierz polski głos.</p><div class="row"><button class="button tertiary" data-action="pause-reader">Pauza</button><button class="button tertiary" data-action="resume-reader">Wznów</button><button class="button tertiary" data-action="stop-reader">Stop</button></div></section>`;
}

function bindHistory(root) {
  root.addEventListener("click", event => {
    const button = event.target.closest('[data-action="speak-history"]');
    if (!button || !native()) return;
    const card = button.closest(".history-card");
    const text = card?.querySelector(".history-content")?.innerText?.trim();
    if (!text) return;
    native().speak(text, state.readerRate);
    card.querySelector(".reader-controls")?.removeAttribute("hidden");
  });
}

function sanitizeHtml(html) {
  const doc = new DOMParser().parseFromString(String(html), "text/html");
  doc.querySelectorAll("script,style,iframe,object,embed,form,button,input,video,audio,svg,canvas").forEach(el => el.remove());
  const allowed = new Set(["P", "BR", "STRONG", "B", "EM", "I", "UL", "OL", "LI", "BLOCKQUOTE", "H2", "H3", "H4", "A", "SPAN", "DIV"]);
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_ELEMENT);
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  for (const el of nodes) {
    if (!allowed.has(el.tagName)) { el.replaceWith(...el.childNodes); continue; }
    for (const attr of [...el.attributes]) {
      const keepHref = el.tagName === "A" && attr.name === "href" && /^https:\/\//i.test(attr.value);
      if (!keepHref) el.removeAttribute(attr.name);
    }
    if (el.tagName === "A" && el.hasAttribute("href")) { el.setAttribute("rel", "noopener noreferrer"); el.setAttribute("target", "_blank"); }
  }
  return doc.body.innerHTML;
}

function stripHtml(html) {
  const doc = new DOMParser().parseFromString(String(html || ""), "text/html");
  return (doc.body.textContent || "").replace(/\s+/g, " ").trim();
}

function weatherDescription(code) {
  if ([0].includes(code)) return "Bezchmurnie";
  if ([1].includes(code)) return "Prawie bezchmurnie";
  if ([2].includes(code)) return "Częściowe zachmurzenie";
  if ([3].includes(code)) return "Pochmurno";
  if ([45,48].includes(code)) return "Mgła";
  if ([51,53,55,56,57].includes(code)) return "Mżawka";
  if ([61,63,65,80,81,82].includes(code)) return "Deszcz";
  if ([66,67].includes(code)) return "Marznący deszcz";
  if ([71,73,75,77,85,86].includes(code)) return "Śnieg";
  if ([95,96,99].includes(code)) return "Burza";
  return "Brak danych";
}
function weatherEmoji(code) {
  if ([0,1].includes(Number(code))) return "☀️";
  if ([2,3].includes(Number(code))) return "☁️";
  if ([45,48].includes(Number(code))) return "🌫️";
  if ([51,53,55,56,57,61,63,65,80,81,82].includes(Number(code))) return "🌧️";
  if ([71,73,75,77,85,86].includes(Number(code))) return "❄️";
  if ([95,96,99].includes(Number(code))) return "⛈️";
  return "🌤️";
}

$(".bottom-nav")?.addEventListener("click", event => {
  const button = event.target.closest("[data-screen]");
  if (button) setScreen(button.dataset.screen);
});
$("#seniorToggle")?.addEventListener("click", () => toggleSenior(!state.senior));
document.addEventListener("click", event => {
  if (localStorage.getItem("il_vibration") === "1" && event.target.closest("button")) native()?.vibrate();
}, true);

loadData();
