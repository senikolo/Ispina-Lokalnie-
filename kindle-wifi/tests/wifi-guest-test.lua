-- Offline regression tests for IL Wi-Fi Gość v1.2: no real network or logins.
local source = "assets/ilwifi_guest.koplugin/main.lua"
local shown, registered = {}, nil
package.preload["ui/widget/container/widgetcontainer"] = function()
    return {extend=function(_, class) return class end}
end
package.preload["ui/uimanager"] = function()
    return {
        show=function(_, item) shown[#shown+1] = item end,
        scheduleIn=function(_, _, callback) callback() end,
    }
end
package.preload["ui/widget/confirmbox"] = function()
    return {new=function(_,item)return item end}
end
package.preload["ui/widget/inputdialog"] = function()
    return {new=function(_,item)return item end}
end
package.preload["ui/widget/infomessage"] = function()
    return {new=function(_, item) return item end}
end
package.preload["ltn12"] = function() return {sink={table=function() return function() return 1 end end}} end
local scenarios, queries = {}, {}
package.preload["socket.http"] = function()
    return {
        TIMEOUT=4,
        request=function(req)
            assert(req.redirect == false and req.method == "GET", "Never auto-follow portal redirect or submit a form")
            assert(req.url:match("^http://"), "Use HTTP to detect redirects without outdated TLS")
            local response = assert(scenarios[req.url], "Unexpected HTTP probe " .. req.url)
            queries[#queries+1] = req.url
            if response.error then return nil, response.error end
            req.sink(response.body or "")
            return 1, response.code, response.headers or {}, "HTTP/1.1"
        end,
    }
end
-- Prevent any real shell network requests during simulated failures.
local execute = os.execute
os.execute = function() return true end

local Guest = dofile(source)
local test = Guest.test
assert(#test.targets == 2 and test.backup)
assert(test.backup.url:match("^http://"))
assert(test.safe_url("https://login.train.example/guest?token=secret123#fragment")
    == "https://login.train.example/guest")
assert(not test.safe_url("javascript:alert(1)"))
assert(not test.safe_url("http://guest.example/\ninjected"))
assert(test.failure_reason("Could not resolve host",6) == "brak DNS")
assert(test.failure_reason("Connection timed out") == "przekroczono czas")
assert(test.failure_reason("Connection refused",7) == "odmowa połączenia")
assert(test.failure_reason("Network is unreachable") == "brak trasy sieciowej")
assert(test.failure_reason("przekroczono czas") == "przekroczono czas")

local urls = {test.targets[1].url, test.targets[2].url, test.backup.url}
local function set(a, b, c)
    scenarios[urls[1]], scenarios[urls[2]], scenarios[urls[3]] = a, b, c
end
local function probe_all()
    return {test.http_probe(urls[1]), test.http_probe(urls[2]),
        test.http_probe(urls[3])}
end

set({code=204,body=""}, {code=200,body="Microsoft Connect Test"}, {code=204,body=""})
local result = test.assess({test.http_probe(urls[1]),test.http_probe(urls[2])})
assert(result.state=="online")
assert(test.report_text(result,"połączone"):find("Internet: dostępny",1,true))
assert(not test.report_text(result,"połączone"):find("Uwaga:",1,true))
assert(test.matches(test.targets[1], {code=204,body=""}))
assert(not test.matches(test.targets[1], {code=204,body="unexpected"}))

set({code=302,headers={Location="http://train.example/guest?token=secret123"}},
    {code=200,body="<html>Accept terms</html>"},
    {code=302,headers={Location="http://train.example/landing?cookie=secret"}})
result = test.assess(probe_all())
assert(result.state=="portal")
assert(result.portal_url=="http://train.example/landing")
local report=test.report_text(result,"połączone")
assert(report:find("Portal: prawdopodobny",1,true))
assert(not report:find("secret",1,true), "Never save session tokens")

set({code=204}, {code=302,headers={Location="http://wifi.example/auth"}}, {code=204})
result = test.assess(probe_all())
assert(result.state=="mixed", "Conflicting endpoints cannot be called fully online")

result = test.assess({{code=nil,error="brak DNS"}, {code=nil,error="przekroczono czas"}})
assert(result.state=="offline", "No network is not a confirmed captive portal")
local offline = test.report_text(result, "połączone")
assert(offline:find("Kontrola 204: brak DNS",1,true))
assert(offline:find("Kontrola tekstowa: przekroczono czas",1,true))
assert(not offline:find("neverssl",1,true))

local plugin = setmetatable({ui={menu={registerToMainMenu=function(_,p) registered=p end}}}, {__index=Guest})
plugin:init()
assert(registered==plugin)
local menu={}
plugin:addToMainMenu(menu)
assert(menu.ilwifi_guest and menu.ilwifi_guest.sorting_hint=="more_tools")
local actions=menu.ilwifi_guest.sub_item_table
assert(#actions==10 and actions[1].text=="Sprawdź Wi-Fi i portal"
    and actions[2].text=="Ponów test" and actions[3].text=="Test lokalnego portalu na telefonie"
    and actions[4].text=="Wpisz adres telefonu ręcznie"
    and actions[5].text=="Otwórz portal w KOReaderze"
    and actions[6].text=="Pokaż treść portalu" and actions[7].text=="Połącz jako gość"
    and actions[8].text=="Podaj e-mail" and actions[9].text=="Ostatni raport")

set({code=204,body=""}, {code=200,body="Microsoft Connect Test"}, {code=204,body=""})
queries={}
actions[1].callback()
assert(#shown>=2 and shown[#shown].text:find("Internet: dostępny",1,true))
assert(not shown[#shown].text:find("neverssl.com",1,true),
    "Do not show guest login instructions when internet already works")
assert(#queries == 2, "No third request needed when both checks succeed")

-- If Microsoft probe fails, Mozilla is used. The good Android probe still
-- confirms internet and should not be treated as a captive portal.
set({code=204,body=""}, {error="timeout"}, {code=204,body=""})
queries={}
actions[2].callback()
assert(#queries==3 and queries[3]==urls[3], "Fallback must be requested after failed secondary probe")
assert(shown[#shown].text:find("Internet: dostępny",1,true))
assert(shown[#shown].text:find("Kontrola tekstowa: przekroczono czas",1,true))
assert(shown[#shown].text:find("Serwer zapasowy: 204 (OK)",1,true))
assert(not shown[#shown].text:find("neverssl.com",1,true))

-- A redirect with an unconfirmed second check should explain the guest login.
set({code=302,headers={Location="http://train.example/guest?auth=not-for-log"}},
    {code=200,body="<html>Sign in</html>"}, {code=302})
actions[2].callback()
assert(shown[#shown].text:find("Portal: prawdopodobny",1,true))
assert(shown[#shown].text:find("Otwórz portal w KOReaderze",1,true))
assert(not shown[#shown].text:find("not-for-log",1,true))

os.execute = execute
print("PASS: Wi-Fi guest v1.3: internet, DNS/timeout, fallback, portal, retry, safe diagnostics")
