local plugin, tmp = assert(arg[1]), assert(arg[2])
local fs = require("lfs")
local timer
local menuItems = {}
local st = { file = tmp .. "/settings.reader.lua", data = { cre_font = "Noto Serif", copt_font_size = 22.5 } }
function st:readSetting(key) return self.data[key] end
function st:saveSetting(key, value) self.data[key] = value; return self end
local function dump(value)
    if type(value) == "table" then
        local parts = {}
        for k, v in pairs(value) do parts[#parts + 1] = "[" .. dump(k) .. "]=" .. dump(v) end
        return "{" .. table.concat(parts, ",") .. "}"
    end
    return type(value) == "string" and string.format("%q", value) or tostring(value)
end
function st:flush()
    if self.block_write then return end
    local f = assert(io.open(self.file, "wb"))
    assert(f:write("return ", dump(self.data)))
    assert(f:close())
end
G_reader_settings = st
package.loaded.datastorage = { getSettingsDir = function() return tmp end }
package.loaded["ui/widget/infomessage"] = { new = function(_, obj) return obj end }
package.loaded["ui/uimanager"] = { scheduleIn = function(_, delay, fn) timer = fn end,
    unschedule = function() timer = nil end, show = function() end }
package.loaded["ui/widget/container/widgetcontainer"] = { extend = function(_, obj) return obj end }
package.loaded["libs/libkoreader-lfs"] = fs
local font_attempts = 0
package.loaded["document/credocument"] = { engineInit = function()
    font_attempts = font_attempts + 1
    if font_attempts == 1 then error("transient font error") end
    return { getFontFaces = function() return {"Noto Serif", "DejaVu Sans"} end }
end }
local ui = { menu = { registerToMainMenu = function(_, p) p:addToMainMenu(menuItems) end },
    file_chooser = { collates = { strcoll = true } }, postInitCallback = {} }
package.loaded["apps/filemanager/filemanager"] = { instance = ui }
local p = assert(loadfile(plugin))()
p.ui = ui
p:init()
ui.postInitCallback[1]()
local prefix = tmp .. "/kindlewifi-panel-"
local function read(path)
    local f = assert(io.open(path, "rb")); local s = f:read("*a"); f:close(); return s
end
assert(read(prefix .. "state.tsv"):find("size\t22.5\n", 1, true))
timer()
assert(font_attempts == 1)
assert(not read(prefix .. "state.tsv"):find("font%-option"))
menuItems.kindlewifi_panel.callback()
assert(font_attempts == 2)
assert(read(prefix .. "state.tsv"):find("font-option\tDejaVu Sans\n", 1, true))
local sequence = 0
local function request(lines, expect, use_menu, cancel)
    sequence = sequence + 1
    local id = string.format("11111111-2222-3333-4444-%012d", sequence)
    local file = assert(io.open(prefix .. "request.tsv", "wb"))
    assert(file:write("panel-v1\n", id, "\n", lines))
    file:close()
    if cancel then
        local marker = assert(io.open(prefix .. "cancel-" .. id, "wb"))
        assert(marker:write(id))
        marker:close()
    end
    if use_menu then menuItems.kindlewifi_panel.callback() else timer() end
    local output = read(prefix .. "result.tsv")
    assert(output:match("^([0-9a-f%-]+)\t" .. expect .. "[\t\n]") == id, output)
    assert(not fs.attributes(prefix .. "work.tsv") and not fs.attributes(prefix .. "request.tsv"))
    if cancel then assert(not fs.attributes(prefix .. "cancel-" .. id)) end
    return output
end
request("font\tNoto Serif\tDejaVu Sans\nsize\t22.5\t40\nspacing\t\t140\nmargins\t\t20,30\nreflow\t\t1\n", "applied")
local saved = assert(loadfile(st.file))()
assert(saved.cre_font == "DejaVu Sans" and saved.copt_font_size == 40)
assert(saved.copt_line_spacing == 140 and saved.kopt_text_wrap == 1)
assert(saved.copt_h_page_margins[1] == 20 and saved.copt_h_page_margins[2] == 30)
assert(read(prefix .. "state.tsv"):find("margins\t20,30\n", 1, true))
request("size\t22.5\t35\n", "conflict")
request("size\t40\t42\nmargins\t20,30\t81,5\n", "invalid")
request("font\tDejaVu Sans\tMissing Font\n", "invalid")
assert(st.data.copt_font_size == 40)
st.block_write = true
local rollback = request("size\t40\t50\n", "rolledback")
assert(rollback:find("Nie potwierdzono zapisu", 1, true))
assert(st.data.copt_font_size == 40 and assert(loadfile(st.file))().copt_font_size == 40)
st.block_write = false
request("size\t40\t60\n", "cancelled", false, true)
assert(st.data.copt_font_size == 40)
local apply = p.applyRequest
p.applyRequest = function() error("forced diagnostic") end
assert(request("size\t40\t60\n", "failed"):find("forced diagnostic", 1, true))
p.applyRequest = apply
request("font\tDejaVu Sans\t\nsize\t40\t\nspacing\t140\t\nmargins\t20,30\t\nreflow\t1\t\n", "applied", true)
saved = assert(loadfile(st.file))()
assert(next(saved) == nil)
print("PASS: Lua panel, font retry, save and reset, conflicting or invalid data, confirmed rollback, persistent error detail, cancellation")
