-- Fixture corresponding to the locally measured 30-station MP3 selection (12 Nov 2026).
local root = "assets/ilradio_k3.koplugin/"
local tuning = dofile(root .. "tuning.lua")
local stations = dofile(root .. "stations.lua")
local rows = tuning.rows(root .. "streams.tsv")
local expected = {
    ["Radio Pasja – Chillout"] = {64, "http://91.232.4.33:8108/"},
    ["Radio Pasja – Relaks"] = {64, "http://91.232.4.33:8102/"},
    ["Music Lake"] = {64, "http://nap.casthost.net:8626/stream"},
    ["SmoothLounge.com"] = {32, "https://smoothjazz.cdnstream1.com/2586_32.mp3"},
    ["LuxuriaMusic"] = {32, "http://ice10.securenetsystems.net/LUXOMP3"},
    ["Sleepbot"] = {56, "http://primarystream.sleepbot.com:8218/seb"},
    ["24/7 Nature Radio"] = {64, "https://ec3.yesstreaming.net:3545/stream"},
    ["Pink Noise Radio"] = {64, "http://uk1.internet-radio.com:8004/"},
    ["Sleepscapes – Rain"] = {64, "https://stream.willstare.com:8850/;"},
    ["24/7 Lofi Radio"] = {64, "https://ec3.yesstreaming.net:3755/stream"},
    ["Aloha Joe's Tiki Radio"] = {24, "http://s2.voscast.com:9120/stream"},
    ["SmoothJazz.com – 32k MP3"] = {32, "https://smoothjazz.cdnstream1.com/2585_32.mp3"},
    ["Big Blue Swing – 32k"] = {32, "http://radio.bigblueswing.com:8004/;32bit.mp3"},
    ["WZUM Pittsburgh Jazz Channel"] = {64, "https://pubmusic.streamguys1.com/wzum-mp3"},
    ["WCLK 91.9 Atlanta"] = {64, "http://17803.live.streamtheworld.com/WCLKFM_SC"},
    ["24/7 Cool Radio"] = {64, "https://ec3.yesstreaming.net:3665/stream"},
    ["Jazz Radio International"] = {64, "https://ec3.yesstreaming.net:1815/stream"},
    ["24/7 Blues Radio"] = {64, "https://ec3.yesstreaming.net:3685/stream"},
    ["RFI Musique"] = {64, "http://rfimusiquemonde64k.ice.infomaniak.ch/rfimusiquemonde-64k.mp3"},
    ["RNZ Concert"] = {64, "http://radionz-ice.streamguys.com/concert.mp3"},
    ["24/7 Classical Radio"] = {64, "https://ec3.yesstreaming.net:3615/stream"},
    ["SR 2 KulturRadio"] = {64, "http://liveradio.sr.de/sr/sr2/mp3/64/stream.mp3?aggregator=custom1"},
    ["rbbKultur (Kulturradio)"] = {64, "http://dispatcher.rndfnk.com/rbb/rbbkultur/live/mp3/low"},
    ["Radio Dismuke"] = {24, "http://stream2.early1900s.org:8000/"},
    ["Radio Kampus"] = {64, "http://193.0.98.66:8002/;"},
    ["TOK FM"] = {64, "http://stream30.radiostream.pl/tuba10-2.mp3"},
    ["France Info – lofi"] = {32, "http://direct.franceinfo.fr/live/franceinfo-lofi.mp3"},
    ["France Culture"] = {32, "http://direct.franceculture.fr/live/franceculture-lofi.mp3"},
    ["Fritz (rbb)"] = {64, "http://fritz.de/livemp3_s"},
    ["RFI English"] = {64, "http://live02.rfi.fr/rfienanglais-64.mp3"},
}
local found, curated, counts = {}, 0, {}
for _, entry in ipairs(stations) do
    local name = entry[1]
    assert(not found[name], "Station listed twice in stations.lua: " .. name)
    found[name] = entry
    if expected[name] then
        local category = entry[4] or entry[2]
        counts[category] = (counts[category] or 0) + 1
        curated = curated + 1
        local candidates = tuning.variants(entry, rows, {})
        local matches = 0
        for _, item in ipairs(candidates) do
            if item.url == expected[name][2] then
                assert(item.codec == "MP3" and item.kbps == expected[name][1],
                    "Changed format or bitrate in " .. name)
                assert(tuning.url(item.url) and tuning.hard_allowed(item),
                    "Unsafe URL or bitrate in " .. name)
                matches = matches + 1
            end
        end
        assert(matches == 1, "Expected one measured direct MP3 source for " .. name)
    end
end
assert(#stations == 63, "Must keep 35 original stations and add 28 without duplication")
assert(curated == 30, "Exactly 30 curated stations must appear once")
for category, amount in pairs({["Relaks i natura"]=11, ["Jazz i muzyka świata"]=8,
    ["Klasyka i nostalgia"]=5, ["Polska i audycje"]=6}) do
    assert(counts[category] == amount, category .. " expected " .. amount)
end
for name in pairs(expected) do assert(found[name], "Missing selected station " .. name) end
local mp3_total = 0
for _, row in ipairs(rows) do
    if expected[row[1]] and row[3] == "MP3" and tonumber(row[4]) <= 64 then
        mp3_total = mp3_total + 1
    end
end
assert(mp3_total == 30, "30 selected MP3 URLs must be present and deduplicated")
print("PASS: 30 unique low-MP3 sources (24-64 kbps), category counts, no original station lost")
