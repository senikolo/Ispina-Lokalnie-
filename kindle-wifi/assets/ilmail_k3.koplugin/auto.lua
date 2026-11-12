-- Automatic EPUB/PDF inbox discovery and explicit user-controlled download.
-- All scanning and saving happens on the Kindle via the existing secure backend.
local UIManager=require("ui/uimanager")
local ConfirmBox=require("ui/widget/confirmbox")
local InfoMessage=require("ui/widget/infomessage")

local function say(text)
    UIManager:show(InfoMessage:new{ text=text, timeout=6 })
end
local function trim(s,n)
    return tostring(s or ""):gsub("[%c]"," "):gsub("%s+"," "):sub(1,n or 60)
end
local function b64decode(s)
    local alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    local bits,buffer,out=0,0,{}
    if type(s)~="string" then return "" end
    for i=1,#s do
        local c=s:sub(i,i)
        if c=="=" then break end
        local at=alphabet:find(c,1,true)
        if not at then return "" end
        buffer=buffer*64+at-1
        bits=bits+6
        if bits>=8 then
            bits=bits-8
            local shift=2^bits
            out[#out+1]=string.char(math.floor(buffer/shift)%256)
            buffer=buffer%shift
        end
    end
    return table.concat(out)
end

return function(Mail)
    local oldInit=Mail.init
    function Mail:init()
        oldInit(self)
        self.mailSeen=G_reader_settings:readSetting("ilmail-k3-saved-attachments") or {}
        if type(self.mailSeen)~="table" then self.mailSeen={} end
        self.autoFiles={}
        self.autoSelection={}
        self.scannedOnce=false
        self.mailEntryScheduled=false
    end

    local function stored(self,key)
        for _,v in ipairs(self.mailSeen) do
            if v==key then return true end
        end
        return false
    end
    local function markStored(self,key)
        if stored(self,key) then return end
        self.mailSeen[#self.mailSeen+1]=key
        if #self.mailSeen>300 then table.remove(self.mailSeen,1) end
        G_reader_settings:saveSetting("ilmail-k3-saved-attachments",self.mailSeen)
        G_reader_settings:flush()
    end

    function Mail:scanAndOffer()
        if not self:ready() then return end
        say("Sprawdzanie nowych wiadomości i załączników…")
        local data=self:run("scan")
        if not data then return end
        local found,scanned,skipped={},0,0
        for row in data:gmatch("[^\r\n]+") do
            local n,failed=row:match("^SCAN\t(%d+)\t(%d+)$")
            if n then scanned,skipped=tonumber(n),tonumber(failed) end
            local uid,index,name,size,from,subject,validity =
                row:match("^FILE\t(%d+)\t(%d+)\t([^\t]*)\t(%d+)\t([^\t]*)\t([^\t]*)\t(%d+)$")
            if uid then
                local decoded=b64decode(name)
                local account=self.config.email or ""
                local key=account.."|"..validity.."|"..uid.."|"..index
                if not stored(self,key) then
                    found[#found+1]={
                        uid=uid,index=index,name=decoded~="" and decoded or "Załącznik",
                        size=tonumber(size),from=b64decode(from),subject=b64decode(subject),key=key,
                    }
                end
            end
        end
        self.autoFiles=found
        local selected={}
        for _,file in ipairs(found) do
            selected[file.key]=self.autoSelection[file.key]~=false
        end
        self.autoSelection=selected
        self.scannedOnce=true
        if #found==0 then
            say("Sprawdzono "..scanned.." ostatnich wiadomości. Brak nowych plików EPUB/PDF."..
                (skipped>0 and "\nNie udało się odczytać: "..skipped or ""))
            return
        end
        UIManager:show(ConfirmBox:new{
            text="Znaleziono "..#found.." plik(ów) EPUB/PDF w ostatnich "..scanned..
                " wiadomościach.\n\nCzy chcesz wybrać pliki do zapisania na Kindle?"..
                (skipped>0 and "\nNie odczytano: "..skipped.." wiadomości." or ""),
            ok_text="Wybierz",
            cancel_text="Później",
            ok_callback=function() self:showAutoFiles() end,
        })
    end

    function Mail:selectedCount()
        local count=0
        for _,file in ipairs(self.autoFiles) do
            if self.autoSelection[file.key] then count=count+1 end
        end
        return count
    end

    function Mail:autoMenu()
        if not self.password then
            return {{text="Odblokuj pocztę kodem PIN.",enabled=false}}
        end
        if not self.scannedOnce then
            return {{text="Wybierz „Sprawdź nowe pliki” w menu poczty.",enabled=false}}
        end
        if #self.autoFiles==0 then
            return {{text="Brak nowych plików EPUB/PDF.",enabled=false}}
        end
        local items={{
            text_func=function() return "Zapisz zaznaczone ("..self:selectedCount()..")" end,
            enabled_func=function() return self:selectedCount()>0 end,
            callback=function() self:saveAutoFiles() end,
            keep_menu_open=true,
            separator=true,
        }}
        for _,file in ipairs(self.autoFiles) do
            local key=file.key
            items[#items+1]={
                text=trim(file.name,45).." · "..math.floor((file.size or 0)/1024).." KB",
                checked_func=function() return self.autoSelection[key]~=false end,
                callback=function(menu)
                    self.autoSelection[key]=not self.autoSelection[key]
                    if menu and type(menu.updateItems)=="function" then menu:updateItems() end
                end,
                keep_menu_open=true,
                help_text=trim(file.subject,70).." / "..trim(file.from,70),
            }
        end
        return items
    end

    function Mail:showAutoFiles()
        local ok,Menu=pcall(require,"ui/widget/menu")
        local okdev,Device=pcall(require,"device")
        if not ok or not okdev then
            say("Otwórz w menu Poczta pozycję „Załączniki EPUB/PDF”.")
            return
        end
        local screen=Device.screen
        self.autoFilesMenu=Menu:new{
            title="Pliki EPUB/PDF do zapisania",
            item_table=self:autoMenu(),
            width=screen:getWidth(),
            height=screen:getHeight(),
        }
        UIManager:show(self.autoFilesMenu)
    end

    function Mail:saveAutoFiles()
        local selected={}
        for _,file in ipairs(self.autoFiles) do
            if self.autoSelection[file.key] then selected[#selected+1]=file end
        end
        if #selected==0 then say("Najpierw zaznacz plik.");return end
        UIManager:show(ConfirmBox:new{
            text="Zapisać "..#selected.." wybrane pliki w folderze documents/Poczta?",
            ok_text="Zapisz",
            cancel_text="Anuluj",
            ok_callback=function()
                local successes,failed=0,0
                for _,file in ipairs(selected) do
                    local data=self:run("save",{file.uid,file.index})
                    if data and data:match("^SAVED\t") then
                        markStored(self,file.key)
                        successes=successes+1
                    else
                        failed=failed+1
                    end
                end
                local remaining={}
                for _,file in ipairs(self.autoFiles) do
                    if not stored(self,file.key) then remaining[#remaining+1]=file end
                end
                self.autoFiles=remaining
                say("Zapisano: "..successes..". Niepowodzenia: "..failed..
                    ".\nFolder: documents/Poczta")
                if self.autoFilesMenu and type(self.autoFilesMenu.switchItemTable)=="function" then
                    self.autoFilesMenu:switchItemTable("Pliki EPUB/PDF do zapisania",self:autoMenu())
                end
            end,
        })
    end

    function Mail:enterAutoMail()
        if self.password and self.password~="" then
            self:scanAndOffer()
        elseif self.vault then
            -- Prompt appears after the main-menu transition has finished.
            self:unlock(function() self:scanAndOffer() end)
        else
            say("Pierwsza konfiguracja: Konto i połączenie → podaj adres e-mail, IMAP, SMTP, hasło aplikacji, a następnie zapisz hasło z kodem PIN.")
        end
    end

    local oldAdd=Mail.addToMainMenu
    function Mail:addToMainMenu(menu_items)
        oldAdd(self,menu_items)
        local root=menu_items.ilmail_k3
        root.text="Poczta i książki"
        local items=root.sub_item_table
        -- Preserve the existing account menu at position 5 and all mail actions.
        items[4]={
            text="Załączniki EPUB/PDF – wybierz pliki",
            sub_item_table_func=function() return self:autoMenu() end,
        }
        items[#items+1]={
            text="Sprawdź nowe wiadomości i pliki",
            keep_menu_open=true,
            callback=function() self:scanAndOffer() end,
        }
        items[#items+1]={
            text="Zapisz załącznik z otwartego e-maila",
            sub_item_table_func=function() return self:saveMenu() end,
        }
        root.sub_item_table=nil
        root.sub_item_table_func=function()
            if not self.mailEntryScheduled then
                self.mailEntryScheduled=true
                if type(UIManager.scheduleIn)=="function" then
                    UIManager:scheduleIn(0.1,function()
                        self.mailEntryScheduled=false
                        self:enterAutoMail()
                    end)
                else
                    self.mailEntryScheduled=false
                    self:enterAutoMail()
                end
            end
            return items
        end
    end
end
