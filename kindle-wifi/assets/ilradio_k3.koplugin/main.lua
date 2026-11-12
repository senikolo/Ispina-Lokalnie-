-- IL Radio K3 uses the reader’s existing GStreamer or another compatible audio engine.
local Device = require("device")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local UIManager = require("ui/uimanager")
local InfoMessage = require("ui/widget/infomessage")
local ConfirmBox = require("ui/widget/confirmbox")
local LuaSettings = require("luasettings")
local DataStorage = require("datastorage")
local modern_lfs, lfs = pcall(require, "libs/libkoreader-lfs")
if not modern_lfs then
    lfs = require("lfs")
end
local dir = debug.getinfo(1, "S").source:sub(2):match("^(.*)/") or "."
local player = dir .. "/player.sh"
local T = dofile(dir .. "/tuning.lua")
local stations = dofile(dir .. "/stations.lua")
local catalog = T.rows(dir .. "/streams.tsv")
local PHONE_CONFIG = "/mnt/us/koreader/settings/ilradio-phone.tsv"
local SPEECH = {["TOK FM"] = true, ["RMF 24"] = true, ["Polskie Radio 24"] = true,
    ["Radio Kraków"] = true, ["Radio RDC"] = true, ["Radio Wnet"] = true,
    ["Polskie Radio Program 1"] = true,
    ["Radio Warszawa"] = true}
-- Only these requested stations may use one-time, explicitly confirmed high-bitrate playback.
-- The global ECO ceiling remains 128 kb/s for every other station and default action.
local HIGH_STATION_CAPS = {["Polskie Radio Program 1"] = 192, ["1.FM Brazilian Birds"] = 256}
local ERRORS = {
    network = "Brak połączenia z serwerem. Sprawdź Wi-Fi i dostęp do internetu.",
    tls = "Nie udało się bezpiecznie połączyć przez HTTPS. Sprawdź datę i certyfikaty Kindle’a.",
    address = "Serwer odmówił dostępu do tego adresu (HTTP 4xx). Spróbuj innego źródła.",
    server = "Serwer stacji jest chwilowo niedostępny (HTTP 5xx). Spróbuj ponownie.",
    buffer = "Strumień buforuje się za długo lub transmisja przerwała.",
    codec = "Silnik nie rozpoznał formatu audio albo brakuje dekodera.",
    output = "Silnik nie uzyskał dostępu do wyjścia audio.",
    unconfirmed = "Nie udało się sprawdzić źródła na tym Kindle. To nie potwierdza, że adres jest błędny.",
    ended = "Transmisja urwała się po rozpoczęciu odtwarzania. Próbuję ponownie lub zmieniam źródło.",
    playback = "Odtwarzacz zakończył pracę po uruchomieniu dźwięku. Sprawdź ostatni kod w diagnostyce.",
    engine = "Brak silnika dla tego formatu. Sprawdź „Diagnostykę audio”.",
    limit = "Parametry źródła przekraczają limit lub przepływność jest nieustalona.",
    transport = "Kindle nie ma narzędzia obsługującego połączenie z tym źródłem.",
}
local AUDIO_ROOTS = {
    music = { "/mnt/us/music", "/mnt/us/koreader/audio/Muzyka" },
    books = { "/mnt/us/audiobooks", "/mnt/us/koreader/audio/Audiobooki" },
}
local ENGINE_OPTIONS = {"auto", "gst-legacy", "gst-mixer", "mplayer", "mpg123", "gst-modern", "mpv"}
local volume_input_open = false
local volume_input_node
local volume_owner
local volume_listener
local RADIO_VERSION = "2.30.14"
local VOLUME_KEYS = {VMinus = -1, VolumeDown = -1, VolumeMinus = -1,
    VPlus = 1, VolumeUp = 1, VolumePlus = 1}

local function read_small(path, limit)
    local file = io.open(path, "rb")
    if not file then return "" end
    local data = file:read(limit or 4096) or ""
    file:close()
    return data
end

local function family(codec)
    return codec and codec:find("AAC", 1, true) and "AAC" or codec
end

local function host(url)
    return (url or ""):match("^https?://([^/%?#]+)") or "Nieustalony"
end

local function label(stream)
    return (stream.codec or "Format nieustalony") .. " · "
        .. (stream.kbps and (stream.kbps .. " kb/s") or "bitrate nieustalony")
end

local function quote(s)
    return "'" .. tostring(s or ""):gsub("'", "'\"'\"'") .. "'"
end
local function shell_call(...)
    local args = { ... }
    local cmd = "sh " .. quote(player)
    for _, arg in ipairs(args) do cmd = cmd .. " " .. quote(arg) end
    local p = io.popen(cmd .. " 2>/dev/null", "r")
    if not p then return "" end
    local result = p:read("*a") or ""
    p:close()
    return (result:gsub("%s+$", ""))
end
local function is_audio(name)
    local extension = name:lower():match("%.([^.]+)$")
    return extension == "mp3" or extension == "wav" or extension == "ogg"
        or extension == "flac" or extension == "m4a" or extension == "m4b"
        or extension == "aac" or extension == "opus"
        or extension == "aif" or extension == "aiff" or extension == "mp4"
end
local function file_uri(path)
    -- Simple percent encoding, including spaces and non-ASCII UTF-8 bytes.
    return "file://" .. path:gsub("([^%w%-%._~/])", function(c)
        return string.format("%%%02X", c:byte())
    end)
end
local function find_files(folders)
    local result = {}
    local seen = {}
    local function walk(path, depth)
        if depth > 3 or #result >= 250 then return end
        if lfs.symlinkattributes(path, "mode") ~= "directory" then return end
        local ok, iter, state = pcall(lfs.dir, path)
        if not ok then return end
        for entry in iter, state do
            if entry ~= "." and entry ~= ".." and entry:sub(1, 1) ~= "." and not entry:lower():match("%.sdr$") then
                local full = path .. "/" .. entry
                local mode = lfs.symlinkattributes(full, "mode")
                if mode == "directory" then walk(full, depth + 1)
                elseif mode == "file" and is_audio(entry) and not seen[full] then
                    seen[full] = true
                    result[#result + 1] = { name = entry, path = full }
                    if #result >= 250 then break end
                end
            end
        end
    end
    for _, path in ipairs(folders) do walk(path, 0) end
    table.sort(result, function(a,b) return a.path:lower() < b.path:lower() end)
    return result
end

local ILRadio = WidgetContainer:extend { name = "ilradio_k3", is_doc_only = false }

local function register_volume_key(input, code, key, event, direction)
    local mapped = input.event_map[code]
    if mapped and VOLUME_KEYS[mapped] ~= direction then return end
    if not input.event_map_adapter or not UIManager.event_handlers then
        input.event_map[code] = mapped or key
        return
    end
    -- A normal KeyPress can stop at a modal widget before reaching the plugin.
    input.event_map[code] = event
    input.event_map_adapter[event] = function(ev)
        if (ev.value == 1 or ev.value == 2) and (volume_owner
            or (volume_listener and volume_listener:active_playback())) then return event end
        if not volume_owner and mapped and type(input.handleKeyBoardEv) == "function" then
            input.event_map[code] = mapped
            local ok, result = pcall(input.handleKeyBoardEv, input, ev)
            input.event_map[code] = event
            if ok then return result end
        end
    end
    UIManager.event_handlers[event] = function()
        local listener = volume_owner or volume_listener
        if listener then listener:rocker_change(direction) end
    end
end

function ILRadio:init()
    volume_listener = self
    if Device.model == "Kindle3" and Device.input and Device.input.event_map and not volume_input_open then
        local input = Device.input
        register_volume_key(input, 114, "VMinus", "ILRadioVolumeDown", -1)
        register_volume_key(input, 115, "VPlus", "ILRadioVolumeUp", 1)
        local node = shell_call("volume-node")
        if not node:match("^/dev/input/event%d+$") then node = "/dev/input/event2" end
        volume_input_node = node
        local ok, opened = pcall(input.open, input, node, "Kindle Keyboard volume keys")
        volume_input_open = ok and opened ~= false
    end
    self.settings = LuaSettings:open(DataStorage:getSettingsDir() .. "/ilradio_k3.lua")
    self.engine = self.settings:readSetting("engine") or "auto"
    self.volume = tonumber(self.settings:readSetting("volume")) or 70
    self.volume = math.max(0, math.min(100, self.volume))
    -- Software gain is opt-in; normal mode preserves MPlayer's prior level.
    self.boost = tonumber(self.settings:readSetting("audio_softvol_max")) or 110
    if self.boost ~= 110 and self.boost ~= 140 and self.boost ~= 170 then self.boost = 110 end
    self.radio = self.settings:readSetting("radio_quality") or {
        mode = "auto", codec = "auto", limit = 128, buffer = 128, minimum = true,
    }
    self.working = self.settings:readSetting("radio_last_working") or {}
    self.decoded = self.settings:readSetting("radio_last_decoded") or {}
    self.show_volume_buttons = self.settings:readSetting("show_volume_buttons") ~= false
    self:read_phone_config()
    -- Upgrade existing installations to ECO 128 once. Subsequent explicit
    -- choices remain available, but the absolute ceiling stays at 128 kb/s.
    if not self.settings:readSetting("radio_eco128_migrated") then
        self.radio.mode = "auto"
        self.radio.codec = "auto"
        self.radio.limit = 128
        self.radio.minimum = true
        self.settings:saveSetting("radio_quality", self.radio)
        self.settings:saveSetting("radio_eco128_migrated", true)
    end
    self.volume_step = tonumber(self.settings:readSetting("volume_step")) or 5
    if self.volume_step ~= 2 and self.volume_step ~= 5 and self.volume_step ~= 10 then self.volume_step = 5 end
    self.last_station_name = self.settings:readSetting("last_station_name")
    self.favorites = self.settings:readSetting("favorites") or {}
    self.positions = self.settings:readSetting("positions") or {}
    self.current = nil
    self.position_tick = function() self:track_position() end
    self.radio_tick = function() self:monitor_radio() end
    self.probe_tick = function() self:check_probe() end
    self.retry_tick = function() self:advance_radio() end
    self.decode_tick = function() self:check_decode_test() end
    self.start_check = function() self:monitor_local() end
    self.queue = self.settings:readSetting("audio_queue")
    self.playlist = nil
    self.track_index = nil
    self.ui.menu:registerToMainMenu(self)
end
function ILRadio:read_phone_config()
    self.custom = {}
    for _, row in ipairs(T.rows(PHONE_CONFIG)) do
        if row[1] == "@" and row[2] and row[2] ~= "-" and row[2] ~= self.settings:readSetting("radio_phone_revision") then
            local mode = ({ultra = true, eco = true, auto = true, high = true})[row[3] or ""]
            local codec = ({auto = true, aac = true, mp3 = true})[row[5] or ""]
            local buffer = tonumber(row[6])
            local limit = tonumber(row[4])
            local valid_limit = row[4] == ""
            for _, value in ipairs(T.limits) do if value == limit then valid_limit = true end end
            if mode and codec and valid_limit and (buffer == 64 or buffer == 128 or buffer == 256) then
                self.radio = {mode = row[3], limit = limit, codec = row[5], buffer = buffer, minimum = row[7] == "1"}
                if row[8] == "0" or row[8] == "1" then
                    self.show_volume_buttons = row[8] == "1"
                    self.settings:saveSetting("show_volume_buttons", self.show_volume_buttons)
                end
                self.settings:saveSetting("radio_quality", self.radio)
                self:save("radio_phone_revision", row[2])
            end
        elseif row[1] ~= "@" and T.url(row[2]) then
            self.custom[#self.custom + 1] = row
        end
    end
end

function ILRadio:available_engines(codec, hls)
    local options = {"mplayer", "gst-legacy", "gst-modern", "mpg123", "mpv"}
    if self.engine ~= "auto" then
        local preferred = self.engine
        for i, engine in ipairs(options) do if engine == preferred then table.remove(options, i); break end end
        table.insert(options, 1, preferred)
    end
    local result = {}
    for _, engine in ipairs(options) do
        if (not hls or engine == "gst-modern" or engine == "mpv")
            and (engine ~= "mpg123" or codec == "MP3" or codec == nil)
            and shell_call("probe", engine) == engine then result[#result + 1] = engine end
    end
    return result
end

function ILRadio:radio_result(code)
    local job = self.job
    if not job then return end
    local source = job.active and job.active.url or job.station[1]
    shell_call("report", job.station[1], source, code)
end

function ILRadio:finish_radio()
    local job = self.job
    if not job then return end
    local higher = job.higher or job.http_fallback
    if higher and not T.hard_allowed(higher) then higher = nil end
    -- Older Kindle 3 decoders may reject every low-bitrate HE-AAC stream.
    -- Offer a known MP3 source instead of leaving the station silent.
    -- Never exceed the chosen data limit without an explicit confirmation.
    local needs_compatibility = {
        codec = true, engine = true, playback = true, ended = true,
        buffer = true, limit = true, unconfirmed = true,
    }
    if not higher and needs_compatibility[job.reason or "unconfirmed"] then
        for _, candidate in ipairs(job.variants or {}) do
            if candidate.codec == "MP3" and T.hard_allowed(candidate)
                and not T.allowed(self.radio, candidate)
                and (not higher or candidate.kbps < higher.kbps) then
                higher = candidate
            end
        end
    end
    local message = ERRORS[job.reason] or ERRORS.unconfirmed
    self:radio_result(job.reason or "unconfirmed")
    if not self:stop() then return end
    if higher and not job.approved then
        UIManager:show(ConfirmBox:new {
            text = message .. "\n\n„" .. job.station[1] .. "” · " .. label(higher)
                .. (higher.direct and " według katalogu. Czy spróbować jednorazowo przez HTTP bez potwierdzonego bitrate’u?"
                    or ". Czy uruchomić go jednorazowo poza limitem?"),
            ok_text = "Uruchom ten wariant", cancel_text = "Zostań przy limicie",
            ok_callback = function() self:play_station(job.station, higher.url, higher.direct) end,
        })
    else
        self:notice(message, 9)
    end
end

function ILRadio:continue_radio(reason)
    local job = self.job
    if not job then return end
    job.reason = reason or job.reason
    local stream = job.active
    if reason == "buffer" and job.http_only and not job.http_fallback and stream
        and stream.url:match("^http://") and stream.codec and stream.kbps and not stream.live_codec then
        stream.direct = true
        job.http_fallback = stream
    end
    if reason then self:radio_result(reason) end
    if shell_call("stop") ~= "stopped" then
        self:stop()
        return
    end
    self.current = nil
    if volume_owner == self then volume_owner = nil end
    UIManager:unschedule(self.radio_tick)
    UIManager:scheduleIn(reason == "network" and 6 or 2, self.retry_tick)
end

function ILRadio:start_radio_variant(stream)
    local job = self.job
    local codec = family(stream.codec)
    local container = stream.live_container or stream.container or ""
    local hls = container:match("^HLS")
    local key = hls and "HLS" or codec or "?"
    stream.engine_key = key
    local options = job.engines[key]
    if not options then
        options = self:available_engines(codec, hls)
        job.engines[key] = options
    end
    local engine = options[job.engine_index]
    if not engine or job.starts >= 8 then self:continue_radio(job.reason or "engine"); return end
    job.starts = job.starts + 1
    local uri = stream.play_url or stream.url
    local cache = math.max(tonumber(self.radio.buffer) or 128, job.reconnect_cache or 0)
    local result = shell_call("start", uri, engine, self.volume, "", 0, cache, codec or "", job.station[1], "", self.boost)
    if not result:match("^starting:") then
        job.engine_index = job.engine_index + 1
        self:continue_radio("engine")
        return
    end
    self.current = {kind = "radio", name = job.station[1], station = job.station, uri = uri,
        engine = engine, stream = stream, evidence = "pending", checks = 0}
    volume_owner = self
    self:radio_result("pending")
    self:notice("Łączę z „" .. job.station[1] .. "” · " .. label(stream), 3)
    UIManager:scheduleIn(5, self.radio_tick)
end

local function known_http_mp3(stream)
    return stream and stream.codec == "MP3" and tonumber(stream.kbps)
        and stream.kbps > 0 and stream.url and stream.url:match("^http://")
end

local function offer_higher(job, stream)
    if not job.higher or (stream.kbps and (not job.higher.kbps or stream.kbps < job.higher.kbps)) then
        job.higher = stream
    end
end

local function station_stream_permitted(job, settings, stream)
    if T.hard_allowed(stream) and (job.approved or T.allowed(settings, stream)) then return true end
    return job.high_ceiling and type(stream.kbps) == "number"
        and stream.kbps > T.hard_limit and stream.kbps <= job.high_ceiling
end

function ILRadio:accept_probe(stream)
    local job = self.job
    if station_stream_permitted(job, self.radio, stream) then
        job.ready = stream
        self:start_radio_variant(stream)
    else
        offer_higher(job, stream)
        self:continue_radio("limit")
    end
end

function ILRadio:check_probe()
    local job = self.job
    if not job or not job.probe then return end
    local prefix = job.probe
    local done = read_small(prefix .. ".done", 16)
    if done == "" and job.probe_checks < 7 then
        job.probe_checks = job.probe_checks + 1
        UIManager:scheduleIn(2, self.probe_tick)
        return
    end
    job.probe = nil
    local raw = read_small(prefix .. ".meta", 2048)
    local code, final, method = raw:match("^(%d+)\n([^\r\n]+)\n?([^\r\n]*)")
    local exit = tonumber(done)
    local stream = job.active
    local headers = read_small(prefix .. ".headers", 16384):lower():gsub("\r", "")
    -- Only the last response's bitrate belongs to the active endpoint.
    local final_header
    for block in headers:gmatch("(.-)\n\n") do
        if block:match("^http/") or block:match("^icy%s") then final_header = block end
    end
    local nominal = tonumber((final_header or ""):match("\nicy%-br:%s*(%d+)"))
        or tonumber((final_header or ""):match("\nice%-bitrate:%s*(%d+)"))
    local native_sample = method == "mplayer" and exit == 0 and final == stream.url
    local http_sample = (code == "200" or code == "206") and (not method or not method:match("^wget") or exit == 0)
    if (native_sample or http_sample) and T.url(final) then
        local redirect_unknown = method == "wget" or native_sample
        local sample = read_small(prefix .. ".sample", 16384)
        local hls = sample:match("^#EXTM3U") and sample:find("\n#EXT%-X%-")
        local codec, kbps, measured, container, core_rate, core_channels = T.sample(sample, nominal)
        if codec then
            stream.live_codec = codec
            stream.live_kbps = kbps
            stream.live_measured = measured
            stream.live_nominal = nominal
            stream.live_final = not redirect_unknown and final or nil
            stream.redirect_unknown = redirect_unknown
            -- Avoid reusing short-lived redirect tokens on a separate connection.
            stream.play_url = final:find("rj%-tok=") and stream.url or final
            stream.live_container = container
            stream.core_rate, stream.core_channels = core_rate, core_channels
            if family(stream.codec) ~= codec then stream.codec = codec end
            stream.kbps = kbps
            self:accept_probe(stream)
            return
        end
        -- Unknown bitrate is not safe under a strict 128 kb/s ceiling.
        if hls then
            offer_higher(job, stream)
            self:continue_radio("limit")
        else
            self:continue_radio(exit == 28 and "buffer" or "codec")
        end
    else
        local failure = T.probeFailure(code, exit)
        -- An old MPlayer may play HTTP MP3 correctly but fail to dump a
        -- probe sample (often with a timeout). Do not mislabel that as 4xx.
        -- Never bypass a confirmed HTTP error, DNS error, or TLS error.
        local ambiguous = failure == "unconfirmed"
            or (failure == "buffer" and (method == "mplayer" or code == "200" or code == "206"))
        if ambiguous and known_http_mp3(stream) and not stream.native_direct_tried
            and shell_call("probe", "mplayer") == "mplayer" then
            stream.native_direct_tried = true
            stream.play_url = stream.url
            if station_stream_permitted(job, self.radio, stream) then
                self:accept_probe(stream)
            else
                -- Exceeding the chosen data limit still needs explicit consent.
                stream.direct = true
                offer_higher(job, stream)
                self:continue_radio("limit")
            end
            return
        end
        self:continue_radio(failure)
    end
end

function ILRadio:advance_radio()
    local job = self.job
    if not job then return end
    if job.ready then
        local stream = job.ready
        local engines = job.engines[stream.engine_key]
        if engines and engines[job.engine_index] and job.starts < 8 then
            self:start_radio_variant(stream)
            return
        end
        job.ready = nil
    end
    if job.starts >= 8 or job.index >= 6 then self:finish_radio(); return end
    job.index = job.index + 1
    local stream = job.variants[job.index]
    if not stream then self:finish_radio(); return end
    job.active = stream
    job.engine_index = 1
    if job.direct and T.hard_allowed(stream) and stream.url:match("^http://") then
        self:accept_probe(stream)
        return
    end
    local probe = shell_call("probe-start", stream.url)
    if probe:match("^/[^\r\n]+/probe%-%d+%-%d+$") then
        job.probe = probe
        job.probe_checks = 0
        UIManager:scheduleIn(2, self.probe_tick)
    elseif probe == "unavailable" then
        -- The network-probe tool may be absent on Kindle 3 even when native
        -- HTTP MP3 playback is functional. Permit exactly one constrained
        -- attempt for a catalogued MP3 stream with a known bitrate.
        if known_http_mp3(stream) and not stream.native_direct_tried
            and shell_call("probe", "mplayer") == "mplayer" then
            stream.native_direct_tried = true
            stream.play_url = stream.url
            if station_stream_permitted(job, self.radio, stream) then
                self:accept_probe(stream)
            else
                stream.direct = true
                offer_higher(job, stream)
                self:continue_radio("limit")
            end
        else
            self:continue_radio("transport")
        end
    else
        self:continue_radio((probe == "address" or probe == "invalid_target") and "address" or "transport")
    end
end

function ILRadio:monitor_radio()
    local job, item = self.job, self.current
    if not job or not item then return end
    if shell_call("timer-expired") == "yes" then
        self:stop()
        self:notice("Odtwarzanie zakończone przez wyłącznik czasowy.", 6)
        return
    end
    local evidence = shell_call("evidence")
    local reason = evidence:match("^failed:(%w+)")
    -- Do not use MPlayer's timeline to infer a live-stream stall.
    -- Older ARM builds can keep reporting the same position while audio plays.
    -- Actual process termination or a decoder/transport error still triggers recovery.
    -- Do not mistake delayed logs for stopped radio while MPlayer is alive.
    if not reason and evidence == "pending" and item.checks >= 4
        and (shell_call("status") ~= "active" or shell_call("health") == "failed") then
        reason = "buffer"
    end
    if reason then
        if item.established and (item.stable or 0) >= 3 and not job.reconnecting then
            -- New bounded budget after an established session, with one retry of the last source.
            job.starts, job.reconnecting, job.engine_index = 0, true, 1
            -- An established radio stream that drops may be a Wi-Fi underrun.
            -- Retry with the maximum existing buffer (256 KiB), without
            -- raising bitrate or changing persisted user settings.
            if reason == "buffer" or reason == "network" or reason == "ended"
                or reason == "playback" then job.reconnect_cache = 256 end
            local ranked = T.rank(self.radio, T.variants(job.station, catalog, self.custom),
                item.stream.url, SPEECH[item.name])
            job.variants = {item.stream}
            for _, variant in ipairs(ranked) do
                if variant.url ~= item.stream.url and not job.approved then
                    job.variants[#job.variants + 1] = variant
                end
            end
            job.index, job.ready = 0, nil
        elseif item.evidence == "decoded" and not job.short_retry and job.starts < 8
            and (reason == "playback" or reason == "ended" or reason == "buffer") then
            -- A station heard briefly should get one retry of its proven
            -- stream before we abandon it for a different codec or CDN.
            job.short_retry = true
            job.engine_index = 1
            job.ready = item.stream
        else
            job.engine_index = job.engine_index + 1
            -- Network/TLS errors usually need another URL, rather than another decoder.
            if reason == "tls" or reason == "address" or reason == "network" or reason == "transport" then job.ready = nil end
        end
        if reason == "network" and not job.network_retry then
            job.network_retry = true
            job.index = math.max(0, job.index - 1)
        end
        self:continue_radio(reason)
        return
    end
    item.checks = item.checks + 1
    if evidence == "decoded" then
        if item.evidence ~= "decoded" then self:radio_result("decoded") end
        item.evidence = "decoded"
        if not item.decoder_facts then item.decoder_facts = shell_call("facts") end
        item.stable = (item.stable or 0) + 1
        if item.stable >= 3 and not item.established then
            job.reconnecting = false
            item.established = true
            job.network_retry = false
            if self.decoded[item.name] ~= item.stream.url then
                self.decoded[item.name] = item.stream.url
                self:save("radio_last_decoded", self.decoded)
            end
        end
    elseif item.checks >= 3 and item.evidence ~= "unconfirmed" then
        item.evidence = "unconfirmed"
        self:radio_result("unconfirmed")
    end
    UIManager:scheduleIn(item.checks < 3 and 5 or 20, self.radio_tick)
end

function ILRadio:find_last_station()
    for _, item in ipairs(stations) do
        if item[1] == self.last_station_name then return item end
    end
end

function ILRadio:save(key, value)
    self.settings:saveSetting(key, value)
    self.settings:flush()
end
function ILRadio:notice(text, seconds)
    UIManager:show(InfoMessage:new { text = text, timeout = seconds or 5 })
end
function ILRadio:status()
    return shell_call("status")
end
function ILRadio:remember_position()
    local item = self.current
    if not item or item.kind ~= "books" or item.engine ~= "mplayer" then return end
    local position = tonumber(shell_call("position"))
    if position and position >= 0 and self.positions[item.path] ~= position then
        self.positions[item.path] = position
        self:save("positions", self.positions)
    end
end

function ILRadio:track_position()
    local state = self:status()
    if state ~= "active" then return end
    self:remember_position()
    UIManager:scheduleIn(12, self.position_tick)
end

function ILRadio:stop()
    UIManager:unschedule(self.position_tick)
    UIManager:unschedule(self.start_check)
    UIManager:unschedule(self.radio_tick)
    UIManager:unschedule(self.probe_tick)
    UIManager:unschedule(self.retry_tick)
    self:remember_position()
    if shell_call("stop") ~= "stopped" then
        self.job = nil
        self:notice("Odtwarzacz nadal się zatrzymuje. Spróbuj ponownie.", 7)
        return false
    end
    self.job = nil
    self.current = nil
    if volume_owner == self then volume_owner = nil end
    return true
end

function ILRadio:onCloseWidget()
    UIManager:unschedule(self.decode_tick)
    shell_call("timer-cancel")
    -- The player is a child process and can outlive the KOReader widget.
    -- Always ask the shell controller to stop it, even after UI state loss.
    self:stop()
end

function ILRadio:start_local(item)
    while item.engine_index < #item.engines do
        item.engine_index = item.engine_index + 1
        local engine = item.engines[item.engine_index]
        local offset = item.kind == "books" and engine == "mplayer" and self.positions[item.path] or 0
        local result = shell_call("start", item.uri, engine, self.volume, item.path, offset or 0, 128, "", item.name, item.kind, self.boost)
        if result:match("^starting:") then
            item.engine = engine
            item.checks, item.evidence = 0, "pending"
            self.current = item
            volume_owner = self
            local note = item.kind == "books" and engine ~= "mplayer"
                and " · ten silnik zaczyna od początku" or ""
            self:notice("Otwieram „" .. item.name .. "”…" .. note, 4)
            UIManager:scheduleIn(2, self.start_check)
            if item.kind == "books" and engine == "mplayer" then UIManager:scheduleIn(6, self.position_tick) end
            return true
        end
        if result == "missing_file" or result == "invalid_local_path" or result == "stop_failed" then break end
    end
    self:notice("Nie udało się uruchomić pliku. Sprawdź Diagnostykę audio.", 8)
    return false
end

function ILRadio:play(uri, name, kind, path)
    local extension = path:lower():match("%.([^.]+)$")
    local codec = extension == "mp3" and "MP3" or "other"
    local engines = self:available_engines(codec)
    local preferred = kind == "books" and "mplayer" or self.engine == "auto" and shell_call("probe", "auto")
    for index, engine in ipairs(engines) do
        if engine == preferred then
            table.remove(engines, index)
            table.insert(engines, 1, engine)
            break
        end
    end
    if #engines == 0 then
        self:notice("Nie znaleziono silnika do odtworzenia tego pliku. Otwórz Diagnostykę audio.", 8)
        return false
    end
    if (self.current or self.job) and not self:stop() then return false end
    return self:start_local({uri = uri, name = name, kind = kind, path = path,
        engines = engines, engine_index = 0})
end

function ILRadio:monitor_local()
    local item = self.current
    if not item or (item.kind ~= "music" and item.kind ~= "books") then return end
    if shell_call("timer-expired") == "yes" then
        self:stop()
        self:notice("Odtwarzanie zakończone przez wyłącznik czasowy.", 6)
        return
    end
    local result = shell_call("local-result")
    if result == "paused" then return end
    if result == "decoded" or result == "pending" then
        item.checks = item.checks + 1
        if result == "decoded" then item.evidence = "decoded" end
        -- Older MPlayer may play audibly without emitting startup log lines.
        -- Avoid terminating a healthy decoder after ~14 seconds.
        local active_without_error = result == "pending"
            and shell_call("status") == "active"
            and shell_call("health") ~= "failed"
        if item.evidence == "decoded" or item.checks < 5 or active_without_error then
            UIManager:scheduleIn(item.evidence == "decoded" and 8
                or (item.checks < 5 and 3 or 8), self.start_check)
            return
        end
    end
    UIManager:unschedule(self.position_tick)
    self.current = nil
    if volume_owner == self then volume_owner = nil end
    if result == "ended" then
        if item.kind == "books" and item.engine == "mplayer" and self.positions[item.path] then
            self.positions[item.path] = nil
            self:save("positions", self.positions)
        end
        if self.playlist and self.track_index and self.playlist[self.track_index + 1] then
            self:next_track(1)
        end
    elseif item.evidence ~= "decoded" then
        if not self:start_local(item) then shell_call("stop") end
    elseif not item.short_retry and item.engine_index < #item.engines then
        -- One alternate decoder on a genuinely failed local file. No loop
        -- and never restart a track whose playback ended normally.
        item.short_retry = true
        if not self:start_local(item) then shell_call("stop") end
    else
        self:notice("Odtwarzanie pliku zostało przerwane. Sprawdź Diagnostykę audio.", 7)
    end
end

function ILRadio:play_station(station, approved_url, direct, high_ceiling)
    -- A higher cap is never imported, persisted or inferred from the stream.
    -- It only comes from the station-specific confirmation action.
    if high_ceiling ~= HIGH_STATION_CAPS[station[1]] then high_ceiling = nil end
    self:read_phone_config()
    local variants = T.variants(station, catalog, self.custom)
    -- Reject known high-bitrate streams before contacting the server.
    -- Unknown variants must pass a probe with a safe measured bitrate.
    local bounded = {}
    for _, stream in ipairs(variants) do
        if not stream.kbps or T.hard_allowed(stream)
            or (high_ceiling and stream.kbps > T.hard_limit and stream.kbps <= high_ceiling) then
            bounded[#bounded + 1] = stream
        end
    end
    variants = bounded
    local http_only = shell_call("radio-transport") == "http-only"
    if http_only then
        for i = #variants, 1, -1 do
            if not variants[i].url:match("^http://") then table.remove(variants, i) end
        end
    end
    if approved_url then
        local filtered = {}
        for _, stream in ipairs(variants) do
            if stream.url == approved_url then filtered[1] = stream; break end
        end
        variants = filtered
    end
    if #variants == 0 and HIGH_STATION_CAPS[station[1]] and not high_ceiling then
        self:notice("Ta stacja nadaje obecnie w " .. HIGH_STATION_CAPS[station[1]]
            .. " kb/s. W menu stacji wybierz „Słuchaj mimo limitu”, aby potwierdzić jednorazowo.", 11)
        return
    end
    if not self:stop() then return end
    self.job = {high_ceiling = high_ceiling, station = station, variants = T.rank(self.radio, variants, self.working[station[1]] or self.decoded[station[1]], SPEECH[station[1]]),
        index = 0, starts = 0, engines = {}, approved = approved_url ~= nil, http_only = http_only, direct = direct}
    self.last_station_name = station[1]
    self.settings:saveSetting("last_station_name", self.last_station_name)
    self:save("last_audio_kind", "radio")
    self.playlist = nil
    self.track_index = nil
    self:notice("Sprawdzam źródła stacji „" .. station[1] .. "”…", 3)
    if #variants == 0 and http_only then
        self:continue_radio("transport")
    else
        self:advance_radio()
    end
end

function ILRadio:next_station(delta)
    for i, station in ipairs(stations) do
        if station[1] == self.last_station_name then
            self:play_station(stations[(i - 1 + delta) % #stations + 1])
            return
        end
    end
    self:play_station(stations[1])
end

function ILRadio:recovered_radio()
    local name, url, result = shell_call("report-current"):match("^([^\n]+)\n([^\n]+)\n([^\n]+)")
    if not T.url(url) then return end
    for _, station in ipairs(stations) do
        if station[1] == name then
            local stream = {url = url}
            for _, candidate in ipairs(T.variants(station, catalog, self.custom or {})) do
                if candidate.url == url then stream = candidate; break end
            end
            return {kind = "radio", name = name, uri = url, stream = stream,
                engine = shell_call("engine-current"), evidence = result, audible = result == "audible"}
        end
    end
end

function ILRadio:active_playback()
    if self.current then return self.current end
    local state = self:status()
    if state ~= "active" and state ~= "paused" then return end
    local kind, path = shell_call("local-current"):match("^([^\n]+)\n(.+)$")
    local item
    if kind == "music" or kind == "books" then
        item = {kind = kind, path = path, name = path:match("([^/]+)$"), uri = file_uri(path),
            engine = shell_call("engine-current"), evidence = shell_call("evidence"),
            checks = 0, engines = {}, engine_index = 0}
        if self.queue and self.queue.path == path and self.queue.group == kind then self:restore_queue() end
        if state == "active" then
            UIManager:unschedule(self.start_check)
            UIManager:scheduleIn(2, self.start_check)
            if kind == "books" and item.engine == "mplayer" then
                UIManager:unschedule(self.position_tick)
                UIManager:scheduleIn(6, self.position_tick)
            end
        end
    else
        item = self:recovered_radio()
    end
    if not item then
        item = {name = "Aktywne odtwarzanie", engine = shell_call("engine-current")}
    end
    self.current = item
    volume_owner = self
    return item
end

function ILRadio:confirm_sound()
    if self:status() ~= "active" then
        self:notice("Najpierw uruchom stację.")
        return
    end
    local item = self:active_playback()
    if not item then
        self:notice("Odtwarzanie działa, ale nie mogę ustalić stacji. Uruchom ją ponownie z menu Radia.", 8)
        return
    end
    if item.kind ~= "radio" then
        self:notice("Teraz odtwarzany jest plik audio, a nie stacja radiowa.")
        return
    end
    item.audible = true
    self.working[item.name] = item.stream.url
    self:save("radio_last_working", self.working)
    shell_call("report", item.name, item.stream.url, "audible")
    self:notice("Zapisano, że słychać tę stację.")
end

function ILRadio:set_quality(key, value)
    self.radio[key] = value
    self:save("radio_quality", self.radio)
    if self.job then self:play_station(self.job.station)
    else self:notice("Ustawienie zadziała przy następnym uruchomieniu stacji.") end
end

function ILRadio:play_file(items, idx, group)
    local row = items[idx]
    if row and self:play(file_uri(row.path), row.name, group, row.path) then
        self.playlist = items
        self.track_index = idx
        self.track_group = group
        self.queue = {path = row.path, group = group}
        self.settings:saveSetting("audio_queue", self.queue)
        self:save("last_audio_kind", group)
    end
end

function ILRadio:restore_queue()
    if self.playlist or type(self.queue) ~= "table" or not AUDIO_ROOTS[self.queue.group] then return end
    local items = find_files(AUDIO_ROOTS[self.queue.group])
    for idx, row in ipairs(items) do
        if row.path == self.queue.path then
            self.playlist, self.track_index, self.track_group = items, idx, self.queue.group
            return
        end
    end
end

function ILRadio:next_track(delta)
    if not self.job then self:restore_queue() end
    if not self.playlist or not self.track_index then
        self:notice("Najpierw wybierz utwór lub rozdział audiobooka.")
        return
    end
    local next_index = self.track_index + delta
    if next_index < 1 or next_index > #self.playlist then
        self:notice("To początek lub koniec listy.")
        return
    end
    self:play_file(self.playlist, next_index, self.track_group)
end

function ILRadio:playing_info(full)
    local item = self:active_playback()
    if not item then
        self:notice(self.job and ("Sprawdzam źródła stacji „" .. self.job.station[1] .. "”…")
            or "Brak aktywnego odtwarzania. Wybierz stację lub otwórz Diagnostykę audio.", 8)
        return
    end
    local state = self:status()
    local title, artist
    if item.kind == "radio" then title = shell_call("title")
    elseif item.path and item.path:lower():match("%.mp3$") then title, artist = T.id3(item.path) end
    local stream = item.stream
    -- Keep the everyday "Co gra?" panel short; raw transport facts stay in
    -- "Szczegóły źródła", which still calls playing_info(true).
    if not full then
        local state_label = state == "paused" and "Pauza"
            or state == "stopped" and "Zatrzymane"
            or (item.audible or item.evidence == "decoded") and "Odtwarzanie"
            or "Odtwarzacz uruchomiony"
        local summary = item.name
        if item.kind == "radio" then
            summary = summary .. "\nTeraz gra: " .. (title and title ~= "" and title or "brak informacji od stacji")
            local codec = stream and (stream.live_codec or stream.catalog_label or stream.codec)
            local kbps, provenance
            if stream and stream.live_measured then
                kbps = string.format("%.0f kb/s", stream.live_measured)
                provenance = "próbka"
            elseif stream and stream.live_nominal then
                kbps = tostring(stream.live_nominal) .. " kb/s"
                provenance = "serwer"
            elseif stream and stream.kbps then
                kbps = tostring(stream.kbps) .. " kb/s"
                provenance = "katalog"
            end
            local stream_label = codec or "format nieustalony"
            if kbps then
                stream_label = stream_label .. " · " .. kbps .. " (" .. provenance .. ")"
            end
            summary = summary .. "\nStrumień: " .. stream_label
        else
            if title and title ~= "" and title ~= item.name then
                summary = summary .. "\nUtwór: " .. title
            end
            if artist and artist ~= "" then
                summary = summary .. "\nWykonawca: " .. artist
            end
            local extension = item.path and item.path:match("%.([^.]+)$")
            if extension then
                summary = summary .. "\nFormat: " .. extension:upper()
            end
        end
        summary = summary .. "\nStan: " .. state_label .. "\nGłośność: " .. self.volume .. "%"
        self:notice(summary, 12)
        return
    end
    local status = state == "paused" and "Pauza" or state == "stopped" and "Zatrzymane"
        or item.audible and "Dźwięk potwierdzony przez słuchacza"
        or item.evidence == "decoded" and "Silnik zgłosił dekodowanie i wyjście audio"
        or "Odtwarzacz uruchomiony, dźwięk niepotwierdzony"
    local text = item.name .. "\nStan: " .. status
        .. "\nSilnik: " .. item.engine .. "\nGłośność: " .. self.volume .. "%"
    if stream then
        local mode = self.radio.mode
        for _, option in ipairs(T.modes) do if option[1] == mode then mode = option[2]; break end end
        local codec = stream.live_codec or "Nieustalony"
        local facts = item.decoder_facts or ""
        local output_rate, output_channels = facts:match("pcm=(%d+) (%d+)")
        if codec == "AAC" and stream.core_rate and tonumber(output_rate) == stream.core_rate * 2 then
            codec = stream.core_channels == 1 and tonumber(output_channels) == 2 and "HE-AAC v2" or "HE-AAC"
            codec = codec .. " (próbka i dekoder)"
        elseif codec == "Nieustalony" and stream.catalog_label then
            codec = codec .. " · " .. stream.catalog_label .. " (katalog)"
        elseif codec == "AAC" and stream.catalog_label then
            codec = "AAC (próbka) · " .. stream.catalog_label .. " (katalog)"
            if stream.catalog_label:find("^HE%-AAC") and stream.core_rate == tonumber(output_rate) then
                codec = codec .. " · dekoder zgłasza wyjście w częstotliwości rdzenia"
            end
        end
        local rate = stream.live_measured and string.format("%.1f kb/s (próbka)", stream.live_measured) or "Nieustalony"
        if stream.live_nominal then rate = rate .. " · " .. stream.live_nominal .. " kb/s (serwer)"
        elseif not stream.live_measured and stream.kbps then rate = rate .. " · " .. stream.kbps .. " kb/s (katalog)" end
        local origin = stream.source or "Nieustalony"
        local active_host = stream.redirect_unknown and "Nieustalony po przekierowaniach"
            or host(stream.live_final or item.uri)
        if stream.live_final and active_host ~= host(stream.final or stream.url) then
            origin = "Przekierowanie · typ serwera nieustalony"
        end
        text = text .. "\nTryb: " .. mode .. (self.job and self.job.approved and " · zgoda jednorazowa" or "")
            .. "\nKodek: " .. codec .. "\nBitrate: " .. rate
            .. "\nSerwer próby: " .. active_host .. "\nŹródło: " .. origin
            .. "\nPołączenie: Wi-Fi"
            .. "\nParametry: próbka przed połączeniem; serwer może je zmienić"
        if stream.live_measured and stream.live_measured < 128 then
            text = text .. string.format("\nTransfer audio względem 128 kb/s: −%.1f%%",
                (128 - stream.live_measured) * 100 / 128)
        end
        if full then
            local facts = shell_call("facts")
            local decoder = facts:match("decoder=([^\r\n]+)")
            text = text .. "\n\nAdres katalogowy: " .. stream.url
                .. "\nPo przekierowaniu (próba): " .. (stream.live_final or "Nieustalony")
                .. "\nAdres przekazany silnikowi: " .. item.uri
                .. "\nFormat próbki: " .. (stream.live_container or "Nieustalony")
                .. "\nKatalog: " .. (stream.catalog_label or "Parametry nieustalone")
                .. "\nWeryfikacja katalogu: " .. (stream.verified and stream.verified ~= "" and stream.verified or "Nieustalona")
                .. "\nDekoder zgłoszony przez silnik: " .. (decoder or "Nieustalony")
        end
    end
    if title and title ~= "" then text = text .. "\nUtwór: " .. title end
    if artist and artist ~= "" then text = text .. "\nWykonawca: " .. artist end
    if full then
        local ok, TextViewer = pcall(require, "ui/widget/textviewer")
        if ok then
            UIManager:show(TextViewer:new {title = "Szczegóły źródła", text = text})
            return
        end
    end
    self:notice(text, full and 32 or 16)
end

function ILRadio:toggle_pause()
    local state = self:status()
    local item = self:active_playback()
    if state == "stopped" and not self.job and self.settings:readSetting("last_audio_kind") ~= "radio" then
        self:restore_queue()
    end
    if state == "active" then
        if not item or not item.path then
            if self:stop() then
                shell_call("timer-cancel")
                self:notice("Radio wstrzymane. Wybierz „Wznów ostatnią stację”, aby słuchać ponownie.")
            end
        else
            self:remember_position()
            local paused = shell_call("pause")
            if paused == "paused" then
                UIManager:unschedule(self.start_check)
                UIManager:unschedule(self.position_tick)
            end
            self:notice(paused == "paused" and "Pauza." or "Odtwarzanie zostało zakończone.")
        end
    elseif state == "paused" then
        local resumed = shell_call("resume")
        if resumed == "active" and item and item.path then
            if item.engine == "mplayer" then
                shell_call("volume", self.volume)
            end
            UIManager:scheduleIn(2, self.start_check)
            if item.kind == "books" and item.engine == "mplayer" then
                UIManager:scheduleIn(6, self.position_tick)
            end
        end
        self:notice(resumed == "active" and "Wznowiono odtwarzanie." or "Odtwarzanie zostało zakończone.")
    elseif self.job then
        if self:stop() then self:notice("Łączenie wstrzymane.") end
    elseif self.playlist and self.track_index then
        self:play_file(self.playlist, self.track_index, self.track_group)
    elseif self:find_last_station() then
        self:play_station(self:find_last_station())
    else
        self:notice("Wybierz stację, utwór lub audiobook.")
    end
end

function ILRadio:onKeyPress(key)
    if Device.model ~= "Kindle3" then return end
    for name, direction in pairs(VOLUME_KEYS) do
        if key:match({name}) and self:active_playback() then
            self:rocker_change(direction)
            return true
        end
    end
end

function ILRadio:rocker_change(direction)
    self.volume_keys_seen = true
    self:volume_change(direction * self.volume_step, true)
end

function ILRadio:volume_change(delta, quiet)
    local current = self:active_playback()
    local next_volume = math.max(0, math.min(100, self.volume + delta))
    if next_volume == self.volume then return end
    self.volume = next_volume
    self:save("volume", self.volume)
    if current and current.engine == "mplayer" then
        if self:status() == "paused" then
            if not quiet then self:notice("Głośność po wznowieniu: " .. self.volume .. "%.") end
            return
        end
        local adjusted = shell_call("volume", self.volume) == "adjusted"
        if not quiet or (not adjusted and not current.volume_warning) then
            self:notice(adjusted and ("Głośność " .. self.volume .. "%.")
                or ("Głośność przy następnym uruchomieniu: " .. self.volume .. "%."), 5)
            if not adjusted then current.volume_warning = true end
        end
    elseif shell_call("system-volume", self.volume) == "adjusted" then
        if not quiet then self:notice("Głośność Kindle’a zmieniona. Ustawienie: " .. self.volume .. "%.") end
    elseif current and current.kind == "radio" and self:status() == "active" then
        if not quiet or not current.volume_warning then
            self:notice("Ten silnik nie zmienia głośności w trakcie audycji. "
                .. "Ustawienie " .. self.volume .. "% zastosuje się po ponownym uruchomieniu radia. "
                .. "Wybierz MPlayer w ustawieniach, aby regulować głośność na żywo.", 9)
            current.volume_warning = true
        end
    elseif not quiet or (current and not current.volume_warning) then
        self:notice("Głośność przy następnym uruchomieniu: " .. self.volume .. "%.", 5)
        if current then current.volume_warning = true end
    end
end

function ILRadio:set_sleep_timer(minutes)
    if minutes == 0 then
        shell_call("timer-cancel")
        self:notice("Automatyczne wyłączenie anulowane.")
        return
    end
    local outcome = shell_call("timer-arm", minutes)
    if outcome == "armed:" .. tostring(minutes * 60) then
        self:notice("Radio i odtwarzacz zostaną wyłączone za " .. minutes .. " minut.")
    else
        self:notice("Nie udało się ustawić wyłączenia. Sprawdź diagnostykę audio.", 9)
    end
end

function ILRadio:timer_menu()
    local left = tonumber(shell_call("timer-left"))
    local items = {}
    if left then
        items[#items+1] = {text = "Pozostało około " .. math.ceil(left / 60) .. " min", enabled = false}
    end
    for _, minutes in ipairs({30, 60, 90, 120}) do
        local value = minutes
        items[#items+1] = {text = "Wyłącz za " .. minutes .. " min", keep_menu_open = true,
            callback = function() self:set_sleep_timer(value) end}
    end
    items[#items+1] = {text = "Anuluj wyłączenie", keep_menu_open = true,
        callback = function() self:set_sleep_timer(0) end}
    return items
end

function ILRadio:play_compatible_mp3(station)
    local available = {}
    for _, stream in ipairs(T.variants(station, catalog, self.custom)) do
        if known_http_mp3(stream) and T.hard_allowed(stream) then available[#available + 1] = stream end
    end
    table.sort(available, function(a, b) return a.kbps < b.kbps end)
    local stream = available[1]
    if not stream then
        self:notice("Brak zgodnego strumienia MP3 do 128 kb/s przez HTTP.", 7)
        return
    end
    if shell_call("probe", "mplayer") ~= "mplayer" then
        self:notice("Na Kindle nie wykryto MPlayera. Zainstaluj silnik audio z aplikacji telefonu.", 9)
        return
    end
    local start = function() self:play_station(station, stream.url, true) end
    if T.allowed(self.radio, stream) then
        start()
    else
        UIManager:show(ConfirmBox:new {
            text = "Zgodny wariant MP3 dla „" .. station[1] .. "” ma " .. stream.kbps
                .. " kb/s i przekracza ustawiony limit. Uruchomić jednorazowo?",
            ok_text = "Uruchom MP3",
            cancel_text = "Anuluj",
            ok_callback = start,
        })
    end
end

function ILRadio:play_high_station(station)
    local cap = HIGH_STATION_CAPS[station[1]]
    if not cap then return end
    UIManager:show(ConfirmBox:new {
        text = "„" .. station[1] .. "” nadaje w " .. cap .. " kb/s. To przekracza zwykły "
            .. "limit 128 kb/s i zwiększy wykorzystanie Wi-Fi. "
            .. "Uruchomić tylko tę stację, tylko ten raz?",
        ok_text = "Słuchaj " .. cap .. " kb/s",
        cancel_text = "Anuluj",
        ok_callback = function() self:play_station(station, nil, false, cap) end,
    })
end

function ILRadio:station_menu(category)
    local menu = {}
    for _, station in ipairs(stations) do
        local entry = station
        local name = entry[1]
        local matches_category = entry[2] == category or (category == "Ulubione" and self.favorites[name])
        if matches_category then
            menu[#menu + 1] = {
                text_func = function() return (self.favorites[name] and "★ " or "") .. name end,
                sub_item_table = {
                    { text = "Słuchaj", keep_menu_open = true, callback = function() self:play_station(entry) end },
                    { text = "Spróbuj MP3 (tryb zgodności)", keep_menu_open = true,
                        callback = function() self:play_compatible_mp3(entry) end },
                    {
                        keep_menu_open = true,
                        text_func = function()
                            return self.favorites[name] and "Usuń z ulubionych" or "Dodaj do ulubionych"
                        end,
                        callback = function()
                            self.favorites[name] = not self.favorites[name] or nil
                            self:save("favorites", self.favorites)
                            self:notice(self.favorites[name] and "Dodano do ulubionych." or "Usunięto z ulubionych.")
                        end,
                    },
                },
            }
            local cap = HIGH_STATION_CAPS[name]
            if cap then
                table.insert(menu[#menu].sub_item_table, 2, {
                    text = "Słuchaj mimo limitu (" .. cap .. " kb/s)",
                    keep_menu_open = true,
                    callback = function() self:play_high_station(entry) end,
                })
            end
        end
    end
    if #menu == 0 then
        menu[1] = {
            text = "Brak ulubionych. Wybierz stację w innej kategorii.",
            enabled = false,
        }
    end
    return menu
end
function ILRadio:files_menu(group)
    local files = find_files(AUDIO_ROOTS[group])
    local menu = {}
    if #files == 0 then
        menu[1] = {
            text = "Brak plików. Jak dodać audio?",
            callback = function()
                self:notice("Otwórz w telefonie „Kindle przez Wi-Fi” i dodaj pliki z ekranu Muzyka lub Audiobooki. Potem otwórz tę listę ponownie.", 12)
            end,
        }
    else
        for i, row in ipairs(files) do
            local index = i
            menu[#menu + 1] = {
                text = row.name,
                keep_menu_open = true,
                callback = function() self:play_file(files, index, group) end,
            }
        end
    end
    return menu
end
function ILRadio:quality_options(key, options)
    local list = {}
    for _, row in ipairs(options) do
        local value = row[1] or nil
        list[#list + 1] = {text = row[2], keep_menu_open = true,
            checked_func = function() return self.radio[key] == value end,
            callback = function() self:set_quality(key, value) end}
    end
    return list
end

function ILRadio:check_decode_test()
    local result = shell_call("decode-result")
    self.decode_checks = self.decode_checks + 1
    if result == "" and self.decode_checks <= 7 then
        UIManager:scheduleIn(2, self.decode_tick)
    elseif result:match("^pcm:%d+") then
        self:notice("Silnik zdekodował próbkę do PCM. Odsłuch stacji pozwoli sprawdzić głośnik i połączenie Wi-Fi.", 12)
    else
        self:notice(ERRORS[result] or ERRORS.unconfirmed, 10)
    end
end

function ILRadio:decode_menu()
    local menu = {}
    for _, sample in ipairs({
        {"AAC-LC 48 kb/s · ADTS", "test-lc48.aac"},
        {"HE-AAC v1 32 kb/s · SBR", "test-real-he32.aac"},
        {"HE-AAC v2 24 kb/s · SBR + PS", "test-real-hev224.aac"},
        {"AAC-LC · M4A", "test-lc48.m4a"},
        {"MP3 64 kb/s", "test-mp364.mp3"},
    }) do
        local title, name = sample[1], sample[2]
        local choices = {}
        for _, selected in ipairs(self:available_engines(name == "test-mp364.mp3" and "MP3" or "AAC")) do
            local engine = selected
            choices[#choices + 1] = {text = engine, keep_menu_open = true, callback = function()
                if self.job or self.current then self:notice("Zatrzymaj odtwarzanie przed próbą dekodera."); return end
                local result = shell_call("decode-test", engine, name)
                if result == "testing" then
                    self.decode_checks = 0
                    self:notice("Sprawdzam " .. title .. " · " .. engine, 3)
                    UIManager:unschedule(self.decode_tick)
                    UIManager:scheduleIn(2, self.decode_tick)
                else
                    self:notice(result == "busy" and "Trwa poprzednia próba dekodera." or (ERRORS[result] or ERRORS.unconfirmed))
                end
            end}
        end
        if #choices == 0 then choices[1] = {text = "Brak dostępnego silnika", enabled = false} end
        menu[#menu + 1] = {text = title, sub_item_table = choices}
    end
    menu[#menu + 1] = {text = "Jak czytać wyniki?", callback = function()
        self:notice("PCM potwierdza dekodowanie pliku, a nie dźwięk w głośniku. Próby HE-AAC wymagają wyjścia 44,1 kHz stereo; sam rdzeń AAC nie wystarcza. HTTP i HTTPS sprawdzisz przez wybór stacji.", 14)
    end}
    return menu
end

function ILRadio:radio_menu()
    local menu = {
        { text = "Stacje radiowe", sub_item_table = {
            { text = "Ulubione", sub_item_table_func = function() return self:station_menu("Ulubione") end },
            { text = "Popularne", sub_item_table_func = function() return self:station_menu("Popularne") end },
            { text = "Regionalne", sub_item_table_func = function() return self:station_menu("Regionalne") end },
            { text = "Muzyczne", sub_item_table_func = function() return self:station_menu("Muzyczne") end },
        } },
        { text = "Odtwarzacz MP3 i audiobooki", sub_item_table = {
            { text = "Moja muzyka", sub_item_table_func = function() return self:files_menu("music") end },
            { text = "Moje audiobooki", sub_item_table_func = function() return self:files_menu("books") end },
            { text = "Następny utwór", callback = function() self:next_track(1) end },
            { text = "Poprzedni utwór", callback = function() self:next_track(-1) end },
        } },
        { text = "Stan Radia · v" .. RADIO_VERSION, callback = function()
            local selected = (self.current and self.current.engine) or shell_call("probe", self.engine)
            self:notice("IL Radio v" .. RADIO_VERSION .. "\nSilnik: " .. (selected ~= "" and selected or "brak")
                .. "\nKlawiatura K3: " .. (self.volume_keys_seen and "odebrano klawisz głośności" or "oczekuje na próbę klawiszy")
                .. (volume_input_node and ("\nWejście: " .. volume_input_node .. (volume_input_open and "" or " · nie otwarto")) or "")
                .. "\nOdtwarzanie: " .. self:status()
                .. "\nUwaga: działanie klawiszy i dźwięku wymaga testu na Kindle.", 18)
        end },
        { text = "Co gra?", callback = function() self:playing_info() end },
        { text = "Szczegóły źródła", callback = function() self:playing_info(true) end },
        { text = "Potwierdź, że słychać dźwięk", callback = function() self:confirm_sound() end },
        { text = "Następna stacja", callback = function() self:next_station(1) end },
        { text = "Poprzednia stacja", callback = function() self:next_station(-1) end },
        { text = "Odtwarzaj / pauza", keep_menu_open = true, callback = function() self:toggle_pause() end },
        { text = "Automatyczne wyłączenie", sub_item_table_func = function() return self:timer_menu() end },
        { text = "Wznów ostatnią stację", keep_menu_open = true, callback = function()
            local station = self:find_last_station()
            if station then self:play_station(station)
            else self:notice("Brak ostatnio słuchanej stacji.") end
        end },
        { text = "Zatrzymaj", keep_menu_open = true, callback = function()
            if self:stop() then
                shell_call("timer-cancel")
                self:notice("Odtwarzanie zatrzymane.")
            end
        end },
        { text_func = function() return "Głośność · " .. self.volume .. "%" end, enabled = false },
        { text = "Ustawienia radia", sub_item_table = {
            { text = "Głośność sprzętowa Kindle’a (ALSA)", sub_item_table = {
                { text = "Ustaw poziom sprzętowy 80%", keep_menu_open = true, callback = function()
                    local ok = shell_call("system-volume", "80") == "adjusted"
                    self:notice(ok and "Ustawiono poziom sprzętowy na 80%."
                        or "Ten Kindle nie udostępnia sterowania mikserem ALSA.", 6)
                end },
                { text = "Ustaw poziom sprzętowy 100%", keep_menu_open = true, callback = function()
                    local ok = shell_call("system-volume", "100") == "adjusted"
                    self:notice(ok and "Ustawiono poziom sprzętowy na 100%. Przy zniekształceniach obniż poziom."
                        or "Ten Kindle nie udostępnia sterowania mikserem ALSA.", 8)
                end },
            } },
            { text = "Wzmocnienie dźwięku (MPlayer)", sub_item_table = {
                { text = "Normalne · 110%", checked_func = function() return self.boost == 110 end,
                    keep_menu_open = true, callback = function() self.boost = 110
                        self:save("audio_softvol_max", self.boost)
                        self:notice("Normalne wzmocnienie. Zadziała od następnego uruchomienia utworu lub radia.") end },
                { text = "Głośniejsze · 140%", checked_func = function() return self.boost == 140 end,
                    keep_menu_open = true, callback = function() self.boost = 140
                        self:save("audio_softvol_max", self.boost)
                        self:notice("Wzmocnienie 140%. Uruchom ponownie odtwarzanie. Przy zniekształceniach wybierz 110%.") end },
                { text = "Mocniejsze · 170%", checked_func = function() return self.boost == 170 end,
                    keep_menu_open = true, callback = function() self.boost = 170
                        self:save("audio_softvol_max", self.boost)
                        self:notice("Wzmocnienie 170% może przesterować dźwięk. Zacznij od niskiej głośności i uruchom ponownie odtwarzanie.") end },
            } },
            { text = "Tryb jakości", sub_item_table_func = function() return self:quality_options("mode", T.modes) end },
            { text = "Maksymalny bitrate (twardy limit 128 kb/s)", sub_item_table_func = function()
                local options = {{false, "Według trybu (maks. 128 kb/s)"}}
                for _, value in ipairs(T.limits) do
                    options[#options + 1] = {value, value .. " kb/s"}
                end
                return self:quality_options("limit", options)
            end },
            { text = "Preferowany kodek", sub_item_table_func = function() return self:quality_options("codec", T.codecs) end },
            { text = "Bufor silnika (jeśli obsługiwany)", sub_item_table_func = function()
                return self:quality_options("buffer", {{64, "64 KiB"}, {128, "128 KiB"}, {256, "256 KiB"}})
            end },
            { text = "Najmniejszy transfer", checked_func = function() return self.radio.minimum end,
                callback = function() self:set_quality("minimum", not self.radio.minimum) end },
            {
                text = "Ekranowe przyciski głośności +/−",
                checked_func = function() return self.show_volume_buttons end,
                callback = function()
                    self.show_volume_buttons = not self.show_volume_buttons
                    self:save("show_volume_buttons", self.show_volume_buttons)
                end,
            },
            {
                text = "Krok głośności: 2% / 5% / 10%",
                sub_item_table = {
                    { text = "2%", checked_func = function() return self.volume_step == 2 end, keep_menu_open = true, callback = function() self.volume_step = 2; self:save("volume_step", 2) end },
                    { text = "5%", checked_func = function() return self.volume_step == 5 end, keep_menu_open = true, callback = function() self.volume_step = 5; self:save("volume_step", 5) end },
                    { text = "10%", checked_func = function() return self.volume_step == 10 end, keep_menu_open = true, callback = function() self.volume_step = 10; self:save("volume_step", 10) end },
                },
            },
        } },
        { text = "Silnik audio (dla klawiszy wybierz MPlayer)", sub_item_table_func = function()
            local opts = {}
            for _, option in ipairs(ENGINE_OPTIONS) do
                local engine = option
                opts[#opts+1] = {
                    text_func = function() return (self.engine == engine and "[X] " or "[ ] ") .. (engine == "auto" and "Automatycznie" or engine) end,
                    callback = function()
                        self.engine = engine
                        self:save("engine", engine)
                        self:notice("Wybrano: " .. engine .. ". Ustawienie zadziała przy kolejnym uruchomieniu odtwarzania.")
                    end,
                }
            end
            return opts
        end },
        { text = "Próby kodeków bez internetu", sub_item_table_func = function() return self:decode_menu() end },
        { text = "Diagnostyka audio", callback = function()
            local details = shell_call("diagnose")
            local ok, TextViewer = pcall(require, "ui/widget/textviewer")
            if ok then
                UIManager:show(TextViewer:new {title = "Diagnostyka audio Kindle K3", text = details})
                return
            end
            if #details > 1100 then details = details:sub(1, 1100) end
            self:notice("Diagnostyka Kindle K3\n\n" .. details, 20)
        end },
        { text = "Pomoc", callback = function()
                self:notice("Aby sprawdzić nowe Radio, otwórz Stan Radia · v" .. RADIO_VERSION .. ". "
                .. "Zainstaluj wtyczkę ponownie przez nową aplikację na telefonie, a potem uruchom ponownie KOReader. "
                .. "MPlayer pozwala sterować głośnością bez przerywania radia, jeśli klawisze są wykrywane.", 18)
        end },
    }
    if self.show_volume_buttons then
        table.insert(menu, 9, { text = "Głośniej +", keep_menu_open = true, callback = function() self:volume_change(self.volume_step) end })
        table.insert(menu, 10, { text = "Ciszej -", keep_menu_open = true, callback = function() self:volume_change(-self.volume_step) end })
    end
    for _, item in ipairs(menu) do if item.callback then item.keep_menu_open = true end end
    return menu
end

function ILRadio:addToMainMenu(menu_items)
    menu_items.ilradio_k3 = {
        text = "Radio i audiobooki · v" .. RADIO_VERSION,
        sorting_hint = "more_tools",
        sub_item_table_func = function() return self:radio_menu() end,
    }
end
return ILRadio
