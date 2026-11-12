-- Regression: saving account parameters must keep the KOReader mail menu alive.
local settings, currentDialog, keyboardOpened, closed = {}, nil, false, false
local lastConfirm
local notices = {}
package.preload["ui/uimanager"] = function()
    return {
        show = function(_, widget)
            if widget.buttons then currentDialog=widget elseif widget.ok_callback then lastConfirm=widget else notices[#notices+1]=widget.text end
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
local allItems=menu.ilmail_k3.sub_item_table_func()
local account=allItems[5].sub_item_table
assert(type(allItems[4].sub_item_table_func)=="function","Automatic attachments submenu missing")
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

-- Automatic scan: metadata only, explicit confirmation before download,
-- selectable items and no repeated downloads for the same mailbox UID.
instance.password="app-password"
local scan_calls,save_calls=0,0
instance.run=function(_,command,args)
    if command=="scan" then
        scan_calls=scan_calls+1
        return "SCAN\t2\t0\nFILE\t101\t1\tYm9vay5lcHVi\t1234\tQWxpY2U=\tVGVzdA==\t42\nFILE\t102\t1\tcGFwZXIucGRm\t3456\tQm9i\tUERG\t42"
    end
    if command=="save" then
        save_calls=save_calls+1
        assert(args[1]=="101" and args[2]=="1","Only selected EPUB must be saved")
        return "SAVED\tYm9vay5lcHVi"
    end
    error("Unexpected mail action: "..tostring(command))
end
instance:scanAndOffer()
assert(scan_calls==1 and #instance.autoFiles==2,"Expected two unsaved EPUB/PDF attachments")
assert(lastConfirm and lastConfirm.ok_text=="Wybierz","Scan must request user consent")
local items=instance:autoMenu()
assert(items[2].checked_func() and items[3].checked_func(),"Files should start selected")
items[3].callback()
assert(instance:selectedCount()==1 and not items[3].checked_func(),"Selection toggle failed")
instance:saveAutoFiles()
assert(lastConfirm.ok_text=="Zapisz","Saving requires confirmation")
lastConfirm.ok_callback()
assert(save_calls==1 and #instance.autoFiles==1,"Only the selected file should be saved")
assert(#settings["ilmail-k3-saved-attachments"]==1,"Saved UID/UIDVALIDITY history not persisted")
instance:scanAndOffer()
assert(#instance.autoFiles==1,"Saved attachment must not be offered again")
assert(#notices>=3)
print("mail K3: settings, automatic scan, selection, consent and history OK")
