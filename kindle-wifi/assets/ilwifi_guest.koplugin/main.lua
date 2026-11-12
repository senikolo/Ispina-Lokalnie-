-- IL Wi-Fi Gosc (TEST) for Kindle Keyboard 3 / KOReader.
-- Detection only: NEVER submits a portal form or stores an e-mail/password.
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local UIManager = require("ui/uimanager")
local InfoMessage = require("ui/widget/infomessage")

local Guest = WidgetContainer:extend {name="ilwifi_guest", is_doc_only=false}
local VERSION = "1.1.0"
local REPORT = "/mnt/us/koreader/settings/ilwifi-guest-last.txt"
local TARGETS = {
    {url="http://connectivitycheck.gstatic.com/generate_204", code=204, title="Kontrola 204"},
    {url="http://www.msftconnecttest.com/connecttest.txt", code=200,
        body="Microsoft Connect Test", title="Kontrola tekstowa"},
}
-- Different provider: Mozilla's official 204 check. Used only if the second
-- endpoint does not give its expected response (e.g. DNS or timeout).
local BACKUP = {url="http://firefox-portal-detection.com/generate_204",
    code=204, title="Serwer zapasowy"}

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

local function failure_reason(raw, exit_code)
    local value = tostring(raw or ""):lower()
    if value == "przekroczono czas" or value == "odmowa połączenia"
        or value == "brak trasy sieciowej" or value == "brak odpowiedzi HTTP"
        or value == "brak narzędzia HTTP" then return value end
    if value:find("resolve", 1, true) or value:find("name or service", 1, true)
        or value:find("host not found", 1, true) or value:find("dns", 1, true)
        or tonumber(exit_code) == 6 then return "brak DNS" end
    if value:find("timeout", 1, true) or value:find("timed out", 1, true)
        or tonumber(exit_code) == 28 then return "przekroczono czas" end
    if value:find("refused", 1, true) or tonumber(exit_code) == 7 then
        return "odmowa połączenia"
    end
    if value:find("unreachable", 1, true) or value:find("no route", 1, true) then
        return "brak trasy sieciowej"
    end
    if value:find("brak klienta", 1, true) then return "brak narzędzia HTTP" end
    return "brak odpowiedzi HTTP"
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
        -- No large portal HTML is retained.
        return 1
    end
    local ok, success, code, headers = pcall(http.request, {
        url=url, method="GET", redirect=false, sink=sink,
        headers={["User-Agent"]="Kindle-IL-WiFi-Guest/1.1", ["Connection"]="close"},
    })
    if not ok then return nil, failure_reason(success) end
    if not success then return nil, failure_reason(code) end
    return {code=tonumber(code), body=table.concat(collected),
        portal=location(headers), via="LuaSocket"}
end

local function from_command(url)
    local tmp = "/tmp/ilwifi-guest-" .. tostring(os.time()) .. "-"
        .. tostring(math.random(10000,99999))
    local head, body, err, rc = tmp .. ".h", tmp .. ".b", tmp .. ".e", tmp .. ".rc"
    local command
    if available("curl") then
        command = "umask 077; curl -sS --connect-timeout 2 --max-time 4"
            .. " --max-redirs 0 --range 0-4095 -D " .. quote(head)
            .. " -o " .. quote(body) .. " " .. quote(url)
            .. " 2>" .. quote(err) .. "; echo $? > " .. quote(rc)
    elseif available("wget") then
        -- BusyBox wget may follow redirects. No portal form is submitted.
        command = "umask 077; wget -S -T 4 -t 1 -O " .. quote(body)
            .. " " .. quote(url) .. " 2>" .. quote(err)
            .. "; echo $? > " .. quote(rc)
    else
        return nil, "brak narzędzia HTTP"
    end
    os.execute(command)
    local raw_headers = read(head, 8192)
    local raw_body = read(body, 4096)
    local raw_errors = read(err, 2048)
    local exit_code = tonumber(trim(read(rc, 16)))
    os.remove(head)
    os.remove(body)
    os.remove(err)
    os.remove(rc)
    -- wget -S reports its HTTP headers on stderr, whereas curl uses -D.
    if raw_headers == "" then raw_headers = raw_errors end
    local status
    for num in raw_headers:gmatch("HTTP/%d%.%d%s+(%d%d%d)") do status = tonumber(num) end
    if not status then status = tonumber(raw_headers:match("ICY%s+(%d%d%d)")) end
    local portal
    for line in raw_headers:gmatch("[^\r\n]+") do
        local value = line:match("^%s*[Ll][Oo][Cc][Aa][Tt][Ii][Oo][Nn]:%s*(%S+)")
        if value then portal = safe_url(value) end
    end
    if not status then return nil, failure_reason(raw_errors, exit_code) end
    return {code=status, body=raw_body, portal=portal, via="curl/wget"}
end

local function http_probe(url)
    local answer, error_socket = from_socket(url)
    if answer then return answer end
    local fallback, error_command = from_command(url)
    if fallback then return fallback end
    -- Retain a specific LuaSocket reason when shell utilities are unavailable.
    local reason = error_command
    if error_command == "brak narzędzia HTTP" and error_socket ~= "brak LuaSocket"
        then reason = error_socket end
    return {code=nil, body="", error=reason or error_socket, via="brak"}
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

local function matches(item, answer)
    if not answer or answer.code ~= item.code then return false end
    if item.body then return trim(answer.body) == item.body end
    -- HTTP 204 must be empty to rule out a portal injecting a fake response.
    if item.code == 204 then return trim(answer.body) == "" end
    return true
end

local function assess(results)
    local successful, portal, errors, unexpected, info = 0, 0, 0, 0, {}
    local portal_url
    for i = 1, #TARGETS + (results[3] and 1 or 0) do
        local item = (i == 3) and BACKUP or TARGETS[i]
        local answer = results[i] or {}
        local good = matches(item, answer)
        local suffix
        if good then
            successful = successful + 1
            suffix = tostring(answer.code) .. " (OK)"
        elseif not answer.code then
            errors = errors + 1
            suffix = failure_reason(answer.error)
        elseif (answer.code >= 300 and answer.code < 400)
            or answer.code == 511
            or (answer.code == 200 and not good) then
            portal = portal + 1
            suffix = tostring(answer.code) .. " (możliwy portal)"
        else
            unexpected = unexpected + 1
            suffix = tostring(answer.code) .. " (odpowiedź nieoczekiwana)"
        end
        if answer.portal then portal_url = answer.portal end
        info[#info + 1] = item.title .. ": " .. suffix
    end
    local state
    if successful > 0 and portal == 0 then state = "online"
    elseif portal > 0 and successful == 0 then state = "portal"
    elseif portal > 0 and successful > 0 then state = "mixed"
    else state = "offline" end
    return {state=state, portal_url=portal_url, info=table.concat(info, "\n"),
        errors=errors, unexpected=unexpected, successful=successful}
end

local function report_text(value, network)
    local message = "IL Wi-Fi Gość · test " .. VERSION .. "\n"
        .. "Wi-Fi: " .. network .. "\n"
    if value.state == "online" then
        message = message .. "Internet: dostępny\nPortal: niewymagany"
    elseif value.state == "portal" then
        message = message .. "Internet: wymaga sprawdzenia\nPortal: prawdopodobny"
    elseif value.state == "mixed" then
        message = message .. "Internet: częściowo dostępny\nPortal: możliwy"
    else
        message = message .. "Internet: niepotwierdzony\nPortal: nie wykryto"
    end
    if value.portal_url then
        message = message .. "\nAdres portalu (bez parametrów): " .. value.portal_url
    end
    message = message .. "\n" .. value.info
    if value.state == "online" and value.errors > 0 then
        message = message .. "\nUwaga: jeden z serwerów kontrolnych nie odpowiada."
    elseif value.state == "offline" and value.errors > 0 then
        message = message .. "\nSprawdź DNS, Wi-Fi i trasę sieciową."
    end
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
    notice("Sprawdzam połączenie z Kindle'a…", 3)
    UIManager:scheduleIn(0.2, function()
        local ok, result = pcall(function()
            local wifi = wifi_state()
            local answers = {}
            if wifi ~= "rozłączone" then
                for i, item in ipairs(TARGETS) do answers[i] = http_probe(item.url) end
                if not matches(TARGETS[2], answers[2]) then
                    answers[3] = http_probe(BACKUP.url)
                end
            end
            local assessed = assess(answers)
            local message = report_text(assessed, wifi)
            persist(message)
            if assessed.state == "portal" or assessed.state == "mixed" then
                return message .. "\n\nJeśli to sieć gościnna, otwórz w przeglądarce Kindle http://neverssl.com "
                    .. "i zaakceptuj wymagane warunki."
            end
            return message
        end)
        self.running = false
        notice(ok and result or "Test sieci nie powiódł się. Wybierz „Ponów test”.", 25)
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
            {text="Ponów test", keep_menu_open=true,
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
    http_probe=http_probe, wifi_state=wifi_state, targets=TARGETS, backup=BACKUP,
    failure_reason=failure_reason, matches=matches}
return Guest
