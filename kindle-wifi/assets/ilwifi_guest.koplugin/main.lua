-- IL Wi-Fi Gosc (TEST) for Kindle Keyboard 3 / KOReader.
-- Simple guest portal flow. Requires explicit user consent; never stores credentials.
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local UIManager = require("ui/uimanager")
local InfoMessage = require("ui/widget/infomessage")
local ConfirmBox = require("ui/widget/confirmbox")
local InputDialog = require("ui/widget/inputdialog")

local Guest = WidgetContainer:extend {name="ilwifi_guest", is_doc_only=false}
local VERSION = "1.2.0"
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

local function valid_url(value)
    if type(value) ~= "string" or #value > 2048 or value:find("[%c%s]") then return nil end
    local scheme, authority = value:match("^(https?)://([^/%?#]+)")
    if not scheme or not authority or authority:find("@",1,true) then return nil end
    if not authority:match("^[%w%-%._:%[%]]+$") then return nil end
    return value
end

local function raw_header(headers, field)
    if type(headers) ~= "table" then return nil end
    for key, value in pairs(headers) do
        if tostring(key):lower() == field then return value end
    end
    return nil
end

local function location(headers)
    return safe_url(raw_header(headers, "location"))
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
        portal=location(headers), redirect_raw=valid_url(raw_header(headers, "location")),
        via="LuaSocket"}
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
    local portal, redirect_raw
    for line in raw_headers:gmatch("[^\r\n]+") do
        local value = line:match("^%s*[Ll][Oo][Cc][Aa][Tt][Ii][Oo][Nn]:%s*(%S+)")
        if value then portal = safe_url(value); redirect_raw = valid_url(value) end
    end
    if not status then return nil, failure_reason(raw_errors, exit_code) end
    return {code=status, body=raw_body, portal=portal,
        redirect_raw=redirect_raw, via="curl/wget"}
end

local function http_probe(url)
    local answer, error_socket = from_socket(url)
    if answer then return answer end
    local fallback, error_command = from_command(url)
    if fallback then return fallback end
    -- Retain a specific LuaSocket reason when shell utilities are unavailable.
    local reason = error_command
    if (error_command == "brak narzędzia HTTP" or error_command == "brak odpowiedzi HTTP")
        and error_socket ~= "brak LuaSocket" then reason = error_socket end
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


-- A compact no-JavaScript guest portal assistant.
-- URL tokens, form fields, cookies and any e-mail stay in RAM only.
local function hostname(url)
    local h = valid_url(url) and url:match("^https?://([^/%?#]+)")
    return h and h:lower()
end

local function combine(base, relative)
    if not relative or relative == "" then return valid_url(base) end
    if relative:match("^https?://") then return valid_url(relative) end
    if relative:match("^//") then
        return valid_url((base:match("^(https?):") or "http") .. ":" .. relative)
    end
    if relative:find("[%c%s]") or relative:match("^[%w+.-]+:") then return nil end
    local site = base:match("^(https?://[^/]+)")
    if not site then return nil end
    if relative:sub(1,1) == "/" then return valid_url(site .. relative) end
    local folder = base:match("^([^?#]+)") or base
    folder = folder:match("^(.*)/[^/]*$") or site
    return valid_url(folder .. "/" .. relative)
end

local function entity_decode(value)
    value = tostring(value or "")
    return value:gsub("&amp;", "&"):gsub("&quot;", '"'):gsub("&#39;", "'")
        :gsub("&lt;", "<"):gsub("&gt;", ">")
end

local function attribute(tag, wanted)
    for name, q, value in tag:gmatch("([%w_%-]+)%s*=%s*([\"'])(.-)%2") do
        if name:lower() == wanted then return entity_decode(value) end
    end
    local pattern = "%f[%w]" .. wanted .. "%s*=%s*([^%s>\"']+)"
    return entity_decode(tag:lower():match(pattern))
end

local function page_text(html)
    local text = tostring(html or ""):gsub("<[sS][cC][rR][iI][pP][tT][^>]*>.-</[sS][cC][rR][iI][pP][tT]%s*>", " ")
        :gsub("<[sS][tT][yY][lL][eE][^>]*>.-</[sS][tT][yY][lL][eE]%s*>", " ")
        :gsub("<[bB][rR]%s*/?>", "\n"):gsub("</[pP]%s*>", "\n")
        :gsub("<[^>]+>", " "):gsub("[ \t]+", " ")
        :gsub("\n%s*\n+", "\n")
    return scrub(entity_decode(text), 2200)
end

local function parse_form(html, page_url)
    if type(html) ~= "string" or #html > 35000 or not valid_url(page_url) then
        return nil, "Nie udało się odczytać strony portalu."
    end
    local lower = html:lower()
    local a, b = lower:find("<form[%s>][^>]*>")
    if not a then
        -- A visible guest link can be submitted only on explicit user request.
        local pos = 1
        while true do
            local x, y = lower:find("<a[%s][^>]*>", pos)
            if not x then break end
            local ending = lower:find("</a%s*>", y + 1) or y
            local tag = html:sub(x, y)
            local label = page_text(html:sub(y + 1, ending - 1)):lower()
            local href = attribute(tag, "href")
            local target = combine(page_url, href)
            if target and (label:find("guest",1,true) or label:find("gość",1,true)
                or label:find("free wifi",1,true) or label:find("bezpłat",1,true)) then
                return {url=target, method="GET", fields={}, email_field=nil,
                    label="Dostęp jako gość", guest_link=true}
            end
            pos = ending + 4
        end
        return nil, "Ta strona nie ma prostego formularza. Może wymagać JavaScriptu lub CAPTCHA."
    end
    local close = lower:find("</form%s*>", b + 1)
    if not close then return nil, "Formularz jest niekompletny." end
    local tag = html:sub(a,b)
    local target = combine(page_url, attribute(tag,"action") or "")
    if not target then return nil, "Nieprawidłowy adres formularza." end
    local meth = (attribute(tag,"method") or "GET"):upper()
    if meth ~= "GET" and meth ~= "POST" then return nil, "Nieobsługiwana metoda formularza." end
    local form = {url=target, method=meth, fields={}, label="Dostęp jako gość"}
    local pos, inputs = b + 1, 0
    while true do
        local x, y = lower:find("<input[%s>][^>]*>", pos)
        if not x or x >= close then break end
        inputs = inputs + 1
        if inputs > 25 then return nil, "Formularz jest zbyt rozbudowany." end
        local input = html:sub(x,y)
        local name = attribute(input,"name")
        local kind = (attribute(input,"type") or "text"):lower()
        local value = attribute(input,"value") or ""
        local is_email = kind == "email" or
            (kind == "text" and name and name:lower():find("mail",1,true))
        if kind == "password" or kind == "file" or kind == "tel" then
            return nil, "Portal wymaga danych, których wtyczka nie obsługuje."
        end
        if name and #name <= 100 and not name:find("[%c]") then
            if kind == "hidden" and #value <= 1500 then
                form.fields[#form.fields+1] = {name=name, value=value}
            elseif kind == "checkbox" then
                -- Added only after the user has reviewed and explicitly confirmed.
                form.fields[#form.fields+1] = {name=name, value=value ~= "" and value or "on"}
                form.consent_box = true
            elseif kind == "submit" and not form.has_submit and #value <= 150 then
                form.fields[#form.fields+1] = {name=name, value=value}
                form.has_submit = true
            elseif is_email then
                form.email_field = name
            elseif kind == "text" or kind == "number" then
                return nil, "Portal wymaga dodatkowego pola. Nie wyślę niepełnego formularza."
            end
        elseif is_email then
            return nil, "Pole e-mail nie ma nazwy w formularzu."
        end
        pos = y + 1
    end
    if lower:sub(b+1,close):find("captcha",1,true)
        or lower:sub(b+1,close):find("recaptcha",1,true) then
        return nil, "Strona wymaga CAPTCHA, której Kindle nie obsłuży w tym trybie."
    end
    return form
end

local function urlencode(value)
    return tostring(value or ""):gsub("\n", "\r\n")
        :gsub("([^%w%-_%.~])", function(ch) return string.format("%%%02X",ch:byte()) end)
end

local function encode_form(form, email)
    local parts = {}
    for _, field in ipairs(form.fields or {}) do
        parts[#parts+1] = urlencode(field.name) .. "=" .. urlencode(field.value)
    end
    if form.email_field then
        if not email then return nil end
        parts[#parts+1] = urlencode(form.email_field) .. "=" .. urlencode(email)
    end
    return table.concat(parts, "&")
end

local function portal_http(url, method, payload, session)
    if not valid_url(url) then return nil, "Nieprawidłowy adres portalu." end
    method = method or "GET"
    if method ~= "GET" and method ~= "POST" then return nil, "Nieobsługiwana metoda." end
    if payload and #payload > 4000 then return nil, "Za duży formularz." end
    -- CURL is the preferred transport for TLS with certificate verification.
    -- The cookie jar is temporary, private and never included in reports.
    if available("curl") then
        local root = "/tmp/ilwifi-auth-" .. tostring(os.time()) .. "-"
            .. tostring(math.random(10000,99999))
        local header, body, data, cookie, err, rc =
            root .. ".h", root .. ".b", root .. ".p", root .. ".cookie", root .. ".e", root .. ".rc"
        session.cookie_file = session.cookie_file or cookie
        local command = "umask 077; curl -sS --connect-timeout 3 --max-time 9"
            .. " --max-redirs 0 -D " .. quote(header) .. " -o " .. quote(body)
            .. " -A 'Kindle-IL-WiFi-Guest/1.2'"
            .. " -b " .. quote(session.cookie_file) .. " -c " .. quote(session.cookie_file)
        if method == "POST" then
            local file = io.open(data,"wb")
            if not file then return nil, "Brak dostępu do pliku tymczasowego." end
            file:write(payload or ""); file:close()
            command = command .. " -X POST -H 'Content-Type: application/x-www-form-urlencoded'"
                .. " --data-binary @" .. quote(data)
        end
        command = command .. " " .. quote(url) .. " 2>" .. quote(err)
            .. "; echo $? > " .. quote(rc)
        os.execute(command)
        local h, response, fail = read(header, 12000), read(body, 30000), read(err, 1000)
        local exit = tonumber(trim(read(rc, 16)))
        os.remove(header); os.remove(body); os.remove(data); os.remove(err); os.remove(rc)
        local status
        for n in h:gmatch("HTTP/%d[%d%.]*%s+(%d%d%d)") do status = tonumber(n) end
        local redirect
        for line in h:gmatch("[^\r\n]+") do
            local v = line:match("^%s*[Ll][Oo][Cc][Aa][Tt][Ii][Oo][Nn]:%s*(%S+)")
            if v then redirect = valid_url(v) or combine(url,v) end
        end
        if not status then return nil, failure_reason(fail,exit) end
        return {code=status, body=response, redirect_raw=redirect}, nil
    end
    if url:match("^https://") then
        return nil, "Brak bezpiecznego klienta HTTPS na Kindle. Nie wyłączam weryfikacji certyfikatów."
    end
    local yes, http = pcall(require,"socket.http")
    local yes_ltn, ltn12 = pcall(require,"ltn12")
    if not yes or not yes_ltn then return nil, "Brak narzędzia do obsługi formularzy." end
    http.TIMEOUT = 6
    local chunks, total = {}, 0
    local sink = function(chunk)
        if chunk and total < 30000 then
            local keep = chunk:sub(1, 30000 - total)
            chunks[#chunks+1]=keep; total=total+#keep
        end
        return 1
    end
    local headers={["Connection"]="close", ["User-Agent"]="Kindle-IL-WiFi-Guest/1.2"}
    if session.cookie then headers["Cookie"]=session.cookie end
    local args={url=url,method=method,redirect=false,headers=headers,sink=sink}
    if method=="POST" then
        headers["Content-Type"]="application/x-www-form-urlencoded"
        headers["Content-Length"]=tostring(#(payload or ""))
        args.source=ltn12.source.string(payload or "")
    end
    local ok, success, code, response_headers = pcall(http.request,args)
    if not ok or not success then return nil,failure_reason(ok and code or success) end
    local set_cookie = raw_header(response_headers,"set-cookie")
    if type(set_cookie)=="string" then
        local first = set_cookie:match("^([^;\r\n]+)")
        if first and first:match("^[%w_%-]+=") then session.cookie=first:sub(1,512) end
    end
    local redirect = raw_header(response_headers,"location")
    return {code=tonumber(code),body=table.concat(chunks),
        redirect_raw=valid_url(redirect) or combine(url,redirect)},nil
end

local function cleanup_session(session)
    if session and session.cookie_file then
        os.remove(session.cookie_file)
        session.cookie_file=nil
    end
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
            cleanup_session(self.portal_session)
            self.portal_session = nil
            self.portal = nil
            if assessed.state == "portal" or assessed.state == "mixed" then
                -- Tokenized redirect is session-only, never written into a report.
                self.portal_hint = (answers[1] and answers[1].redirect_raw)
                    or (answers[2] and answers[2].redirect_raw)
                    or (answers[3] and answers[3].redirect_raw)
                    or TARGETS[1].url
            else
                self.portal_hint = nil
            end
            local message = report_text(assessed, wifi)
            persist(message)
            if assessed.state == "portal" or assessed.state == "mixed" then
                return message .. "\n\nWybierz „Otwórz portal w KOReaderze”, aby sprawdzić logowanie."
            end
            return message
        end)
        self.running = false
        notice(ok and result or "Test sieci nie powiódł się. Wybierz „Ponów test”.", 25)
    end)
end

function Guest:load_portal()
    if self.running then notice("Trwa już sprawdzanie sieci.",5); return end
    if not self.portal_hint then
        notice("Najpierw wybierz „Sprawdź Wi-Fi i portal”.",9)
        return
    end
    self.running=true
    notice("Odczytuję stronę gościa z Kindle'a…",3)
    UIManager:scheduleIn(0.2,function()
        local ok, result = pcall(function()
            cleanup_session(self.portal_session)
            self.portal_session={}
            local current = self.portal_hint
            local response, err
            local steps=0
            repeat
                response, err = portal_http(current,"GET",nil,self.portal_session)
                if not response then break end
                local next_url = response.redirect_raw and combine(current,response.redirect_raw)
                if next_url and response.code >=300 and response.code <400 and steps<3 then
                    if hostname(next_url) ~= hostname(current) then self.portal_session.cookie=nil end
                    current=next_url
                    steps=steps+1
                else break end
            until false
            if not response then return "Nie odczytano strony: "..scrub(err,100) end
            if response.code <200 or response.code >=400 then
                return "Portal zwrócił HTTP "..tostring(response.code)
                    .. ". Formularz może być niedostępny."
            end
            local form, why = parse_form(response.body,current)
            if not form then
                self.portal=nil
                return "Nie można automatycznie obsłużyć strony. "..scrub(why,200)
            end
            if hostname(form.url) ~= hostname(current) then
                self.portal=nil
                return "Formularz przekierowuje na inny serwer. Dla bezpieczeństwa "
                    .. "nie wysyłam danych automatycznie."
            end
            self.portal={form=form,host=hostname(form.url),text=page_text(response.body)}
            return "Wykryto prosty portal logowania: "..self.portal.host
                .. (form.email_field and "\nWymaga adresu e-mail." or "\nMożliwy dostęp jako gość.")
                .. "\nPrzed połączeniem wybierz „Pokaż treść portalu”."
                .. "\nNastępnie wybierz „Połącz jako gość” lub „Podaj e-mail”."
        end)
        self.running=false
        notice(ok and result or "Błąd odczytu portalu. Nie wysłano żadnych danych.",22)
    end)
end

function Guest:show_portal()
    if not self.portal then
        notice("Najpierw wybierz „Otwórz portal w KOReaderze”.",8)
        return
    end
    local text="Portal: "..self.portal.host.."\n\n"
        .. (self.portal.text~="" and self.portal.text or "Brak czytelnego tekstu.")
        .. "\n\nZewnętrzny regulamin lub polityka prywatności mogą wymagać przeglądarki."
    local ok, TextViewer = pcall(require,"ui/widget/textviewer")
    if ok and TextViewer and TextViewer.new then
        UIManager:show(TextViewer:new{title="Treść portalu Wi-Fi",text=text})
    else notice(text,25) end
end

function Guest:submit_portal(email)
    local current=self.portal
    if not current then notice("Najpierw odczytaj portal.",7); return end
    local form=current.form
    if form.email_field and (type(email)~="string"
        or #email>240 or not email:match("^[%w%._%+%-]+@[%w%-%.]+%.[%a]+$")) then
        notice("Podaj prawidłowy adres e-mail.",9); return
    end
    local target_host = hostname(form.url)
    if not target_host or target_host~=current.host then
        notice("Serwer formularza jest inny od strony logowania. Operacja przerwana.",10)
        return
    end
    local warning=form.url:match("^http://") and
        "\nUwaga: połączenie HTTP nie szyfruje wysyłanych danych." or ""
    UIManager:show(ConfirmBox:new{
        text="Sprawdź, czy zgadzasz się z regulaminem sieci Wi-Fi operatora. "
            .. "Wtyczka wyśle formularz wyłącznie po Twoim potwierdzeniu.\n"
            .. "Serwer: "..current.host
            .. (email and "\nAdres e-mail będzie wysłany do operatora." or "")
            .. warning,
        ok_text="Akceptuję i łączę",
        ok_callback=function()
            if self.running then notice("Trwa inne połączenie.",5); return end
            self.running=true
            UIManager:scheduleIn(0.2,function()
                local ok, result=pcall(function()
                    local payload=encode_form(form,email)
                    if not payload or #payload>4000 then return "Nieprawidłowe pola formularza." end
                    local url=form.url
                    if form.method=="GET" and payload~="" then
                        url=url..(url:find("?",1,true) and "&" or "?")..payload
                        if not valid_url(url) then return "Adres formularza jest zbyt długi." end
                    end
                    local answer, err=portal_http(url,form.method,
                        form.method=="POST" and payload or nil,self.portal_session or {})
                    if not answer then return "Nie wysłano: "..scrub(err,90) end
                    if answer.code>=400 then return "Serwer odrzucił formularz: HTTP "
                        ..tostring(answer.code)..". Nie potwierdzono dostępu." end
                    -- HTTP 200 or redirect is NOT proof of successful authentication.
                    return "Serwer przyjął żądanie (HTTP "..tostring(answer.code)
                        .."). Sprawdź teraz dostęp przyciskiem „Ponów test”."
                end)
                self.running=false
                notice(ok and result or "Nie udało się wysłać formularza.",20)
            end)
        end,
    })
end

function Guest:ask_email()
    if not self.portal then notice("Najpierw odczytaj portal.",8); return end
    if not self.portal.form.email_field then
        notice("Ten formularz nie wymaga adresu e-mail. Użyj „Połącz jako gość”.",9)
        return
    end
    local dlg
    dlg=InputDialog:new{
        title="Adres e-mail do sieci gościnnej",input="",input_type="text",
        buttons={{{text="Anuluj",id="close",callback=function() UIManager:close(dlg) end},
            {text="Dalej",is_enter_default=true,callback=function()
                local email=trim(dlg:getInputText() or "")
                if #email>240 or not email:match("^[%w%._%+%-]+@[%w%-%.]+%.[%a]+$") then
                    notice("Sprawdź adres e-mail.",8)
                    return
                end
                UIManager:close(dlg)
                self:submit_portal(email)
            end}}},
    }
    UIManager:show(dlg)
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
            {text="Otwórz portal w KOReaderze", keep_menu_open=true,
                callback=function() self:load_portal() end},
            {text="Pokaż treść portalu", keep_menu_open=true,
                callback=function() self:show_portal() end},
            {text="Połącz jako gość", keep_menu_open=true,
                callback=function() self:submit_portal(nil) end},
            {text="Podaj e-mail", keep_menu_open=true,
                callback=function() self:ask_email() end},
            {text="Ostatni raport", keep_menu_open=true,
                callback=function() self:last_report() end},
            {text="Jak połączyć Kindle w pociągu?", callback=function()
                notice("1. W ustawieniach Kindle'a wybierz sieć Wi-Fi pociągu (2,4 GHz).\n"
                    .. "2. Otwórz IL Wi-Fi Gość i sprawdź połączenie.\n"
                    .. "3. Otwórz portal w KOReaderze i zapoznaj się z treścią strony.\n"
                    .. "4. Wybierz dostęp jako gość lub wpisz e-mail, a potem zatwierdź regulamin.\n"
                    .. "5. Ponów test. Nie każdy portal działa bez JavaScriptu lub CAPTCHA.\n\n"
                    .. "Wtyczka nie obchodzi zabezpieczeń sieci.", 30)
            end},
        },
    }
end

Guest.test = {assess=assess, safe_url=safe_url, report_text=report_text,
    http_probe=http_probe, wifi_state=wifi_state, targets=TARGETS, backup=BACKUP,
    failure_reason=failure_reason, matches=matches,
    parse_form=parse_form, encode_form=encode_form, combine=combine,
    portal_http=portal_http, valid_url=valid_url}
return Guest
