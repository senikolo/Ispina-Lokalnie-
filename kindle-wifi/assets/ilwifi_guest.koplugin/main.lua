-- IL Wi-Fi Gosc (TEST) for Kindle Keyboard 3 / KOReader.
-- Detection only: NEVER submits a portal form or stores an e-mail/password.
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local UIManager = require("ui/uimanager")
local InfoMessage = require("ui/widget/infomessage")

local Guest = WidgetContainer:extend {name="ilwifi_guest", is_doc_only=false}
local VERSION = "1.0.0"
local REPORT = "/mnt/us/koreader/settings/ilwifi-guest-last.txt"
local TARGETS = {
    {url="http://connectivitycheck.gstatic.com/generate_204", code=204, title="Kontrola 204"},
    {url="http://www.msftconnecttest.com/connecttest.txt", code=200,
        body="Microsoft Connect Test", title="Kontrola tekstowa"},
}

local function trim(s)
    return (tostring(s or ""):gsub("^%s+",""):gsub("%s+$",""))
end

local function scrub(s, limit)
    return trim(s):gsub("[%c]", " "):sub(1, limit or 160)
end

local function read(path, limit)
    local f = io.open(path, "rb")
    if not f then return "" end
    local v = f:read(limit or 4096) or ""
    f:close()
    return v
end

local function quote(s)
    return "'" .. tostring(s):gsub("'", "'\"'\"'") .. "'"
end

local function available(program)
    local ok = os.execute("command -v " .. program .. " >/dev/null 2>&1")
    return ok == true or ok == 0
end

-- Never display query strings or fragments (captive portals may put tokens there).
local function safe_url(value)
    if type(value) ~= "string" or value:find("[%c%s]") or #value > 2048 then return nil end
    if not value:match("^https?://[%w%-%._:%[%]]+") then return nil end
    local bare = value:match("^([^%?#]+)")
    return bare and bare:sub(1, 180) or nil
end

local function location(headers)
    if type(headers) ~= "table" then return nil end
    for key, value in pairs(headers) do
        if tostring(key):lower() == "location" then return safe_url(value) end
    end
    return nil
end

local function from_socket(url)
    local yes, http = pcall(require, "socket.http")
    local yes_ltn, ltn12 = pcall(require, "ltn12")
    if not yes or not yes_ltn or not http.request or not ltn12.sink then
        return nil, "brak LuaSocket"
    end
    http.TIMEOUT = 4
    local collected, bytes = {}, 0
    local sink = function(chunk)
        if chunk and bytes < 4096 then
            local take = chunk:sub(1, 4096 - bytes)
            collected[#collected + 1] = take
            bytes = bytes + #take
        end
        -- Do not retain a large portal HTML page.
        return 1
    end
    local ok, success, code, headers = pcall(http.request, {
        url=url, method="GET", redirect=false, sink=sink,
        headers={["User-Agent"]="Kindle-IL-WiFi-Guest/1.0", ["Connection"]="close"},
    })
    if not ok then return nil, scrub(success, 80) end
    if not success then return nil, scrub(code, 80) end
    return {code=tonumber(code), body=table.concat(collected),
        portal=location(headers), via="LuaSocket"}
end

local function from_command(url)
    local tmp = "/tmp/ilwifi-guest-" .. tostring(os.time()) .. "-" .. tostring(math.random(10000,99999))
    local head, body = tmp .. ".h", tmp .. ".b"
    local command
    if available("curl") then
        command = "umask 077; curl -sS --connect-timeout 2 --max-time 4"
            .. " --max-redirs 0 --range 0-4095 -D " .. quote(head)
            .. " -o " .. quote(body) .. " " .. quote(url) .. " 2>/dev/null"
    elseif available("wget") then
        -- Old BusyBox wget may follow redirects; resulting HTML is still
        -- classified as an untrusted portal, never automatically submitted.
        command = "umask 077; wget -S -T 4 -t 1 -O " .. quote(body)
            .. " " .. quote(url) .. " 2>" .. quote(head)
    else
        return nil, "brak klienta HTTP"
    end
    os.execute(command)
    local raw_headers, raw_body = read(head, 8192), read(body, 4096)
    os.remove(head)
    os.remove(body)
    local status
    for num in raw_headers:gmatch("HTTP/%d%.%d%s+(%d%d%d)") do status = tonumber(num) end
    -- Some embedded gateways return ICY and non-standard old HTTP statuses.
    if not status then status = tonumber(raw_headers:match("ICY%s+(%d%d%d)")) end
    local portal
    for line in raw_headers:gmatch("[^\r\n]+") do
        local val = line:match("^[Ll][Oo][Cc][Aa][Tt][Ii][Oo][Nn]:%s*(%S+)")
        if val then portal = safe_url(val) end
    end
    if not status then return nil, "HTTP niedostępne" end
    return {code=status, body=raw_body, portal=portal, via="curl/wget"}
end

local function http_probe(url)
    local answer, error_socket = from_socket(url)
    if answer then return answer end
    local fallback, error_cmd = from_command(url)
    if fallback then return fallback end
    return {code=nil, body="", error=error_cmd or error_socket, via="brak"}
end

local function wifi_state()
    local state = trim(read("/sys/class/net/wlan0/operstate", 80)):lower()
    local route = read("/proc/net/route", 8192)
    local default_route = false
    for line in route:gmatch("[^\n]+") do
        local interface, destination = line:match("^(%S+)%s+(%S+)")
        if interface == "wlan0" and destination == "00000000" then default_route = true end
    end
    if state == "down" then return "rozłączone", default_route end
    if state == "up" or state == "dormant" then
        return default_route and "połączone" or "Wi-Fi bez trasy", default_route
    end
    return default_route and "połączone" or "nieustalone", default_route
end

local function assess(results)
    local successful, portal, errors, info = 0, 0, 0, {}
    local host
    for i, item in ipairs(TARGETS) do
        local answer = results[i] or {}
        local matches = answer.code == item.code
            and (not item.body or trim(answer.body):find(item.body, 1, true))
        if matches then successful = successful + 1
        elseif not answer.code then errors = errors + 1
        elseif answer.code >= 300 and answer.code < 400
            or answer.code == 200 or answer.code == 511 or answer.code == 403 then
            portal = portal + 1
        end
        if answer.portal then host = answer.portal end
        info[#info+1] = item.title .. ": " .. (answer.code and tostring(answer.code) or "błąd")
            .. (matches and " (OK)" or "")
    end
    local result
    if successful > 0 and portal == 0 then result = "online"
    elseif portal > 0 and successful == 0 then result = "portal"
    elseif portal > 0 and successful > 0 then result = "mixed"
    else result = "offline" end
    return {state=result, portal_url=host, info=table.concat(info, "\n"), errors=errors}
end

local function report_text(value, network)
    local message = "IL Wi-Fi Gość · test " .. VERSION .. "\n"
        .. "Wi-Fi: " .. network .. "\n"
    if value.state == "online" then
        message = message .. "Internet: dostępny\nPortal: niewymagany"
    elseif value.state == "portal" then
        message = message .. "Internet: wymaga autoryzacji\nPortal: prawdopodobny"
    elseif value.state == "mixed" then
        message = message .. "Internet: wynik niejednoznaczny\nPortal: możliwe przekierowanie"
    else
        message = message .. "Internet: niepotwierdzony\nPortal: nie wykryto"
    end
    if value.portal_url then message = message .. "\nAdres portalu (bez danych sesji): " .. value.portal_url end
    message = message .. "\n" .. value.info
    return message
end

local function persist(text)
    -- No e-mail, password, cookies or full tokenized portal URLs are saved.
    local f = io.open(REPORT, "wb")
    if f then f:write(text:sub(1, 1900), "\n"); f:close() end
end

local function notice(text, seconds)
    UIManager:show(InfoMessage:new {text=text, timeout=seconds or 8})
end

function Guest:init()
    self.ui.menu:registerToMainMenu(self)
end

function Guest:check()
    if self.running then notice("Sprawdzanie sieci już trwa.", 5); return end
    self.running = true
    notice("Sprawdzam internet z Kindle'a. Może to potrwać kilkanaście sekund.", 3)
    UIManager:scheduleIn(0.2, function()
        local ok, result = pcall(function()
            local wifi = wifi_state()
            local answers = {}
            for i, item in ipairs(TARGETS) do answers[i] = http_probe(item.url) end
            local assessed = assess(answers)
            local message = report_text(assessed, wifi)
            persist(message)
            return message .. "\n\nAby zalogować się w portalu, otwórz przeglądarkę Kindle i stronę HTTP (np. neverssl.com)."
        end)
        self.running = false
        notice(ok and result or "Nie ukończono testu sieci. Uruchom ponownie próbę.", 25)
    end)
end

function Guest:last_report()
    local value = read(REPORT, 2000)
    if value == "" then
        notice("Brak raportu. Najpierw wybierz „Sprawdź Wi-Fi i portal”.", 9)
        return
    end
    local ok, TextViewer = pcall(require, "ui/widget/textviewer")
    if ok and TextViewer and TextViewer.new then
        UIManager:show(TextViewer:new {title="IL Wi-Fi Gość · diagnostyka", text=value})
    else
        notice(value, 25)
    end
end

function Guest:addToMainMenu(menu)
    menu.ilwifi_guest = {
        text="IL Wi-Fi Gość · TEST",
        sorting_hint="more_tools",
        sub_item_table={
            {text="Sprawdź Wi-Fi i portal", keep_menu_open=true,
                callback=function() self:check() end},
            {text="Ostatni raport", keep_menu_open=true,
                callback=function() self:last_report() end},
            {text="Jak połączyć Kindle w pociągu?", callback=function()
                notice("1. W ustawieniach Kindle'a wybierz sieć Wi-Fi pociągu (2,4 GHz).\n"
                    .. "2. Otwórz IL Wi-Fi Gość i sprawdź połączenie.\n"
                    .. "3. Jeżeli jest portal, otwórz przeglądarkę Kindle i stronę http://neverssl.com/.\n"
                    .. "4. Samodzielnie zaakceptuj regulamin lub wpisz e-mail.\n"
                    .. "5. Wróć do KOReadera i ponów test.\n\n"
                    .. "Wersja TEST wykrywa portal, ale nie loguje automatycznie.", 30)
            end},
        },
    }
end

Guest.test = {assess=assess, safe_url=safe_url, report_text=report_text,
    http_probe=http_probe, wifi_state=wifi_state, targets=TARGETS}
return Guest
