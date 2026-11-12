local dir = arg[1]
local T = dofile(dir .. "/tuning.lua")
local eco = {mode = "eco", codec = "auto"}
local ultra = {mode = "ultra", codec = "auto"}
local list = {{codec = "HE-AAC v2", kbps = 24}, {codec = "HE-AAC", kbps = 32},
    {codec = "AAC-LC", kbps = 48}, {codec = "MP3", kbps = 64}, {codec = "AAC-LC", kbps = 64},
    {codec = "MP3", kbps = 128}}
assert(T.rank(eco, list)[1].kbps == 48)
assert(T.rank(ultra, list)[1].kbps == 24)
assert(T.rank({mode = "eco", minimum = true}, list)[1].kbps == 24)
assert(T.rank({mode = "eco", limit = 32}, list)[1].kbps == 32)
assert(T.rank({mode = "high"}, list)[1].kbps == 128)
assert(T.rank({mode = "eco", codec = "mp3"}, list)[1].codec == "MP3")
assert(T.allowed(eco, {codec = "MP3", kbps = 64}))
assert(not T.allowed(eco, {codec = "AAC-LC", kbps = 64}))
assert(not T.allowed({mode = "eco", limit = 32}, {codec = "MP3", kbps = 64}))
assert(not T.allowed({mode = "auto"}, {}))
-- Never accept a stream whose real bitrate is unknown, even with limit = 0.
assert(not T.allowed({mode = "auto", limit = 0}, {}))
assert(not T.allowed({mode = "ultra", limit = 0}, {codec = "AAC", kbps = 48}))
assert(not T.url("http://user:pass@localhost/a") and not T.url("file:///etc/passwd"))
assert(T.url("https://radio.example/live?x=2&y=3"))

for _, sample in ipairs({
    {"test-lc48.aac", 48, "AAC", 44100, 2}, {"test-he32.aac", 32, "AAC", 22050, 2},
    {"test-hev224.aac", 24, "AAC", 22050, 1}, {"test-mp364.mp3", 64, "MP3"},
    {"test-real-he32.aac", 32, "AAC", 22050, 2},
    {"test-real-hev224.aac", 24, "AAC", 22050, 1}
}) do
    local file = assert(io.open(dir .. "/" .. sample[1], "rb"))
    local bytes = file:read(16384)
    file:close()
    local codec, cap, rate, _, core, channels = T.sample(bytes, sample[2])
    assert(codec == sample[3] and cap == sample[2] and math.abs(rate - cap) < 1.2, sample[1])
    assert(core == sample[4] and channels == sample[5], sample[1] .. " core")
end
assert(not T.sample("<html>Not a radio stream</html>", 24))
local file = assert(io.open(dir .. "/test-lc48.aac", "rb"))
local bytes = file:read(16384)
file:close()
local codec, cap = T.sample(bytes, 24)
assert(codec == "AAC" and cap > 24)
print("PASS policy caps, priority, AAC and MP3 frames, misleading bitrate and URL checks")
