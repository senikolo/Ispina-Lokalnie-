const MINUTES_PER_DAY = 1440;
const GMINNA_VALID_THROUGH = "2026-12-31";

export function normalizeName(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLocaleLowerCase("pl-PL")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

export function timeToMinutes(value) {
  const match = /^(\d{1,3}):(\d{2})(?::(\d{2}))?$/.exec(String(value || ""));
  if (!match) return NaN;
  return Number(match[1]) * 60 + Number(match[2]) + (Number(match[3] || 0) >= 30 ? 1 : 0);
}

export function minutesToTime(value) {
  const minute = Math.max(0, Math.floor(value));
  const day = Math.floor(minute / MINUTES_PER_DAY);
  const clock = `${String(Math.floor((minute % MINUTES_PER_DAY) / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
  return day ? `${clock} (+${day})` : clock;
}

function canonicalTransferKey(name) {
  const key = normalizeName(name);
  const aliases = [
    [/^ispina iv$/, "ispina lesniczowka"],
    [/^ispina iii$/, "ispina przedszkole"],
    [/^ispina ii$/, "ispina most"],
    [/^krakow czyzyny$/, "krakow czyzyny dworzec"],
    [/^mikluszowice park ride$/, "mikluszowice park&ride"],
  ];
  for (const [pattern, replacement] of aliases) if (pattern.test(key)) return replacement;
  return key;
}

function parseGminna(gminna, validThrough) {
  const stops = new Map();
  for (const stop of gminna?.stops || []) {
    stops.set(String(stop.id), {
      id: `gminna:${stop.id}`,
      source: "GMINNA",
      name: stop.name || String(stop.id),
      key: canonicalTransferKey(stop.name || String(stop.id)),
    });
  }
  const trips = [];
  const maxDate = validThrough || gminna?.validThrough || GMINNA_VALID_THROUGH;
  for (const group of gminna?.serviceGroups || []) {
    if (!Array.isArray(group.route) || !Array.isArray(group.offsets)) continue;
    for (let d = 0; d < (group.departures || []).length; d++) {
      const start = timeToMinutes(group.departures[d]);
      if (!Number.isFinite(start)) continue;
      const stopTimes = group.route.map((stopId, index) => {
        const offset = Number(group.offsets[index] || 0);
        const minute = start + offset;
        const stop = stops.get(String(stopId));
        return {
          stopId: stop?.id || `gminna:${stopId}`,
          arrivalMinute: minute,
          departureMinute: minute,
          arrival: minutesToTime(minute).split(" ")[0] + ":00",
          departure: minutesToTime(minute).split(" ")[0] + ":00",
          seq: index + 1,
          stopKey: stop?.key || canonicalTransferKey(String(stopId)),
          stopName: stop?.name || String(stopId),
        };
      });
      trips.push({
        tripId: `gminna:${group.direction || "route"}:${group.dayType || "any"}:${d}:${group.departures[d]}`,
        route: "Linia gminna",
        serviceId: `gminna:${group.dayType || "any"}`,
        headsign: group.direction === "KRAKOW" ? "Kraków, Czyżyny Dworzec" : "Mikluszowice Park&Ride",
        source: "GMINNA",
        dayType: String(group.dayType || "").toUpperCase(),
        validFrom: gminna.validFrom || "2026-01-01",
        validThrough: maxDate,
        estimatedLocalTimes: Boolean(group.timingQuality?.interpolatedLocalStops),
        stopTimes,
      });
    }
  }
  return { trips, stops };
}

export function buildNetwork(mld, gminna, options = {}) {
  const stopMap = new Map();
  const trips = [];
  const validThrough = options.gminnaValidThrough || gminna?.validThrough || GMINNA_VALID_THROUGH;

  for (const [id, record] of Object.entries(mld?.stops || {})) {
    const name = typeof record === "string" ? record : record?.name || id;
    stopMap.set(`mld:${id}`, { id: `mld:${id}`, source: "MLD", name, key: canonicalTransferKey(name) });
  }
  for (const trip of mld?.trips || []) {
    const stopTimes = (trip.stopTimes || []).map((stopTime, index) => {
      const id = `mld:${stopTime.stopId}`;
      const stop = stopMap.get(id);
      return {
        stopId: id,
        arrivalMinute: timeToMinutes(stopTime.arrival),
        departureMinute: timeToMinutes(stopTime.departure || stopTime.arrival),
        arrival: stopTime.arrival,
        departure: stopTime.departure || stopTime.arrival,
        seq: Number(stopTime.seq || index + 1),
        stopKey: stop?.key || canonicalTransferKey(stop?.name || stopTime.stopId),
        stopName: stop?.name || String(stopTime.stopId),
      };
    }).filter(x => Number.isFinite(x.arrivalMinute) && Number.isFinite(x.departureMinute));
    trips.push({
      tripId: String(trip.tripId), route: String(trip.route || "MLD"),
      serviceId: String(trip.serviceId || ""), headsign: String(trip.headsign || ""),
      source: "MLD", stopTimes,
    });
  }

  const local = parseGminna(gminna, validThrough);
  for (const [id, stop] of local.stops) stopMap.set(id, stop);
  trips.push(...local.trips);
  return {
    trips,
    stops: [...stopMap.values()],
    mldCalendar: mld?.calendar || {},
    mldCalendarDates: mld?.calendarDates || [],
    mldFeedInfo: mld?.feedInfo || {},
    fetchedAt: Number(mld?.fetchedAt || 0),
    gminnaValidFrom: gminna?.validFrom || null,
    gminnaValidThrough: validThrough,
  };
}

function compactDate(isoDate) { return String(isoDate || "").replaceAll("-", ""); }

export function mldServiceRunsOn(serviceId, isoDate, calendar, calendarDates) {
  const date = compactDate(isoDate);
  const exception = (calendarDates || []).find(x => x.serviceId === serviceId && x.date === date);
  if (exception) return String(exception.exceptionType) === "1";
  const service = calendar?.[serviceId];
  if (!service) return false;
  const start = String(service.startDate || service.start_date || "");
  const end = String(service.endDate || service.end_date || "");
  if (date < start || date > end) return false;
  const weekday = new Date(`${isoDate}T12:00:00`).getDay();
  const keys = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"];
  return String(service[keys[weekday]] || "0") === "1";
}

export function tripRunsOn(trip, isoDate, network) {
  if (trip.source === "GMINNA") {
    if (isoDate < trip.validFrom || isoDate > trip.validThrough) return false;
    const day = new Date(`${isoDate}T12:00:00`).getDay();
    if (trip.dayType === "WEEKDAY") return day >= 1 && day <= 5;
    if (trip.dayType === "SATURDAY") return day === 6;
    if (trip.dayType === "SUNDAY" || trip.dayType === "HOLIDAY") return day === 0;
    return false;
  }
  return mldServiceRunsOn(trip.serviceId, isoDate, network.mldCalendar, network.mldCalendarDates);
}

export function getStopSuggestions(stops, query, limit = 12) {
  const q = normalizeName(query);
  if (q.length < 2) return [];
  const unique = new Map();
  for (const stop of stops || []) {
    const key = normalizeName(stop.name);
    if (!key.includes(q)) continue;
    const prior = unique.get(key);
    if (!prior || (prior.source !== "MLD" && stop.source === "MLD")) unique.set(key, stop);
  }
  return [...unique.values()].sort((a, b) => {
    const ap = normalizeName(a.name).startsWith(q) ? 0 : 1;
    const bp = normalizeName(b.name).startsWith(q) ? 0 : 1;
    return ap - bp || a.name.localeCompare(b.name, "pl");
  }).slice(0, limit);
}

function parseClockInput(value) {
  const parsed = timeToMinutes(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function searchConnections(network, input) {
  const date = input.date;
  const fromKey = input.from?.key || canonicalTransferKey(input.from?.name || "");
  const toKey = input.to?.key || canonicalTransferKey(input.to?.name || "");
  const earliest = parseClockInput(input.time);
  const transferMinutes = Math.max(3, Math.min(60, Number(input.transferMinutes || 7)));
  if (!date || !fromKey || !toKey || fromKey === toKey) return [];

  const activeTrips = network.trips.filter(trip => tripRunsOn(trip, date, network));
  const byKey = new Map();
  for (const trip of activeTrips) {
    trip.stopTimes.forEach((stop, index) => {
      if (!byKey.has(stop.stopKey)) byKey.set(stop.stopKey, []);
      byKey.get(stop.stopKey).push({ trip, index, stop });
    });
  }

  const found = new Map();
  for (const trip of activeTrips) {
    const origins = [];
    const destinations = [];
    trip.stopTimes.forEach((stop, index) => {
      if (stop.stopKey === fromKey) origins.push(index);
      if (stop.stopKey === toKey) destinations.push(index);
    });
    for (const i of origins) {
      const departure = trip.stopTimes[i].departureMinute;
      if (departure < earliest) continue;
      for (const j of destinations) {
        if (j <= i) continue;
        const arrival = trip.stopTimes[j].arrivalMinute;
        addResult(found, {
          departure, arrival, transfers: 0,
          legs: [makeLeg(trip, trip.stopTimes[i], trip.stopTimes[j])],
        });
      }
      for (let j = i + 1; j < trip.stopTimes.length; j++) {
        const transferStop = trip.stopTimes[j];
        if (transferStop.arrivalMinute < departure || transferStop.arrivalMinute - departure > 240) continue;
        const candidates = byKey.get(transferStop.stopKey) || [];
        for (const next of candidates) {
          if (next.trip.tripId === trip.tripId) continue;
          const secondDeparture = next.stop.departureMinute;
          const wait = secondDeparture - transferStop.arrivalMinute;
          if (wait < transferMinutes || wait > 120) continue;
          for (let k = next.index + 1; k < next.trip.stopTimes.length; k++) {
            const destination = next.trip.stopTimes[k];
            if (destination.stopKey !== toKey) continue;
            addResult(found, {
              departure, arrival: destination.arrivalMinute, transfers: 1,
              legs: [
                makeLeg(trip, trip.stopTimes[i], transferStop),
                makeLeg(next.trip, next.stop, destination),
              ],
              transferStop: transferStop.stopName,
              transferWait: wait,
            });
          }
        }
      }
    }
  }
  return [...found.values()].sort((a, b) => a.departure - b.departure || a.arrival - b.arrival)
    .slice(0, Math.max(1, Math.min(20, Number(input.limit || 10))));
}

function makeLeg(trip, from, to) {
  return {
    route: trip.route,
    source: trip.source,
    headsign: trip.headsign,
    from: from.stopName,
    to: to.stopName,
    departure: from.departureMinute,
    arrival: to.arrivalMinute,
    estimatedLocalTimes: Boolean(trip.estimatedLocalTimes),
  };
}

function addResult(results, item) {
  if (item.arrival < item.departure) return;
  const key = item.legs.map(leg => `${leg.route}:${leg.departure}:${leg.arrival}`).join("|");
  if (!results.has(key)) results.set(key, item);
}

export function findWasteEvents(schedule, fromDate = new Date()) {
  const start = new Date(fromDate);
  start.setHours(0, 0, 0, 0);
  return (schedule?.events || []).map(event => ({
    date: event.date,
    dateObject: new Date(`${event.date}T00:00:00`),
    categories: event.categories || [],
  })).filter(event => event.dateObject >= start)
    .sort((a, b) => a.dateObject - b.dateObject);
}

export { canonicalTransferKey, GMINNA_VALID_THROUGH };
