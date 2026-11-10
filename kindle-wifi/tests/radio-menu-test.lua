local source = arg[1]
local legacy = arg[2] == "legacy"
package.loaded["libs/libkoreader-lfs"] = nil
package.loaded["lfs"] = nil
package.preload["libs/libkoreader-lfs"] = function()
    if legacy then error("Modern filesystem module is unavailable on older KOReader") end
    return {}
end
package.preload["lfs"] = function()
    if not legacy then error("Legacy filesystem module is unavailable on modern KOReader") end
    return {}
end
local data, scheduled, calls, messages = { engine = "mplayer" }, {}, {}, {}
local volume_opened
local input = { event_map = {}, event_map_adapter = not legacy and {} or nil }
local input_handlers = {}
input.open = function(_, path) volume_opened = path end
package.preload["device"] = function() return { model = "Kindle3", input = input } end
local state, last_position = "stopped", "56"
local evidence = "pending"
local no_transport = false
package.preload["ui/widget/container/widgetcontainer"] = function()
    return { extend = function(_, class) return class end }
end
package.preload["ui/uimanager"] = function()
    return {
        event_handlers = input_handlers,
        show = function(_, note) messages[#messages+1] = note.kind == "radio-error" and note or note.text end,
        scheduleIn = function(_, delay, fn) scheduled[fn] = delay end,
        unschedule = function(_, fn) scheduled[fn] = nil end,
    }
end
package.preload["ui/widget/infomessage"] = function() return { new = function(_, item) return item end } end
package.preload["ui/widget/confirmbox"] = function() return { new = function(_, item) item.kind = "radio-error"; return item end } end
package.preload["luasettings"] = function()
    return { open = function()
        return {
            readSetting = function(_, key) return data[key] end,
            saveSetting = function(_, key, value) data[key] = value end,
            flush = function() end,
        }
    end }
end
package.preload["datastorage"] = function() return { getSettingsDir = function() return "/tmp" end } end
io.popen = function(command)
    calls[#calls + 1] = command
    local response = ""
    if command:find("'probe-start'", 1, true) then
        if no_transport then response = "unavailable"
        else
            response = "/tmp/probe-228-1"
            local url = command:match("'probe%-start' '([^']+)'")
            local sample = assert(io.open(source .. "/test-lc48.aac", "rb"))
            local bytes = sample:read("*a"); sample:close()
            for suffix, contents in pairs({[".done"]="0", [".meta"]="200\n" .. url .. "\ncurl\n",
                [".headers"]="HTTP/1.1 200 OK\nicy-br: 48\n\n", [".sample"]=bytes}) do
                local f = assert(io.open(response .. suffix, "wb")); f:write(contents); f:close()
            end
        end
    elseif command:find("'evidence'", 1, true) then response = evidence
    elseif command:find("'probe' 'mplayer'", 1, true) then response = "mplayer"
    elseif command:find("'start'", 1, true) then response, state = "starting:mplayer", "active"
    elseif command:find("'stop'", 1, true) then response, state = "stopped", "stopped"
    elseif command:find("'pause'", 1, true) then response, state = "paused", "paused"
    elseif command:find("'resume'", 1, true) then response, state = "active", "active"
    elseif command:find("'status'", 1, true) then response = state
    elseif command:find("'volume'", 1, true) then response = "adjusted"
    elseif command:find("'position'", 1, true) then response = last_position
    elseif command:find("'title'", 1, true) then response = "Próbny utwór" end
    return { read = function() return response end, close = function() end }
end
local plugin = dofile(source .. "/main.lua")
local registered
plugin.ui = { menu = { registerToMainMenu = function(_, instance) registered = instance end } }
plugin:init()
assert(registered == plugin)
assert(volume_opened == "/dev/input/event2")
local function rocker(code, value)
    local key = input.event_map[code]
    if legacy then
        if value == 1 then
            return plugin:onKeyPress({ match = function(_, keys) return keys[1] == key end })
        end
    else
        -- Mirror the adapter branch of KOReader input and UIManager's named event dispatch.
        local event = assert(input.event_map_adapter[key], "Missing hardware key adapter")({value = value})
        if event then
            assert(input_handlers[event], "Missing global hardware key handler")()
            return true
        end
    end
end
assert(not rocker(115, 1))
local menu = {}
plugin:addToMainMenu(menu)
assert(menu.ilradio_k3 and menu.ilradio_k3.sorting_hint == "more_tools")
local catalog = dofile(source .. "/stations.lua")
assert(#catalog == 42)
local function menu_item(items, title)
    for _, item in ipairs(items) do
        if item.text == title then return item end
    end
end
local first = plugin:station_menu("Popularne")[1]
assert(first.text_func() == "RMF FM")
first.sub_item_table[2].callback()
assert(data.favorites["RMF FM"] and #plugin:station_menu("Ulubione") == 1)
first.sub_item_table[1].callback()
local function settle()
    local steps = 0
    while plugin.job and not plugin.current and steps < 40 do
        steps = steps + 1
        if plugin.job.probe then plugin.probe_tick() else plugin.retry_tick() end
    end
    assert(steps < 40)
end
settle()
assert(plugin.current.name == "RMF FM" and plugin.current.stream.kbps == 48)
assert(plugin.current.stream.codec:find("AAC"))
assert(data.last_station_name == "RMF FM" and not data.radio_last_working)
assert(rocker(115, 1) and data.volume == 75)
assert(not rocker(115, 0) and data.volume == 75)
if not legacy then
    assert(rocker(115, 2) and data.volume == 80)
    assert(rocker(114, 2) and data.volume == 75)
end
assert(rocker(114, 1) and data.volume == 70)
assert(calls[#calls]:find("'volume' '70'", 1, true))
plugin:playing_info()
assert(messages[#messages]:find("dźwięk niepotwierdzony", 1, true))
assert(messages[#messages]:find("Próbny utwór", 1, true))
evidence = "decoded"
plugin.radio_tick()
assert(plugin.current.evidence == "decoded" and not data.radio_last_working)
plugin:confirm_sound()
assert(data.radio_last_working["RMF FM"] == plugin.current.stream.url)

local options = menu_item(plugin:radio_menu(), "Ustawienia radia").sub_item_table
local volume = menu_item(options, "Ekranowe przyciski głośności +/−")
assert(volume.checked_func())
volume.callback()
assert(not volume.checked_func() and not data.show_volume_buttons)
assert(not menu_item(plugin:radio_menu(), "Głośniej +"))
assert(rocker(115, 1) and data.volume == 75)

plugin:set_quality("limit", 32)
plugin:play_station(catalog[1])
settle()
assert(not plugin.current and not plugin.job)
local permission = messages[#messages]
assert(permission.kind == "radio-error" and permission.ok_text == "Uruchom ten wariant")
permission.ok_callback()
settle()
assert(plugin.job.approved and plugin.current.stream.kbps == 48)
plugin:stop()
assert(not scheduled[plugin.retry_tick] and not scheduled[plugin.probe_tick])
assert(not rocker(115, 1) and data.volume == 75)
assert(plugin:play("file:///mnt/us/music/test.mp3", "Próba", "music", "/mnt/us/music/test.mp3"))
assert(rocker(114, 1) and data.volume == 70)
plugin:onCloseWidget()
assert(not rocker(114, 1) and data.volume == 70)
plugin:set_quality("limit", nil)

-- Fake a candidate rejection until the bounded queue is exhausted.
plugin:play_station(catalog[1])
evidence = "failed:tls"
local steps = 0
while plugin.job and steps < 20 do
    steps = steps + 1
    if plugin.current then plugin.radio_tick() elseif plugin.job.probe then plugin.probe_tick() else plugin.retry_tick() end
end
assert(not plugin.job and steps < 20)
assert(#plugin:station_menu("Polskie Radio") >= 6)
plugin:next_station(1)
assert(plugin.last_station_name == catalog[2][1])
plugin:stop()
no_transport = true
plugin:play_station(catalog[1])
settle()
assert(not plugin.current, "Finite bitrate must not trust catalog without a fresh sample")
plugin:stop()
for _, suffix in ipairs({".done", ".meta", ".headers", ".sample"}) do os.remove("/tmp/probe-228-1" .. suffix) end
print((legacy and "Legacy" or "Modern") .. " filesystem module: PASS IL Radio menu")
