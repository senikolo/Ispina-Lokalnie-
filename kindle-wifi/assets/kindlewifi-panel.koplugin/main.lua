local DataStorage = require("datastorage")
local InfoMessage = require("ui/widget/infomessage")
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local lfs = require("libs/libkoreader-lfs")

local Panel = WidgetContainer:extend{ name = "kindlewifi_panel", is_doc_only = false }
local settings_dir = DataStorage:getSettingsDir()
local kindle_dir = "/mnt/us/koreader/settings"
if lfs.attributes(kindle_dir, "mode") == "directory" then settings_dir = kindle_dir end
local prefix = settings_dir .. "/kindlewifi-panel-"
local diagnostic_file = prefix .. "diagnostic.txt"
local pathAttributes = lfs.symlinkattributes or lfs.attributes
local snapshot_file, request_file, work_file, result_file =
    prefix .. "state.tsv", prefix .. "request.tsv", prefix .. "work.tsv", prefix .. "result.tsv"
local keys = {
    font = "cre_font", size = "copt_font_size", spacing = "copt_line_spacing",
    margins = "copt_h_page_margins", reflow = "kopt_text_wrap",
    home = "home_dir", sort = "collate", reverse = "reverse_collate",
}
local view_modes = { mosaic_image = true, mosaic_text = true, list_image_meta = true,
    list_only_meta = true, list_image_filename = true }
local fonts
local last_diagnostic
local next_font_attempt = 0
local save_failure

local function safe(value)
    return type(value) == "string" and #value <= 1024 and not value:find("[%c]")
end

local function put(path, contents)
    local temporary = path .. ".part"
    local file, open_error = io.open(temporary, "wb")
    if not file then return nil, open_error end
    local written, write_error = file:write(contents)
    local closed, close_error = file:close()
    if written and closed then
        local renamed, rename_error = os.rename(temporary, path)
        if renamed then return true end
        write_error = rename_error
    end
    os.remove(temporary)
    return nil, write_error or close_error or "błąd zapisu"
end

local function diagnostic(message)
    message = tostring(message):gsub("[%c]", " "):sub(1, 420)
    if message ~= last_diagnostic and put(diagnostic_file, message .. "\n") then
        last_diagnostic = message
    end
end

local function take(path)
    if pathAttributes(path, "mode") ~= "file" then return nil end
    local file = io.open(path, "rb")
    if not file then return nil end
    local contents = file:read(32769)
    file:close()
    if contents and #contents <= 32768 then return contents end
    return nil
end

local function fontList()
    if fonts then return fonts end
    if os.time() < next_font_attempt then return {} end
    local ok, list = pcall(function()
        local cre = require("document/credocument"):engineInit()
        return cre.getFontFaces()
    end)
    if not ok or type(list) ~= "table" or #list == 0 then
        next_font_attempt = os.time() + 10
        return {}
    end
    fonts = {}
    for _, face in ipairs(list) do
        if safe(face) and #face <= 160 then fonts[face] = true end
    end
    return fonts
end

local function coverBrowser(ui)
    if ui.coverbrowser and ui.coverbrowser.setupFileManagerDisplayMode
            and package.loaded.bookinfomanager then
        return ui.coverbrowser, package.loaded.bookinfomanager
    end
end

local function current(ui, key)
    if key == "view" then
        local _, info = coverBrowser(ui)
        if not info then return "!" end
        local ok, value = pcall(info.getSetting, info, "filemanager_display_mode")
        if not ok then return "!" end
        return view_modes[value] and value or "classic"
    end
    local value = G_reader_settings:readSetting(keys[key])
    if key == "margins" and type(value) == "table" then
        value = tostring(value[1]) .. "," .. tostring(value[2])
    elseif type(value) == "number" or type(value) == "boolean" then
        value = tostring(value)
    end
    return safe(value) and value or ""
end

local function decode(ui, key, value)
    if not safe(value) then return false end
    if key == "view" then
        return coverBrowser(ui) ~= nil and (value == "classic" or view_modes[value] == true), value
    end
    if not keys[key] then return false end
    if value == "" then return true, nil end
    if key == "font" then return fontList()[value] == true, value end
    if key == "size" or key == "spacing" then
        local n = value:match("^%d+%.?%d*$") and tonumber(value)
        if not n then return false end
        if key == "size" then return n >= 8 and n <= 70, n end
        return n >= 60 and n <= 220, n
    end
    if key == "margins" then
        local l, r = value:match("^(%d+),(%d+)$")
        l, r = tonumber(l), tonumber(r)
        return l and r and l <= 80 and r <= 80, { l, r }
    end
    if key == "reflow" then return value == "0" or value == "1", tonumber(value) end
    if key == "reverse" then return value == "true" or value == "false", value == "true" end
    if key == "sort" then return (ui.file_chooser.collates or {})[value] ~= nil, value end
    if key == "home" then
        if value ~= "/mnt/us" and not value:match("^/mnt/us/.+") then return false end
        if value:find("//", 1, true) or value:sub(-1) == "/" then return false end
        local path = ""
        for part in value:gmatch("[^/]+") do
            if part == "." or part == ".." then return false end
            path = path .. "/" .. part
            if pathAttributes(path, "mode") ~= "directory" then return false end
        end
        return true, value
    end
    return false
end

function Panel:exportState()
    local lines = {}
    for key in pairs(keys) do
        lines[#lines + 1] = key .. "\t" .. current(self.ui, key)
    end
    lines[#lines + 1] = "view\t" .. current(self.ui, "view")
    for name in pairs(fonts or {}) do
        if #lines >= 140 then break end
        lines[#lines + 1] = "font-option\t" .. name
    end
    for id in pairs(self.ui.file_chooser.collates or {}) do
        if safe(id) then lines[#lines + 1] = "sort-option\t" .. id end
    end
    table.sort(lines)
    local text = "panel-v1\n" .. table.concat(lines, "\n") .. "\n"
    if #text > 32768 then error("Odczyt ustawień przekracza limit rozmiaru.") end
    local now = os.time()
    if text ~= self.last_state or not self.last_state_at or now < self.last_state_at
            or now - self.last_state_at >= 60 or not lfs.attributes(snapshot_file) then
        local ok, err = put(snapshot_file, text)
        if not ok then error("Zapis odczytu: " .. tostring(err)) end
        self.last_state, self.last_state_at = text, now
    end
end

function Panel:applyRequest(text)
    local id = text and text:match("^panel%-v1\n([0-9a-f%-]+)\n")
    if not id or #id ~= 36 then return "", "invalid" end
    local changes = {}
    local start = #"panel-v1\n" + #id + 2
    local body = text:sub(start)
    local count = 0
    for line in body:gmatch("([^\n]+)\n") do
        count = count + #line + 1
        local key, old, new = line:match("^(%l+)\t([^\t]*)\t([^\t]*)$")
        if not key or (key ~= "view" and not keys[key]) or changes[key] then return id, "invalid" end
        local live = current(self.ui, key)
        if live ~= old and live ~= new then return id, "conflict" end
        local valid, decoded = decode(self.ui, key, new)
        if not valid then return id, "invalid" end
        changes[key] = { old = live, new = new, decoded = decoded }
    end
    if count ~= #body or count == 0 then return id, "invalid" end

    local old_settings = {}
    for key, change in pairs(changes) do
        if key ~= "view" then
            old_settings[key] = { value = G_reader_settings:readSetting(keys[key]) }
            G_reader_settings:saveSetting(keys[key], change.decoded)
        end
    end
    local applied, apply_error = pcall(function()
        if next(old_settings) then
            G_reader_settings:flush()
            local persisted = assert(loadfile(G_reader_settings.file))()
            for key in pairs(old_settings) do
                local expected = changes[key].decoded
                local actual = persisted[keys[key]]
                if type(expected) == "table" then
                    if type(actual) ~= "table" or expected[1] ~= actual[1] or expected[2] ~= actual[2] then
                        error("Nie potwierdzono zapisu marginesów.")
                    end
                elseif actual ~= expected then
                    error("Nie potwierdzono zapisu ustawienia.")
                end
            end
        end
        if changes.view and changes.view.old ~= changes.view.new then
            local cover, info = coverBrowser(self.ui)
            local mode = changes.view.decoded
            if mode == "classic" then mode = nil end
            cover:setupFileManagerDisplayMode(mode)
            if current(self.ui, "view") ~= changes.view.new then
                error("Nie potwierdzono trybu widoku.")
            end
            if info.closeDbConnection then info:closeDbConnection() end
        end
    end)
    if not applied then
        for key, previous in pairs(old_settings) do
            G_reader_settings:saveSetting(keys[key], previous.value)
        end
        local restored = pcall(function()
            if next(old_settings) then
                G_reader_settings:flush()
                local persisted = assert(loadfile(G_reader_settings.file))()
                for key, previous in pairs(old_settings) do
                    local actual, expected = persisted[keys[key]], previous.value
                    if type(expected) == "table" then
                        assert(type(actual) == "table" and actual[1] == expected[1] and actual[2] == expected[2])
                    else
                        assert(actual == expected)
                    end
                end
            end
        end)
        if changes.view then
            local cover = coverBrowser(self.ui)
            local previous = changes.view.old
            if previous == "classic" then previous = nil end
            local view_restored = cover and pcall(cover.setupFileManagerDisplayMode, cover, previous)
            restored = restored and view_restored and current(self.ui, "view") == changes.view.old
        end
        save_failure = tostring(apply_error)
        return id, restored and "rolledback" or "failed"
    end
    if self.ui.onRefresh and (changes.sort or changes.reverse or changes.home) then
        pcall(self.ui.onRefresh, self.ui)
    end
    return id, "applied"
end

function Panel:sync()
    if not pathAttributes(work_file) and pathAttributes(request_file, "mode") == "file" then
        os.rename(request_file, work_file)
    end
    if pathAttributes(work_file) then
        local request = take(work_file)
        local request_id = request and request:match("^panel%-v1\n([0-9a-f%-]+)\n") or ""
        local cancel_file = #request_id == 36 and prefix .. "cancel-" .. request_id or nil
        local id, result = request_id, "cancelled"
        save_failure = nil
        if not cancel_file or take(cancel_file) ~= request_id then
            local ok
            ok, id, result = pcall(self.applyRequest, self, request)
            if not ok then
                save_failure = tostring(id)
                id, result = request_id, "failed"
            end
        end
        self:exportState()
        local detail = save_failure and "\t" .. save_failure:gsub("[%c]", " "):sub(1, 420) or ""
        if put(result_file, id .. "\t" .. result .. detail .. "\n") then
            os.remove(work_file)
            if cancel_file then os.remove(cancel_file) end
        end
    else
        self:exportState()
    end
end

function Panel:refreshFromReader(include_fonts)
    local ok, err = pcall(self.exportState, self)
    if not ok then
        diagnostic("Odczyt ustawień: " .. tostring(err))
        return false, err
    end
    if include_fonts then fontList() end
    ok, err = pcall(self.sync, self)
    if not ok then
        diagnostic("Synchronizacja: " .. tostring(err))
        return false, err
    end
    if last_diagnostic then
        os.remove(diagnostic_file)
        last_diagnostic = nil
    end
    return true
end

function Panel:init()
    if self.ui.document then return end
    self.ui.menu:registerToMainMenu(self)
    local function firstRead()
        local ok, err = pcall(self.exportState, self)
        if not ok then diagnostic("Odczyt ustawień: " .. tostring(err)) end
    end
    if self.ui.postInitCallback then
        table.insert(self.ui.postInitCallback, firstRead)
    end
    self.poll = function()
        local fm = require("apps/filemanager/filemanager")
        if fm.instance ~= self.ui then return end
        self:refreshFromReader(true)
        UIManager:scheduleIn(4, self.poll)
    end
    UIManager:scheduleIn(1, self.poll)
end

function Panel:onSuspend()
    if self.poll then UIManager:unschedule(self.poll) end
end

function Panel:onResume()
    if self.poll then
        UIManager:unschedule(self.poll)
        UIManager:scheduleIn(1, self.poll)
    end
end

function Panel:onCloseWidget()
    if self.poll then UIManager:unschedule(self.poll) end
end

function Panel:addToMainMenu(items)
    items.kindlewifi_panel = {
        text = "Ustawienia z telefonu",
        sorting_hint = "tools",
        callback = function()
            next_font_attempt = 0
            local ok, err = self:refreshFromReader(true)
            UIManager:show(InfoMessage:new{ text = ok and "Odczyt ustawień dla telefonu jest gotowy."
                or "Nie udało się odczytać ustawień KOReadera.\n\n" .. tostring(err) })
        end,
    }
end

return Panel
