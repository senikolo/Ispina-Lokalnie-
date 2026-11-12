local root = "assets/ilradio_k3.koplugin/"
local tuning = dofile(root .. "tuning.lua")
local stations = dofile(root .. "stations.lua")
local catalog = tuning.rows(root .. "streams.tsv")
local specs = {
    ["SomaFM Groove Salad"]={"groovesalad","ice2"},
    ["SomaFM Drone Zone"]={"dronezone","ice6"},
    ["SomaFM Deep Space One"]={"deepspaceone","ice6"},
    ["SomaFM Lush"]={"lush","ice2"},
}
local seen = {}
for _, station in ipairs(stations) do
    local spec = specs[station[1]]
    if spec then
        assert(not seen[station[1]], "Duplicate station")
        seen[station[1]] = true
        assert(station[2] == "Muzyczne")
        local variants = tuning.variants(station, catalog, {})
        assert(#variants == 4, "Expected 2 AAC + 2 MP3 mirrors for " .. station[1])
        local expected = {
            "http://ice5.somafm.com/" .. spec[1] .. "-64-aac",
            "http://" .. spec[2] .. ".somafm.com/" .. spec[1] .. "-64-aac",
            "http://ice5.somafm.com/" .. spec[1] .. "-128-mp3",
            "http://" .. spec[2] .. ".somafm.com/" .. spec[1] .. "-128-mp3",
        }
        for i, url in ipairs(expected) do
            local v = variants[i]
            assert(v.url == url, station[1] .. ": wrong direct stream or mirror")
            assert(v.kbps == (i <= 2 and 64 or 128))
            assert(v.codec == (i <= 2 and "HE-AAC" or "MP3"))
            assert(tuning.hard_allowed(v), "Must not exceed 128 kbps")
        end
        local ranked = tuning.rank({mode="auto", minimum=true, limit=128,codec="auto"},variants,nil)
        assert(ranked[1].kbps == 64 and ranked[#ranked].kbps == 128,
            "Conserving data must rank 64 before 128")
        assert(tuning.hard_allowed(ranked[#ranked]))
    end
end
for name in pairs(specs) do assert(seen[name], "Missing SomaFM station: " .. name) end
local krakow = 0
for _, row in ipairs(catalog) do
    if row[1] == "Radio Kraków" and tonumber(row[4]) == 96 then krakow = krakow + 1 end
    assert(row[1] ~= "Open FM Chillout", "Unverified Open FM stream must not be included")
end
assert(krakow == 2, "Both Radio Krakow 96 kbps regional alternatives must remain")
print("PASS: SomaFM four channels, 16 direct low-bitrate routes, alternate hosts, ECO128, Krakow untouched")
