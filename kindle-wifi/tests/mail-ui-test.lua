-- Regression: saving account parameters must keep the KOReader mail menu alive.
local settings, currentDialog, keyboardOpened, closed = {}, nil, false, false
local notices = {}
package.preload["ui/uimanager"] = function()
    return {
        show = function(_, widget)
            if widget.buttons then currentDialog=widget else notices[#notices+1]=widget.text end
        end,
        close = function(_, widget)
            assert(widget == currentDialog, "Only the input dialog may close")
            closed=true
        end,
    }
end
package.preload["ui/widget/infomessage"] = function()
    return {new=function(_, config) return config end}
end
package.preload["ui/widget/confirmbox"] = function()
    return {new=function(_, config) return config end}
end
package.preload["ui/widget/inputdialog"] = function()
    return {
        new=function(_, config)
            config.getInputText=function(self) return self.input end
            config.onShowKeyboard=function() keyboardOpened=true end
            return config
        end,
    }
end
package.preload["ui/widget/container/widgetcontainer"] = function()
    return {extend=function(_, class) return class end}
end
package.preload["datastorage"] = function()
    return {getSettingsDir=function() return "/tmp" end}
end
package.preload["libs/libkoreader-lfs"] = function()
    return {attributes=function() return "file" end}
end
_G.G_reader_settings = {
    readSetting=function(_,key,default) return settings[key] or default end,
    saveSetting=function(_,key,value) settings[key]=value end,
    delSetting=function(_,key) settings[key]=nil end,
    flush=function() end,
}
local Mail=dofile("assets/ilmail_k3.koplugin/main.lua")
local instance=Mail
instance.ui={menu={registerToMainMenu=function() end}}
instance:init()
local menu={}
instance:addToMainMenu(menu)
local account=menu.ilmail_k3.sub_item_table[5].sub_item_table
for i=1,8 do
    assert(account[i].keep_menu_open == true, "Menu closes after editing item "..i)
end
local function fill(index, value)
    closed=false
    keyboardOpened=false
    account[index].callback()
    assert(currentDialog and keyboardOpened, "Input dialog did not open")
    currentDialog.input=value
    currentDialog.buttons[1][2].callback()
    assert(closed, "Input dialog did not close")
    assert(settings["ilmail-k3-account"], "Settings not persisted")
end
fill(1, "tester@example.net")
assert(settings["ilmail-k3-account"].email == "tester@example.net")
fill(2, "imap.example.net")
assert(settings["ilmail-k3-account"].imap == "imap.example.net")
fill(3, "smtp.example.net")
assert(settings["ilmail-k3-account"].smtp == "smtp.example.net")
assert(#notices>=3)
print("mail K3: menu persistence and account setting fields OK")
