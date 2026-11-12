-- End-to-end simulation: Kindle KOReader contacts a mock phone hotspot.
local shown={}
package.preload["ui/widget/container/widgetcontainer"]=function()
    return {extend=function(_,cls)return cls end}
end
package.preload["ui/uimanager"]=function()
    return {show=function(_,o)shown[#shown+1]=o end,
        close=function()end,
        scheduleIn=function(_,_,callback)callback()end}
end
package.preload["ui/widget/infomessage"]=function()
    return {new=function(_,o)o.kind="info";return o end}
end
package.preload["ui/widget/confirmbox"]=function()
    return {new=function(_,o)o.kind="confirm";return o end}
end
package.preload["ui/widget/inputdialog"]=function()
    return {new=function(_,o)o.kind="input";o.getInputText=function()return o.user_input or "" end;return o end}
end
package.preload["ltn12"]=function()
    return {source={string=function(s)
        local sent=false;return function()if sent then return nil end;sent=true;return s end
    end}}
end
local original_execute=os.execute
os.execute=function(command)
    if command:find("command -v ",1,true) then return false end
    error("Unexpected shell command: "..tostring(command))
end
local replies, calls = {}, {}
package.preload["socket.http"]=function()
    return {TIMEOUT=4,request=function(req)
        local response=replies[req.url] and replies[req.url][req.method]
        assert(response,req.method.." "..req.url)
        local body=""
        if req.source then while true do local p=req.source();if not p then break end;body=body..p end end
        calls[#calls+1]={method=req.method,url=req.url,body=body}
        req.sink(response.body or "")
        return 1,response.code,response.headers or {}
    end}
end
local function reply(url,method,data)
    replies[url]=replies[url] or {}
    replies[url][method]=data
end
local Guest=dofile("assets/ilwifi_guest.koplugin/main.lua")
assert(Guest.test.private_ip("192.168.43.1"))
assert(Guest.test.private_ip("10.2.0.1"))
assert(Guest.test.private_ip("172.19.1.1"))
assert(not Guest.test.private_ip("8.8.8.8"))
assert(not Guest.test.private_ip("192.168.999.1"))
assert(not Guest.test.private_ip("123;ls"))

local phone="http://192.168.43.1:8765/"
reply(phone,"GET",{code=200,body=[[
<html><form action="/authorize" method="POST">
<input type="hidden" name="simulation" value="kindle-lab">
<input type="checkbox" name="agree" value="yes">
<input type="submit" name="guest" value="Zatwierdz test">
</form></html>]]})
reply(phone.."authorize","POST",{code=200,body="<h1>TEST: formularz przyjęty</h1>"})
reply(phone.."status","GET",{code=200,body="TEST_OK_GUEST"})
local plugin=setmetatable({ui={menu={registerToMainMenu=function()end}}},{__index=Guest})
local count=#calls
plugin:local_test("8.8.8.8")
assert(#calls==count,"Never connect to public IP for local simulator")
plugin:local_test("192.168.43.1")
assert(plugin.is_local_test and plugin.portal and plugin.portal.form)
assert(#calls==count+1 and calls[#calls].url==phone)
plugin:submit_portal(nil)
assert(shown[#shown].kind=="confirm")
assert(#calls==count+1,"No consent means no POST")
shown[#shown].ok_callback()
assert(calls[#calls].method=="GET" and calls[#calls].url==phone.."status")
assert(shown[#shown].text:find("TEST OK",1,true))
assert(shown[#shown].text:find("Nie oznacza",1,true))
assert(calls[#calls-1].body:find("agree=yes",1,true))
assert(calls[#calls-1].body:find("simulation=kindle-lab",1,true))
-- Email mode: same phone, different form, no email retained on the Kindle.
reply(phone,"GET",{code=200,body=[[
<html><form action="/register" method="POST">
<input type="hidden" name="simulation" value="kindle-lab">
<input type="email" name="email">
<input type="checkbox" name="agree" value="yes">
</form></html>]]})
reply(phone.."register","POST",{code=200,body="OK"})
reply(phone.."status","GET",{code=200,body="TEST_OK_EMAIL"})
plugin:local_test("192.168.43.1")
assert(plugin.portal.form.email_field=="email")
plugin:ask_email()
local input=shown[#shown];assert(input.kind=="input")
input.user_input="test@example.org"
input.buttons[1][2].callback()
assert(shown[#shown].kind=="confirm")
shown[#shown].ok_callback()
assert(shown[#shown].text:find("TEST OK",1,true))
assert(calls[#calls-1].body:find("test%40example.org",1,true))
os.execute=original_execute
print("PASS: Kindle local Android hotspot portal, consent, e-mail form, no public IP")
