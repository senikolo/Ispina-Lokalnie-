local T = {}
T.modes = {{"ultra", "Ultraoszczędny"}, {"eco", "Oszczędny"},
    {"auto", "Automatyczny"}, {"high", "Wysoka jakość"}}
T.hard_limit = 128
T.limits = {24, 32, 48, 64, 96, 128}
T.codecs = {{"auto", "Według trybu"}, {"aac", "AAC jako pierwszy"}, {"mp3", "MP3 jako pierwszy"}}
T.buffers = {64, 128, 256}

function T.url(value)
    return type(value) == "string" and #value <= 1024
        and value:match("^https?://[%w%.%-]+[:%d]*/?[^%s%c]*$") ~= nil
        and value:match("^https?://([^/?#]+)") ~= nil
        and not value:match("^https?://[^/?#]*@")
end

function T.rows(path)
    local rows = {}
    local f = io.open(path, "rb")
    if not f then return rows end
    local contents = f:read(65537) or ""
    f:close()
    if #contents > 65536 then return rows end
    for line in contents:gmatch("[^\r\n]+") do
        local row = {}
        for column in (line .. "\t"):gmatch("(.-)\t") do row[#row + 1] = column end
        if row[1]:sub(1, 1) ~= "#" then rows[#rows + 1] = row end
    end
    return rows
end

function T.variants(station, catalog, custom)
    local list, seen = {}, {}
    local function add(row)
        if T.url(row.url) and not seen[row.url] then
            list[#list + 1] = row
            seen[row.url] = true
        end
    end
    for _, r in ipairs(custom) do
        if r[1] == station[1] then add({url = r[2], source = "Adres użytkownika", custom = true}) end
    end
    for _, r in ipairs(catalog) do
        if r[1] == station[1] then
            add({url = r[2], codec = r[3] ~= "" and r[3] or nil, catalog_label = r[3] ~= "" and r[3] or nil, kbps = tonumber(r[4]), source = r[5],
                verified = r[6], final = r[7], container = r[8], measured = tonumber(r[9])})
        end
    end
    add({url = station[3], source = "Katalog dotychczasowy"})
    add({url = station[5], source = "Katalog dotychczasowy"})
    if station.economy then add({url = station.economy.url, source = "Katalog dotychczasowy"}) end
    return list
end

-- Never let settings, phone imports, or one-time compatibility consent
-- exceed 128 kb/s. An unmeasured stream is not a safe direct-play target.
function T.hard_allowed(stream)
    return type(stream) == "table" and type(stream.kbps) == "number"
        and stream.kbps > 0 and stream.kbps <= T.hard_limit
end

function T.cap(settings, codec)
    local limit = tonumber(settings.limit)
    if not limit or limit == 0 then limit = nil end
    local maximum
    if settings.mode == "ultra" then maximum = 32
    elseif settings.mode == "eco" then maximum = codec == "MP3" and 64 or 48
    elseif settings.mode == "auto" then maximum = limit and math.huge or 64
    else maximum = math.huge end
    return math.min(T.hard_limit, limit and math.min(limit, maximum) or maximum)
end

function T.allowed(settings, stream)
    return T.hard_allowed(stream) and stream.kbps <= T.cap(settings, stream.codec)
end

-- Return an address error only for an explicit rejected HTTP response.
-- Legacy Kindle decoders can fail the sample probe while still being able to play.
function T.probeFailure(code, exit_code)
    local http = tonumber(code)
    if http and http >= 500 and http <= 599 then return "server" end
    if http == 408 then return "buffer" end
    if http == 429 then return "server" end
    if http and http >= 400 and http <= 499 then return "address" end
    if http and http >= 300 and http <= 399 then return "transport" end
    if exit_code == 35 or exit_code == 51 or exit_code == 58 or exit_code == 59
        or exit_code == 60 or exit_code == 77 or exit_code == 90 then return "tls" end
    if exit_code == 127 then return "transport" end
    if exit_code == 5 or exit_code == 6 or exit_code == 7 then return "network" end
    if exit_code == 69 then return "server" end
    if exit_code == 22 then return "address" end
    if exit_code == 28 or not exit_code then return "buffer" end
    return "unconfirmed"
end

function T.rank(settings, list, last, speech)
    local minimum = settings.minimum and settings.mode ~= "high"
    local function score(v)
        if v.custom and not minimum then return 50000 end
        if not v.kbps or v.kbps <= 0 then return 0 end
        if not T.allowed(settings, v) then return -1000 + math.min(v.kbps, 999) * -1 end
        local aac = v.codec and v.codec:find("AAC", 1, true)
        local smallest = settings.mode == "ultra" or (settings.mode ~= "high" and settings.minimum)
            or (settings.mode ~= "high" and speech)
        local base = smallest and (300 - v.kbps) or math.min(v.kbps, 512)
        local codec_bonus = aac and 6000 or 0
        if settings.mode == "high" then codec_bonus = 0 end
        if settings.codec == "mp3" then codec_bonus = v.codec == "MP3" and 6000 or 0 end
        if settings.codec == "aac" then codec_bonus = aac and 6000 or 0 end
        if settings.mode == "auto" and settings.codec == "auto" and last == v.url then
            -- A recent audible stream can be more stable than a fresh choice.
            codec_bonus = codec_bonus + 1800
        end
        return 1000 + base * 10 + codec_bonus
            + (v.codec == "HE-AAC v2" and 4 or 0) + (last == v.url and 2 or 0)
    end
    -- Stable order for equal candidates, including user-supplied addresses.
    for i, item in ipairs(list) do item.order = i end
    table.sort(list, function(a, b)
        if minimum and a.kbps and b.kbps and a.kbps > 0 and b.kbps > 0
            and T.allowed(settings, a) and T.allowed(settings, b) and a.kbps ~= b.kbps then
            return a.kbps < b.kbps
        end
        local x, y = score(a), score(b)
        if x == y then return a.order < b.order end
        return x > y
    end)
    return list
end

local adts_rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350}
local function adts(data, i)
    local a,b,c,d,e,f,g = data:byte(i,i+6)
    if not g or a ~= 255 or b % 8 >= 2 or b < 240 then return end
    local rate = adts_rates[math.floor(c / 4) % 16 + 1]
    local size = (d % 4) * 2048 + e * 8 + math.floor(f / 32)
    if not rate or size <= 7 then return end
    return size, (g % 4 + 1) * 1024 / rate, nil, rate, (c % 2) * 4 + math.floor(d / 64)
end

local mp3_rates = {44100, 48000, 32000}
local mpeg1 = {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}
local mpeg2 = {8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}
local function mp3(data, i)
    local a,b,c,d = data:byte(i,i+3)
    if not d or a ~= 255 or b < 224 or math.floor(b / 2) % 4 ~= 1 then return end
    local version = math.floor(b / 8) % 4
    if version == 1 then return end
    local rate = mp3_rates[math.floor(c / 4) % 4 + 1]
    local kbps = (version == 3 and mpeg1 or mpeg2)[math.floor(c / 16)]
    if not rate or not kbps then return end
    rate = rate / (version == 3 and 1 or (version == 2 and 2 or 4))
    local samples = version == 3 and 1152 or 576
    return math.floor(samples * 125 * kbps / rate) + math.floor(c / 2) % 2, samples / rate, kbps
end

function T.sample(data, nominal)
    local function scan(parser)
        for i = 1, #data - 4 do
            local pos, frames, seconds, peak, core, channels = i, 0, 0, 0
            while true do
                local len, duration, rate, sample_rate, channel_count = parser(data, pos)
                if not len or pos + len > #data + 1 then break end
                pos, frames, seconds = pos + len, frames + 1, seconds + duration
                peak = math.max(peak, rate or 0)
                core, channels = sample_rate, channel_count
            end
            if frames >= 8 and seconds >= 0.32 then
                local measured = (pos - i) * 0.008 / seconds
                -- Short ADTS samples fluctuate around their nominal rate.
                -- Leave the measured value intact for the source details.
                local rounded = math.floor(measured + 0.5)
                -- ICY bitrate is a nominal claim. Reject a materially faster sample.
                if nominal and nominal > 0 and measured <= nominal * 1.04 then rounded = nominal end
                return math.max(rounded, peak), measured, core, channels
            end
        end
    end
    local rate, measured, core, channels = scan(adts)
    if rate then return "AAC", rate, measured, "ADTS", core, channels end
    rate, measured = scan(mp3)
    if rate then return "MP3", rate, measured, "MPEG Audio" end
end

local function unsigned(data, first, count, base)
    local value = 0
    for i = first, first + count - 1 do
        local byte = data:byte(i)
        if not byte or byte >= base then return end
        value = value * base + byte
    end
    return value
end

local function utf8char(code)
    if code < 128 then return string.char(code) end
    if code < 2048 then return string.char(192 + math.floor(code / 64), 128 + code % 64) end
    if code < 65536 then
        return string.char(224 + math.floor(code / 4096), 128 + math.floor(code / 64) % 64, 128 + code % 64)
    end
    return string.char(240 + math.floor(code / 262144), 128 + math.floor(code / 4096) % 64,
        128 + math.floor(code / 64) % 64, 128 + code % 64)
end

local function id3_text(data)
    local encoding = data:byte(1)
    local text = data:sub(2)
    if encoding == 0 then
        text = text:gsub("[\128-\255]", function(c) return utf8char(c:byte()) end)
    elseif encoding == 1 or encoding == 2 then
        local little = encoding == 1 and text:sub(1, 2) == "\255\254"
        local first = (little or text:sub(1, 2) == "\254\255") and 3 or 1
        local function unit(i)
            local a, b = text:byte(i, i + 1)
            if b then return little and (b * 256 + a) or (a * 256 + b) end
        end
        local result = {}
        while first < #text do
            local code = unit(first)
            if code == 0 then break end
            first = first + 2
            if code >= 0xd800 and code <= 0xdbff then
                local low = unit(first)
                if low and low >= 0xdc00 and low <= 0xdfff then
                    code = 0x10000 + (code - 0xd800) * 1024 + low - 0xdc00
                    first = first + 2
                end
            end
            if code < 0xd800 or code > 0xdfff then result[#result + 1] = utf8char(code) end
        end
        text = table.concat(result)
    elseif encoding ~= 3 then return end
    text = text:match("^[^%z]*"):gsub("%c", " "):match("^%s*(.-)%s*$")
    if text ~= "" then return text end
end

function T.id3(path)
    local file = io.open(path, "rb")
    if not file then return end
    local header = file:read(10) or ""
    local title, artist
    local version, _, flags = header:byte(4, 6)
    local size = unsigned(header, 7, 4, 128)
    if header:sub(1, 3) == "ID3" and size and size <= 262144 and version >= 2 and version <= 4 then
        local tag = file:read(size) or ""
        local unsync = flags >= 128
        if unsync and version < 4 then tag = tag:gsub("\255\0", "\255") end
        local pos = 1
        if math.floor(flags / 64) % 2 == 1 then
            local extension = version > 2 and unsigned(tag, 1, 4, version == 4 and 128 or 256)
            pos = extension and (extension + (version == 3 and 5 or 1)) or (#tag + 1)
        end
        local id_size, size_size = version == 2 and 3 or 4, version == 2 and 3 or 4
        local header_size = version == 2 and 6 or 10
        while pos + header_size <= #tag do
            local id = tag:sub(pos, pos + id_size - 1)
            local length = unsigned(tag, pos + id_size, size_size, version == 4 and 128 or 256)
            if not id:match("^[A-Z0-9]+$") or not length or length <= 0 or pos + header_size + length - 1 > #tag then break end
            local format = version == 2 and 0 or tag:byte(pos + 9)
            if (format == 0 or (version == 4 and format == 2))
                and (id == "TIT2" or id == "TT2" or id == "TPE1" or id == "TP1") then
                local data = tag:sub(pos + header_size, pos + header_size + math.min(length, 1024) - 1)
                if version == 4 and (unsync or format == 2) then data = data:gsub("\255\0", "\255") end
                if id == "TIT2" or id == "TT2" then title = id3_text(data)
                else artist = id3_text(data) end
                if title and artist then break end
            end
            pos = pos + header_size + length
        end
    end
    if (not title or not artist) and file:seek("end", -128) then
        local tag = file:read(128) or ""
        if tag:sub(1, 3) == "TAG" then
            title = title or id3_text("\0" .. tag:sub(4, 33))
            artist = artist or id3_text("\0" .. tag:sub(34, 63))
        end
    end
    file:close()
    return title, artist
end

return T
