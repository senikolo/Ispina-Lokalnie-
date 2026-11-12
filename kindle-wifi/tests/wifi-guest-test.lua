-- Offline regression tests for IL Wi-Fi Gosc (no real external network or logins).
local source = "assets/ilwifi_guest.koplugin/main.lua"
local shown, registered = {}, nil
package.preload["ui/widget/container/widgetcontainer"] = function()
    return {extend=function(_, class) return class end}
end
package.preload["ui/uimanager"] = function()
    return {
        show=function(_, item) shown[#shown + 1] = item end,
        scheduleIn=function(_, _, callback) callback() end,
    }
end
package.preload["ui/widget/infomessage"] = function()
    return {new=function(_, item) return item end}
end
package.preload["ltn12"] = function() return {sink={table=function() return function() return 1 end end}} end

local scenarios = {}
package.preload["socket.http"] = function()
    return {
        TIMEOUT=4,
        request=function(req)
            assert(req.redirect == false and req.method == "GET", "Never follow or submit portal form")
            assert(req.url:match("^http://"), "Detection uses plain HTTP (no TLS needed)")
            local response = assert(scenarios[req.url], "Unexpected URL")
            req.sink(response.body or "")
            return 1, response.code, response.headers or {}, "HTTP/1.1"
        end
    }
end

local Guest = dofile(source)
local test = Guest.test
assert(#test.targets == 2)
assert(test.safe_url("https://login.train.example/guest?token=secret123#fragment")
    == "https://login.train.example/guest")
assert(not test.safe_url("javascript:alert(1)"))
assert(not test.safe_url("http://guest.example/\ninjected"))
local urls = {test.targets[1].url, test.targets[2].url}

local function set(a, b)
    scenarios[urls[1]] = a
    scenarios[urls[2]] = b
    return {test.http_probe(urls[1]), test.http_probe(urls[2])}
end
local result = test.assess(set(
    {code=204,body=""}, {code=200,body="Microsoft Connect Test"}))
assert(result.state == "online")
assert(test.report_text(result, "połączone"):find("Internet: dostępny", 1, true))

result = test.assess(set(
    {code=302,headers={Location="http://train.example/guest?token=secret123"}},
    {code=200,body="<html>Accept terms</html>"}))
assert(result.state == "portal")
assert(result.portal_url == "http://train.example/guest")
local report = test.report_text(result, "połączone")
assert(report:find("Portal: prawdopodobny",1,true))
assert(not report:find("secret123",1,true), "Never persist URL session tokens")

result = test.assess(set(
    {code=204}, {code=302,headers={Location="http://wifi.example/auth"}}))
assert(result.state == "mixed", "Conflicting endpoints must not be called fully online")

result = test.assess({{code=nil,error="DNS"},{code=nil,error="timeout"}})
assert(result.state == "offline", "Network outage must not be mistaken for a captive portal")

local plugin = setmetatable({ui={menu={registerToMainMenu=function(_, p) registered=p end}}}, {__index=Guest})
plugin:init()
assert(registered == plugin)
local menu = {}
plugin:addToMainMenu(menu)
assert(menu.ilwifi_guest and menu.ilwifi_guest.sorting_hint=="more_tools")
assert(#menu.ilwifi_guest.sub_item_table == 3)
set({code=204}, {code=200,body="Microsoft Connect Test"})
menu.ilwifi_guest.sub_item_table[1].callback()
assert(#shown>=2)
assert(shown[#shown].text:find("Internet: dostępny",1,true))
assert(shown[#shown].text:find("neverssl.com",1,true))
print("PASS: KOReader menu, plain HTTP guest detection, offline, redirects, privacy, nonblocking start")
