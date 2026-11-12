-- Pure offline simulated captive-portal flow for KOReader Kindle 3.
local shown, sessions = {}, {}
package.preload["ui/widget/container/widgetcontainer"] = function()
    return {extend=function(_, class) return class end}
end
package.preload["ui/uimanager"] = function()
    return {
        show=function(_, object) shown[#shown+1]=object end,
        close=function() end,
        scheduleIn=function(_, _, callback) callback() end,
    }
end
package.preload["ui/widget/infomessage"] = function()
    return {new=function(_, o) o.kind="info";return o end}
end
package.preload["ui/widget/confirmbox"] = function()
    return {new=function(_, o) o.kind="confirm";return o end}
end
package.preload["ui/widget/inputdialog"] = function()
    return {new=function(_, o) o.kind="input";o.getInputText=function()return o.user_input or "" end;return o end}
end
package.preload["ui/widget/textviewer"] = function()
    return {new=function(_, o) o.kind="viewer";return o end}
end
package.preload["ltn12"] = function()
    return {sink={},source={string=function(input)
        local emitted=false
        return function()
            if emitted then return nil end
            emitted=true
            return input
        end
    end}}
end

local execute=os.execute
-- No shell HTTP client, thus LuaSocket handles all synthetic portal connections.
os.execute=function(command)
    if command:find("command -v ",1,true) then return false end
    error("Unexpected shell operation: "..tostring(command))
end

local expected, calls = {}, {}
package.preload["socket.http"] = function()
    return {TIMEOUT=4,request=function(req)
        local response=expected[req.url] and expected[req.url][req.method]
        assert(response,"Unexpected "..req.method.." request "..req.url)
        local sent=""
        if req.source then
            while true do local chunk=req.source();if not chunk then break end;sent=sent..chunk end
        end
        calls[#calls+1]={method=req.method,url=req.url,payload=sent,headers=req.headers}
        if response.error then return nil,response.error end
        if response.body then req.sink(response.body) end
        return 1,response.code,response.headers or {},"HTTP/1.1"
    end}
end
local function route(url,method,response)
    expected[url]=expected[url] or {}
    expected[url][method]=response
end

local Guest=dofile("assets/ilwifi_guest.koplugin/main.lua")
local T=Guest.test
local page=[[
<html><head><title>Guest Wi-Fi</title></head>
<body><h1>Wi-Fi dla pasażerów</h1><p>Akceptując warunki korzystania, możesz połączyć się jako gość.</p>
<form action="/authorize" method="POST">
<input type="hidden" name="csrf" value="token&amp;A">
<input type="checkbox" name="agree" value="yes">
<input type="submit" name="guest" value="Connect">
</form>
</body></html>]]
local form,err=T.parse_form(page,"http://portal.train/login?session=secret")
assert(form,err)
assert(form.method=="POST" and form.url=="http://portal.train/authorize")
assert(form.consent_box)
assert(T.encode_form(form)== "csrf=token%26A&agree=yes&guest=Connect")
assert(T.combine("http://portal.train/login","/authorize")=="http://portal.train/authorize")
assert(not T.combine("http://portal.train/login","javascript:alert(1)"))
assert(not T.parse_form("<form method='POST' action='https://train.test/auth'><input type='password' name='pw'></form>", "http://portal.train/login"))
assert(not T.parse_form("<html><script>start()</script></html>", "http://portal.train/login"))

local email_page=[[
<form method='post' action='/register'>
  <input type='hidden' name='sessionid' value='abc'>
  <input type='email' name='email'>
  <input type='checkbox' name='policy' value='accepted'>
</form>]]
local email_form=T.parse_form(email_page,"http://portal.train/login")
assert(email_form and email_form.email_field=="email" and email_form.method=="POST")
assert(T.encode_form(email_form,"guest@example.com")==
    "sessionid=abc&policy=accepted&email=guest%40example.com")
assert(T.encode_form(email_form,nil)==nil)

local plugin=setmetatable({ui={menu={registerToMainMenu=function()end}}},{__index=Guest})
local menu={}
plugin:addToMainMenu(menu)
local actions=menu.ilwifi_guest.sub_item_table
local first="http://connectivitycheck.gstatic.com/generate_204"
local second="http://www.msftconnecttest.com/connecttest.txt"
local backup="http://firefox-portal-detection.com/generate_204"
local tokenized="http://portal.train/login?session=secret"
route(first,"GET",{code=302,headers={location=tokenized}})
route(second,"GET",{code=200,body="<html>portal</html>"})
route(backup,"GET",{code=302,headers={location=tokenized}})
route(tokenized,"GET",{code=200,body=page})
route("http://portal.train/authorize","POST",{code=302,headers={location="http://portal.train/welcome"}})
actions[1].callback()
assert(plugin.portal_hint==tokenized, "Token preserved only in RAM")
assert(shown[#shown].text:find("Otwórz portal w KOReaderze",1,true))
assert(not shown[#shown].text:find("session=secret",1,true),"Do not reveal token in report")
actions[3].callback()
assert(plugin.portal and plugin.portal.host=="portal.train" and plugin.portal.form)
assert(shown[#shown].text:find("Możliwy dostęp jako gość",1,true))
actions[4].callback()
assert(shown[#shown].kind=="viewer" and shown[#shown].text:find("Wi-Fi dla pasażerów",1,true))
local total=#calls
actions[5].callback()
local confirm=shown[#shown]
assert(confirm.kind=="confirm" and confirm.ok_text=="Akceptuję i łączę")
assert(#calls==total,"No submission without explicit acceptance")
confirm.ok_callback()
assert(calls[#calls].method=="POST" and calls[#calls].url=="http://portal.train/authorize")
assert(calls[#calls].payload=="csrf=token%26A&agree=yes&guest=Connect")
assert(shown[#shown].text:find("Ponów test",1,true))
assert(not shown[#shown].text:find("Internet: dostępny",1,true),
    "HTTP 302 acknowledgement does not prove login succeeded")
assert(not calls[#calls].payload:find("session=secret",1,true))

-- Email portal: user types into InputDialog and separately confirms network terms.
route(tokenized,"GET",{code=200,body=email_page})
actions[3].callback()
assert(plugin.portal and plugin.portal.form.email_field=="email")
actions[6].callback()
local input=shown[#shown]
assert(input.kind=="input" and input.input_type=="text")
input.user_input="guest@example.com"
input.buttons[1][2].callback()
confirm=shown[#shown]
assert(confirm.kind=="confirm" and confirm.text:find("Adres e-mail",1,true))
assert(not calls[#calls].payload:find("guest@example.com",1,true))
route("http://portal.train/register","POST",{code=200,body="Processing"})
confirm.ok_callback()
assert(calls[#calls].method=="POST")
assert(calls[#calls].payload:find("guest%40example.com",1,true))
assert(not shown[#shown].text:find("Internet: dostępny",1,true))

os.execute=execute
print("PASS: Wi-Fi guest 1.2: guest/login HTML, consent, email, privacy, unsupported JS")
