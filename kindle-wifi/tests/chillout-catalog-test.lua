local root = "assets/ilradio_k3.koplugin/"
local tuning = dofile(root .. "tuning.lua")
local stations = dofile(root .. "stations.lua")
local catalog = tuning.rows(root .. "streams.tsv")
local expected = {
    ["Chillizet"] = { url = "https://ch.cdn.eurozet.pl/chi-net.mp3", kbps = 128 },
    ["FluxFM Chillout"] = { url = "https://streams.fluxfm.de/chillout/mp3-128/streams.fluxfm.de/", kbps = 128 },
}
local seen = {}
for _, station in ipairs(stations) do
    local name = station[1]
    if expected[name] then
        assert(station[2] == "Muzyczne", name .. " must be musical")
        assert(not seen[name], "Duplicate station " .. name)
        seen[name] = true
        local variants = tuning.variants(station, catalog, {})
        assert(#variants == 1, "Expected exactly one verified direct stream: " .. name)
        local stream = variants[1]
        assert(stream.url == expected[name].url)
        assert(stream.codec == "MP3" and stream.kbps == expected[name].kbps)
        assert(tuning.allowed({mode="auto",limit=128}, stream))
        assert(not tuning.allowed({mode="auto",limit=64}, stream))
    end
end
for name in pairs(expected) do assert(seen[name], "Missing station: " .. name) end
-- Preserve Radio Krakow's user-confirmed 96 kbps streams.
local krakow = false
for _, row in ipairs(catalog) do
    if row[1] == "Radio Kraków" and tonumber(row[4]) == 96 then krakow = true end
    if row[1] == "Polskie Radio Jedynka" then
        assert(tonumber(row[4]) and tonumber(row[4]) <= 128,
               "Do not import unsupported 192 kbps Jedynka source")
    end
end
assert(krakow, "User-confirmed Radio Krakow 96 kbps source must remain")
print("PASS: Chillizet and FluxFM Chillout verified catalog; Krakow ECO source retained")
