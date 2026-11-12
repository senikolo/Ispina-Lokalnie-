-- Regression: requested stations are catalogued, but never silently bypass ECO128.
local root = "assets/ilradio_k3.koplugin/"
local T = dofile(root .. "tuning.lua")
local stations = dofile(root .. "stations.lua")
local streams = T.rows(root .. "streams.tsv")
local expected = {
    ["Polskie Radio Program 1"] = {category="Popularne", bitrates={192}, codecs={MP3=true, ["AAC-LC"]=true}},
    ["1.FM Brazilian Birds"] = {category="Muzyczne", bitrates={256}, codecs={MP3=true}},
}
for name, spec in pairs(expected) do
    local count, found = 0, false
    for _, station in ipairs(stations) do
        if station[1] == name then
            count = count + 1
            assert(station[2] == spec.category)
            found = station
        end
    end
    assert(count == 1, "Expected exactly one category entry for " .. name)
    local variants = T.variants(found, streams, {})
    assert(#variants >= 2, "Missing alternate streams: " .. name)
    local codecs = {}
    for _, stream in ipairs(variants) do
        assert(T.url(stream.url), "Invalid URL for " .. name)
        assert(not T.hard_allowed(stream), "Do not silently bypass ECO128 for " .. name)
        assert(not T.allowed({mode="high", limit=0}, stream))
        local allowed_bitrate = false
        for _, kbps in ipairs(spec.bitrates) do
            if stream.kbps == kbps then allowed_bitrate = true end
        end
        assert(allowed_bitrate, name .. " has an unverified or mislabelled bitrate")
        assert(spec.codecs[stream.codec], name .. " codec not expected")
        codecs[stream.codec] = true
    end
    for codec in pairs(spec.codecs) do assert(codecs[codec], "Missing codec " .. codec .. " on " .. name) end
end
print("PASS: PR1 192 and Brazilian Birds 256, catalogue mirrors, immutable global ECO128")
