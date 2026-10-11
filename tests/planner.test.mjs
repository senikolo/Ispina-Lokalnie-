import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { buildNetwork, getStopSuggestions, searchConnections, tripRunsOn, normalizeName, findWasteEvents } from "../app/src/main/assets/js/planner.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const local = JSON.parse(fs.readFileSync(path.join(here, "../app/src/main/assets/data/gminna-2026-09-25.json"), "utf8"));
const pass = [];
function test(name, fn) { fn(); pass.push(name); }

const smallMld = {
  stops: { a: { name: "Ispina Most" }, b: { name: "Drwinia" }, c: { name: "Kraków, Czyżyny Dworzec" } },
  calendar: { everyday: { monday: "1", tuesday: "1", wednesday: "1", thursday: "1", friday: "1", saturday: "1", sunday: "1", startDate: "20261001", endDate: "20261231" } },
  calendarDates: [],
  trips: [
    { tripId: "direct", route: "A7", serviceId: "everyday", headsign: "Kraków", stopTimes: [{ stopId: "a", arrival: "06:00:00", departure: "06:00:00", seq: 1 }, { stopId: "c", arrival: "07:00:00", departure: "07:00:00", seq: 2 }] },
    { tripId: "first", route: "A65", serviceId: "everyday", headsign: "Drwinia", stopTimes: [{ stopId: "a", arrival: "06:10:00", departure: "06:10:00", seq: 1 }, { stopId: "b", arrival: "06:30:00", departure: "06:30:00", seq: 2 }] },
    { tripId: "second", route: "A50", serviceId: "everyday", headsign: "Kraków", stopTimes: [{ stopId: "b", arrival: "06:38:00", departure: "06:38:00", seq: 1 }, { stopId: "c", arrival: "07:18:00", departure: "07:18:00", seq: 2 }] },
  ],
};

test("Polish names normalize for accessible stop search", () => {
  assert.equal(normalizeName("Kraków, Czyżyny Dworzec"), "krakow czyzyny dworzec");
});

test("planner finds a direct departure and sorts it before a later transfer", () => {
  const network = buildNetwork(smallMld, { stops: [], serviceGroups: [] });
  const from = network.stops.find(x => x.name === "Ispina Most");
  const to = network.stops.find(x => x.name === "Kraków, Czyżyny Dworzec");
  const result = searchConnections(network, { from, to, date: "2026-10-12", time: "05:00", limit: 8 });
  assert.equal(result.length, 2);
  assert.equal(result[0].transfers, 0);
  assert.equal(result[0].legs[0].route, "A7");
  assert.equal(result[1].transfers, 1);
  assert.equal(result[1].legs.map(x => x.route).join("/"), "A65/A50");
});

test("an 8 minute connection is rejected when the user asks for 10 minutes", () => {
  const network = buildNetwork(smallMld, { stops: [], serviceGroups: [] });
  const from = network.stops.find(x => x.name === "Ispina Most");
  const to = network.stops.find(x => x.name === "Kraków, Czyżyny Dworzec");
  const result = searchConnections(network, { from, to, date: "2026-10-12", time: "06:05", transferMinutes: 10 });
  assert.equal(result.length, 0);
});

test("calendar exceptions override the weekday pattern", () => {
  const calendar = { weekday: { monday: "1", tuesday: "1", wednesday: "1", thursday: "1", friday: "1", saturday: "0", sunday: "0", startDate: "20261001", endDate: "20261231" } };
  assert.equal(tripRunsOn({ source: "MLD", serviceId: "weekday" }, "2026-10-10", { mldCalendar: calendar, mldCalendarDates: [{ serviceId: "weekday", date: "20261010", exceptionType: "1" }] }), true);
  assert.equal(tripRunsOn({ source: "MLD", serviceId: "weekday" }, "2026-10-12", { mldCalendar: calendar, mldCalendarDates: [{ serviceId: "weekday", date: "20261012", exceptionType: "2" }] }), false);
});

test("expired municipal service is excluded", () => {
  const trip = { source: "GMINNA", dayType: "WEEKDAY", validFrom: "2026-09-22", validThrough: "2026-12-31" };
  assert.equal(tripRunsOn(trip, "2027-01-04", {}), false);
});

test("suggestions return canonical stop names without duplicates", () => {
  const network = buildNetwork(smallMld, { stops: [], serviceGroups: [] });
  const suggestions = getStopSuggestions(network.stops, "czyzyny");
  assert.deepEqual(suggestions.map(x => x.name), ["Kraków, Czyżyny Dworzec"]);
});

test("bundled municipal data builds searchable Ispina stops and marks estimated times", () => {
  const network = buildNetwork(null, local);
  assert(network.trips.length > 30);
  assert(network.stops.some(x => x.name === "Ispina Most"));
  assert(network.stops.some(x => x.name === "Ispina Dom Ludowy"));
  const suggestions = getStopSuggestions(network.stops, "ispina");
  assert(suggestions.length > 0);
  const from = network.stops.find(x => x.name === "Ispina Most");
  const to = network.stops.find(x => x.name === "Kraków, Czyżyny Dworzec");
  const trips = searchConnections(network, { from, to, date: "2026-10-12", time: "03:30", limit: 1 });
  assert.equal(trips.length, 1);
  assert.equal(trips[0].legs[0].estimatedLocalTimes, true);
});

test("waste events are sorted and preserve source categories", () => {
  const waste = JSON.parse(fs.readFileSync(path.join(here, "../app/src/main/assets/data/waste-2026.json"), "utf8"));
  const events = findWasteEvents(waste, new Date("2026-10-11T12:00:00"));
  assert.deepEqual(events.map(x => x.date), ["2026-10-16", "2026-11-17", "2026-12-17"]);
  assert(events[0].categories.includes("Gabaryty i opony"));
});

console.log(`${pass.length}/${pass.length} testów OK`);
for (const name of pass) console.log(`✓ ${name}`);
