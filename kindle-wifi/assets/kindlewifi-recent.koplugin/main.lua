local DataStorage = require("datastorage")
local InfoMessage = require("ui/widget/infomessage")
local Menu = require("ui/widget/menu")
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local lfs = require("libs/libkoreader-lfs")

local Recent = WidgetContainer:extend{
    name = "kindlewifi_recent",
    is_doc_only = false,
}

local function info(text)
    UIManager:show(InfoMessage:new{ text = text })
end

function Recent:init()
    if not self.ui.document then
        self.ui.menu:registerToMainMenu(self)
    end
end

function Recent:addToMainMenu(menu_items)
    menu_items.kindlewifi_recent = {
        text = "Ostatnio przesłane",
        sorting_hint = "tools",
        callback = function() self:showRecent() end,
    }
end

local function recentBooks()
    local directory = DataStorage:getSettingsDir() .. "/kindlewifi-recent"
    local books = {}
    if lfs.symlinkattributes(directory, "mode") ~= "directory" then return books end
    for entry in lfs.dir(directory) do
        local marker = directory .. "/" .. entry
        if #entry == 68 and entry:match("^%x+%.txt$") and lfs.symlinkattributes(marker, "mode") == "file" then
            local file = io.open(marker, "rb")
            if file then
                local text = file:read(513)
                file:close()
                if text and #text <= 512 then
                    local stamp, name = text:match("^(%d+)\n([^/%c]+)$")
                    if stamp and not name:match("^%.") and not name:find("\\", 1, true) then
                        local path = "/mnt/us/documents/" .. name
                        if lfs.symlinkattributes(path, "mode") == "file" then
                            table.insert(books, { text = name, path = path, stamp = tonumber(stamp) })
                        end
                    end
                end
            end
        end
    end
    table.sort(books, function(a, b)
        if a.stamp == b.stamp then return a.text < b.text end
        return a.stamp > b.stamp
    end)
    return books
end

function Recent:showRecent()
    local books = recentBooks()
    if #books == 0 then
        info("Brak ostatnio przesłanych książek. Wyślij plik z aplikacji Kindle przez Wi-Fi.")
        return
    end
    local menu = Menu:new{
        title = "Ostatnio przesłane",
        item_table = books,
        is_borderless = true,
        is_popout = false,
        onMenuSelect = function(widget, item)
            if lfs.symlinkattributes(item.path, "mode") ~= "file" then
                info("Książka została przeniesiona lub usunięta.")
                return true
            end
            UIManager:close(widget)
            require("apps/reader/readerui"):showReader(item.path)
            return true
        end,
    }
    UIManager:show(menu)
end

return Recent
