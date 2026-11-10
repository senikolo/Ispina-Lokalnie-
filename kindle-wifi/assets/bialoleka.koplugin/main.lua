local DataStorage = require("datastorage")
local InfoMessage = require("ui/widget/infomessage")
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")

local Weather = WidgetContainer:extend{
    name = "bialoleka",
    is_doc_only = false,
}

function Weather:init()
    self.ui.menu:registerToMainMenu(self)
end

function Weather:addToMainMenu(menu_items)
    menu_items.bialoleka = {
        text = "Pogoda – Białołęka",
        sorting_hint = "tools",
        callback = function() self:showWeather() end,
    }
end

function Weather:showWeather()
    local file = io.open(DataStorage:getSettingsDir() .. "/kindlewifi-bialoleka.txt", "rb")
    local text
    if file then
        text = file:read(4097)
        file:close()
    end
    local stamp, report
    if text and #text <= 4096 then
        stamp, report = text:match("^(%d+)\n(.+)$")
    end
    if stamp and tonumber(stamp) and os.time() - tonumber(stamp) > 6 * 3600 then
        report = "Prognoza ma ponad 6 godzin.\n\n" .. report
    end
    UIManager:show(InfoMessage:new{
        text = report or "Brak zapisanej prognozy. Wyślij ją z aplikacji Kindle przez Wi-Fi.",
    })
end

return Weather
