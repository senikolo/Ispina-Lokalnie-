-- IL Mail K3. All network connections originate on the Kindle; the phone only installs this plugin.
-- The backend does certificate and hostname verification. Passwords stay in RAM
-- until KOReader exits, and each command uses a short-lived local config file.
local UIManager = require("ui/uimanager")
local InfoMessage = require("ui/widget/infomessage")
local ConfirmBox = require("ui/widget/confirmbox")
local InputDialog = require("ui/widget/inputdialog")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local DataStorage = require("datastorage")
local ok_lfs, lfs = pcall(require, "libs/libkoreader-lfs")
if not ok_lfs then lfs = require("lfs") end
local plugin_dir = debug.getinfo(1, "S").source:sub(2):match("^(.*)/") or "."
local b64alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
local Mail = WidgetContainer:extend{ name="ilmail_k3", is_doc_only=false }
local TEMPLATES = {
    "Dziękuję za wiadomość i poświęcony czas.",
    "Dziękuję za odpowiedź. Bardzo doceniam pomoc.",
    "Dziękuję za przesłane informacje. Są dla mnie bardzo przydatne.",
    "Dziękuję za materiały. Zapoznam się z nimi.",
    "Potwierdzam otrzymanie wiadomości. Dziękuję.",
    "Z przyjemnością skorzystam z propozycji. Dziękuję.",
    "Czy mogę prosić o dodatkowe informacje?",
    "Dziękuję za wyjaśnienia. Wszystko jest już dla mnie jasne.",
    "Zajmę się tym i wrócę z odpowiedzią.",
    "Serdecznie pozdrawiam i dziękuję za kontakt.",
}
local function validEmail(s)
    return type(s)=="string" and #s<=250 and s:match("^[^%@%s<>]+@[^%@%s<>]+%.[^%@%s<>]+$")~=nil
        and not s:find("[%z\r\n]")
end
local function addressFromHeader(raw)
    if type(raw)~="string" or raw:find("[%z\r\n]") then return nil end
    local addr=raw:match("<([^<>]+)>") or raw:match("^%s*([^%s,;]+@[^%s,;]+)%s*$")
    return validEmail(addr) and addr or nil
end

local function quote(s)
    return "'" .. tostring(s):gsub("'", "'\"'\"'") .. "'"
end
local function enc(s)
    s = tostring(s or "")
    return ((s:gsub(".", function(ch)
        local n = ch:byte()
        local bits = ""
        for i=7,0,-1 do bits=bits .. ((math.floor(n / (2^i)) % 2 == 1) and "1" or "0") end
        return bits
    end) .. "0000"):gsub("%d%d%d%d%d%d", function(group)
        local v = 0
        for i=1,6 do v = v * 2 + tonumber(group:sub(i,i)) end
        return b64alphabet:sub(v+1,v+1)
    end) .. ({"", "==", "="})[#s % 3 + 1])
end
local function dec(s)
    local v = 0
    local bits = 0
    local out = {}
    for i=1,#s do
        local char=s:sub(i,i)
        if char=="=" then break end
        local n=b64alphabet:find(char,1,true)
        if not n then return nil end
        v=v*64+(n-1)
        bits=bits+6
        if bits>=8 then
            bits=bits-8
            local base=2^bits
            out[#out+1]=string.char(math.floor(v/base)%256)
            v=v%base
        end
    end
    return table.concat(out)
end
local function clean(s, n)
    s=tostring(s or ""):gsub("[%c]", " "):gsub("%s+", " ")
    return s:sub(1,n or 100)
end
local function notice(message)
    UIManager:show(InfoMessage:new{ text=message, timeout=7 })
end
local function ask(title, initial, on_save, secret, after_save)
    local dialog
    dialog=InputDialog:new{
        title=title, input=initial or "", input_type="text",
        text_type=secret and "password" or nil,
        buttons={{{text="Anuluj",id="close",callback=function()
            UIManager:close(dialog)
        end},{
            text="Zapisz",is_enter_default=true,callback=function()
                local value=dialog:getInputText()
                if type(value)~="string" then
                    notice("Sprawdź wprowadzone dane.")
                    return
                end
                -- Never let a settings write throw out of the KOReader
                -- dialog callback: retain the input so it can be corrected.
                local ok, accepted = pcall(on_save, value)
                if not ok then
                    notice("Nie zapisano ustawienia. Spróbuj ponownie.")
                    return
                end
                if accepted == false then
                    notice("Sprawdź wprowadzone dane.")
                    return
                end
                -- Close the input dialog before displaying confirmation.
                -- Showing an InfoMessage over an open InputDialog can
                -- cause an unexpected return to the reader on older K3 UI.
                UIManager:close(dialog)
                if type(after_save)=="function" then
                    local callback_ok=pcall(after_save)
                    if not callback_ok then notice("Nie udało się przejść do następnego kroku.") end
                elseif after_save then notice(after_save) end
            end,
        }}},
    }
    UIManager:show(dialog)
    dialog:onShowKeyboard()
end
function Mail:init()
    local stored=G_reader_settings:readSetting("ilmail-k3-account") or {}
    -- Old settings can be missing one field after an interrupted migration.
    if type(stored)~="table" then stored={} end
    self.config={
        email=type(stored.email)=="string" and stored.email or "",
        imap=type(stored.imap)=="string" and stored.imap or "",
        smtp=type(stored.smtp)=="string" and stored.smtp or "",
    }
    self.password=nil
    self.vault=G_reader_settings:readSetting("ilmail-k3-vault")
    self.custom_templates=G_reader_settings:readSetting("ilmail-k3-templates") or {}
    self.badPins=0
    self.lockedUntil=0
    self.items={}
    self.attachments={}
    self.current_uid=nil
    self.current=nil
    self.ui.menu:registerToMainMenu(self)
end
function Mail:saveConfig()
    G_reader_settings:saveSetting("ilmail-k3-account",self.config)
    G_reader_settings:flush()
end
function Mail:inputField(key, title)
    ask(title,self.config[key],function(value)
        if #value>250 or value:find("[%c]") then return false end
        if key=="imap" or key=="smtp" then
            if not value:match("^[%w%.%-]+$") then return false end
        end
        if key=="email" and value~=self.config.email and self.vault then
            -- A saved token must not be silently reused for a different account.
            self:forgetSaved()
        end
        local previous=self.config[key]
        self.config[key]=value
        local saved=pcall(self.saveConfig,self)
        if not saved then self.config[key]=previous; return false end
        return true
    end,false,"Zapisano ustawienie. Możesz wprowadzić następny parametr.")
end
function Mail:login()
    ask("Hasło aplikacji do poczty (tylko na tę sesję)","",function(value)
        if #value<1 or #value>300 or value:find("[%c]") then return false end
        self.password=value
    end,true,"Hasło jest przechowywane tylko w pamięci KOReadera.")
end
function Mail:lock()
    self.password=nil
    self.items={}
    self.attachments={}
    self.current_uid=nil
    self.current=nil
    self.scannedOnce=false
    notice("Poczta zablokowana. Hasło usunięto z bieżącej sesji.")
end
function Mail:forgetSaved()
    self.vault=nil
    G_reader_settings:delSetting("ilmail-k3-vault")
    G_reader_settings:flush()
end
function Mail:remember()
    if not self.password or self.password=="" then
        notice("Najpierw wpisz hasło aplikacji do poczty.")
        return
    end
    local ok,Vault=pcall(dofile,plugin_dir.."/vault.lua")
    if not ok then
        notice("Brak zgodnego szyfrowania OpenSSL. Hasło nie zostanie zapisane.")
        return
    end
    local candidate
    ask("Ustal nowy PIN – dokładnie 4 cyfry","",function(pin)
        if not Vault.validPIN(pin) or #pin~=4 or pin=="2282" then
            notice("Wybierz własny czterocyfrowy PIN, inny niż 2282.")
            return false
        end
        candidate=pin
        return true
    end,true,function()
        -- Open the second dialog only after the first has closed.
        ask("Powtórz czterocyfrowy PIN","",function(repeated)
            if repeated~=candidate then notice("PIN-y są różne.");return false end
            local sealed,record=pcall(Vault.seal,self.password,candidate)
            if not sealed then
                notice("Nie można zaszyfrować hasła. Niczego nie zapisano.")
                return false
            end
            local verified,clear=pcall(Vault.open,record,candidate)
            if not verified or clear~=self.password then
                notice("Błąd weryfikacji szyfrowania. Niczego nie zapisano.")
                return false
            end
            self.vault=record
            G_reader_settings:saveSetting("ilmail-k3-vault",record)
            G_reader_settings:flush()
            self.badPins=0
            candidate=nil
            return true
        end,true,"Hasło zapisano w postaci zaszyfrowanej. Od teraz używaj PIN-u.")
    end)
end
function Mail:unlock(on_success)
    if self.password then
        if on_success then on_success() else notice("Poczta jest już odblokowana.") end
        return
    end
    if not self.vault then notice("Nie zapisano hasła. Otwórz Konto i połączenie.");return end
    if os.time() < self.lockedUntil then
        notice("Za dużo prób. Spróbuj ponownie później.")
        return
    end
    local ok,Vault=pcall(dofile,plugin_dir.."/vault.lua")
    if not ok then notice("Brak OpenSSL. Nie można odszyfrować hasła.");return end
    local unlocked=false
    ask("Poczta – wprowadź czterocyfrowy PIN","",function(pin)
        -- An existing saved 6-8 digit PIN still works after upgrading.
        if not Vault.validPIN(pin) then return false end
        local success,clear=pcall(Vault.open,self.vault,pin)
        if not success or not clear then
            self.badPins=self.badPins+1
            if self.badPins>=3 then
                self.badPins=0
                self.lockedUntil=os.time()+60
                notice("Trzy błędne próby. Blokada na minutę.")
            else
                notice("Nieprawidłowy PIN. Pozostało prób: "..(3-self.badPins))
            end
            return true
        end
        self.password=clear
        self.badPins=0
        self.lockedUntil=0
        unlocked=true
        return true
    end,true,function()
        if unlocked then
            if on_success then
                on_success()
            else
                notice("Poczta odblokowana.")
            end
        end
    end)
end
function Mail:removeSaved()
    if not self.vault then notice("Nie ma zapisanego hasła.");return end
    UIManager:show(ConfirmBox:new{
        text="Usunąć zaszyfrowane hasło i wyłączyć odblokowywanie PIN-em? Konto i szablony zostaną zachowane.",
        ok_text="Usuń",
        ok_callback=function()
            self:forgetSaved()
            self:lock()
        end,
    })
end
function Mail:ready()
    if not self.password or self.password=="" then
        notice(self.vault and "Poczta jest zablokowana. Wybierz „Odblokuj pocztę (PIN)”." or "Najpierw wpisz hasło aplikacji do poczty.")
        return false
    end
    if not self.config.email:match("^[^%@%s]+@[^%@%s]+%.[^%@%s]+$")
        or not self.config.imap:match("^[%w%.%-]+$")
        or not self.config.smtp:match("^[%w%.%-]+$") then
        notice("Podaj e-mail oraz adresy IMAP i SMTP w ustawieniach.")
        return false
    end
    if lfs.attributes(plugin_dir.."/backend.lua","mode")~="file" then
        notice("Brakuje silnika LuaSec. Zaktualizuj wtyczkę w aplikacji Kindle przez Wi-Fi.")
        return false
    end
    return true
end
function Mail:run(command, args, recipient, subject, body, replyid)
    if self.busy then notice("Trwa już operacja pocztowa."); return nil end
    if not self:ready() then return nil end
    local success,backend=pcall(dofile,plugin_dir.."/backend.lua")
    if not success then
        notice("Nie można uruchomić poczty. Sprawdź biblioteki LuaSocket/LuaSec w diagnostyce.")
        return nil
    end
    local method=backend[command]
    if type(method)~="function" then notice("Nieznane polecenie poczty.");return nil end
    self.busy = true
    local ok,result
    if command=="read" then
        ok,result=pcall(method,self.config,self.password,args[1])
    elseif command=="save" then
        ok,result=pcall(method,self.config,self.password,args[1],args[2])
    elseif command=="send" then
        ok,result=pcall(method,self.config,self.password,recipient,subject,body,replyid)
    else
        ok,result=pcall(method,self.config,self.password)
    end
    self.busy = false
    if not ok then
        local reason = tostring(result):gsub("^.-:%d+: ", "")
        local lower = reason:lower()
        local msg = reason
        if lower:find("timed out", 1, true) or lower:find("timeout", 1, true) then
            msg = "Serwer pocztowy nie odpowiada. Sprawdź Wi-Fi i spróbuj ponownie."
        elseif lower:find("certificate", 1, true) or lower:find("tls", 1, true) then
            msg = "Nie udało się nawiązać bezpiecznego połączenia. Sprawdź datę Kindle'a i certyfikaty."
        end
        notice(msg:sub(1,270))
        return nil
    end
    return result
end

function Mail:diagnose()
    local checks = {}
    for _, module in ipairs({"socket", "ssl", "mime", "libs/libkoreader-lfs"}) do
        local ok = pcall(require, module)
        checks[#checks+1] = (ok and "OK: " or "BRAK: ") .. module
    end
    checks[#checks+1] = "Certyfikaty CA: " ..
        (lfs.attributes(plugin_dir.."/ca-bundle.crt","mode") == "file" and "obecne" or "brak")
    checks[#checks+1] = "Porty poczty: IMAP 993, SMTP 465."
    checks[#checks+1] = "Certyfikat serwera jest sprawdzany podczas połączenia."
    local ok, TextViewer = pcall(require, "ui/widget/textviewer")
    if ok then
        UIManager:show(TextViewer:new{title="Diagnostyka poczty",text=table.concat(checks,"\n")})
    else
        notice(table.concat(checks,"\n"))
    end
end
function Mail:list()
    local data=self:run("list")
    if not data then return {{text="Nie udało się pobrać wiadomości.",enabled=false}} end
    self.items={}
    for row in data:gmatch("[^\r\n]+") do
        local uid,from,subject,date=row:match("^LIST\t(%d+)\t([^\t]+)\t([^\t]+)\t([^\t]+)$")
        if uid then
            self.items[#self.items+1]={uid=uid,from=dec(from) or "",subject=dec(subject) or "(bez tematu)",date=dec(date) or ""}
        end
    end
    if #self.items==0 then return {{text="Skrzynka jest pusta.",enabled=false}} end
    local menu={}
    for _,item in ipairs(self.items) do
        local uid=item.uid
        menu[#menu+1]={
            text=clean(item.subject,48) .. " · " .. clean(item.from,24),
            callback=function() self:read(uid) end,
            keep_menu_open=true,
        }
    end
    return menu
end
function Mail:read(uid)
    local data=self:run("read",{uid})
    if not data then return end
    local from,subject,body="","",""
    self.attachments={}
    self.current_uid=uid
    local replyto,messageid="",""
    for row in data:gmatch("[^\r\n]+") do
        local typ,val=row:match("^([A-Z]+)\t(.+)$")
        if typ=="FROM" then from=dec(val) or ""
        elseif typ=="REPLYTO" then replyto=dec(val) or ""
        elseif typ=="MESSAGEID" then messageid=dec(val) or ""
        elseif typ=="SUBJECT" then subject=dec(val) or ""
        elseif typ=="BODY" then body=dec(val) or ""
        elseif typ=="ATT" then
            local n,filename,bytes=row:match("^ATT\t(%d+)\t([^\t]+)\t(%d+)$")
            if n then
                self.attachments[#self.attachments+1]={index=n,name=dec(filename) or "plik",size=tonumber(bytes)}
            end
        end
    end
    self.current={uid=uid,from=from,replyto=replyto,subject=subject,messageid=messageid}
    local message="Od: "..from.."\nTemat: "..subject.."\n\n"..body
    message=message.."\n\nOdpowiedź: Poczta i książki → Odpowiedz na wiadomość."
    if #self.attachments>0 then message=message.."\n\nZałączniki EPUB/PDF: "..#self.attachments..
        "\nWróć do Poczty i wybierz „Zapisz załącznik”." end
    local ok,TextViewer=pcall(require,"ui/widget/textviewer")
    if ok then
        UIManager:show(TextViewer:new{title=clean(subject,80),text=message})
    else
        notice(message:sub(1,1800))
    end
end
function Mail:saveMenu()
    if not self.current_uid or #self.attachments==0 then
        return {{text="Najpierw przeczytaj wiadomość z załącznikiem.",enabled=false}}
    end
    local menu={}
    for _,item in ipairs(self.attachments) do
        local uid,index=self.current_uid,item.index
        menu[#menu+1]={
            text=clean(item.name,60),
            callback=function()
                local data=self:run("save",{uid,index})
                if not data then return end
                local saved=data:match("^SAVED\t([^\r\n]+)")
                if saved then
                    notice("Zapisano w dokumentach Kindle: "..clean(dec(saved) or "plik",100))
                else notice("Nie potwierdzono zapisu pliku.") end
            end,
            keep_menu_open=true,
        }
    end
    return menu
end
function Mail:compose(recipient, subject, body, replyid)
    if not self:ready() then return end
    ask("Odbiorca",recipient or "",function(to)
        if not validEmail(to) then return false end
        ask("Temat",subject or "",function(topic)
            if #topic>200 or topic:find("[%z\r\n]") then return false end
            ask("Treść wiadomości (maks. 5000 znaków)",body or "",function(message)
                if #message<1 or #message>5000 or message:find("%z") then return false end
                UIManager:show(ConfirmBox:new{
                    text="Wysłać wiadomość do "..clean(to,75).."?\n\nTemat: "..clean(topic,75)..
                        "\n\n"..clean(message,160),
                    ok_text="Wyślij",
                    ok_callback=function()
                        local data=self:run("send",nil,to,topic,message,replyid)
                        if data and data:match("^SENT") then
                            notice("Wiadomość została przyjęta przez serwer SMTP.")
                        end
                    end,
                })
            end)
        end)
    end)
end
function Mail:send()
    self:compose("","","",nil)
end
function Mail:replyWith(body)
    if not self:ready() then return end
    local m=self.current
    if not m then notice("Najpierw otwórz wiadomość, na którą chcesz odpowiedzieć.");return end
    local to=addressFromHeader(m.replyto) or addressFromHeader(m.from)
    if not to then notice("Nie można odczytać adresu nadawcy. Użyj nowej wiadomości.");return end
    local subject=m.subject or ""
    if not subject:lower():match("^re%s*:") then subject="Re: "..subject end
    self:compose(to,subject,body,m.messageid)
end
function Mail:saveTemplate()
    if #self.custom_templates>=10 then notice("Limit 10 własnych szablonów.");return end
    ask("Nowy własny szablon (maks. 500 znaków)","",function(value)
        if #value<1 or #value>500 or value:find("%z") then return false end
        self.custom_templates[#self.custom_templates+1]=value
        G_reader_settings:saveSetting("ilmail-k3-templates",self.custom_templates)
        G_reader_settings:flush()
        notice("Szablon zapisany.")
    end)
end
function Mail:replyMenu()
    if not self.current then
        return {{text="Najpierw otwórz wiadomość w Odebranych.",enabled=false}}
    end
    local menu={{text="Napisz własną odpowiedź",callback=function()self:replyWith("")end,keep_menu_open=true}}
    for i,t in ipairs(TEMPLATES) do
        menu[#menu+1]={text=t,callback=function()self:replyWith(t)end,keep_menu_open=true}
    end
    for i,t in ipairs(self.custom_templates) do
        menu[#menu+1]={text="[Mój "..i.."] "..clean(t,65),
            callback=function()self:replyWith(t)end,keep_menu_open=true}
    end
    return menu
end
function Mail:addToMainMenu(menu_items)
    menu_items.ilmail_k3={
        text="Poczta i książki · TEST",
        sorting_hint="more_tools",
        sub_item_table={
            {text="Odebrane — ostatnie 12 wiadomości",sub_item_table_func=function()return self:list()end},
            {text="Wyślij nową wiadomość",callback=function()self:send()end},
            {text="Odpowiedz na wiadomość — 10 szablonów",sub_item_table_func=function()return self:replyMenu()end},
            {text="Zapisz załącznik EPUB/PDF",sub_item_table_func=function()return self:saveMenu()end},
            {text="Konto i połączenie",sub_item_table={
                {text="Adres e-mail",keep_menu_open=true,callback=function()self:inputField("email","Adres konta e-mail")end},
                {text="Serwer IMAP (port 993)",keep_menu_open=true,callback=function()self:inputField("imap","Serwer IMAP — bez imaps://")end},
                {text="Serwer SMTP (port 465)",keep_menu_open=true,callback=function()self:inputField("smtp","Serwer SMTP — bez smtps://")end},
                {text="Hasło na tę sesję",keep_menu_open=true,callback=function()self:login()end},
                {text="Zapamiętaj hasło (utwórz PIN)",keep_menu_open=true,callback=function()self:remember()end},
                {text="Odblokuj pocztę (PIN)",keep_menu_open=true,callback=function()self:unlock()end},
                {text="Zablokuj pocztę",keep_menu_open=true,callback=function()self:lock()end},
                {text="Usuń zapamiętane hasło i PIN",keep_menu_open=true,callback=function()self:removeSaved()end},
            }},
            {text="Szablony odpowiedzi",sub_item_table={
                {text="Zapisz własny szablon",keep_menu_open=true,callback=function()self:saveTemplate()end},
                {text="Usuń wszystkie własne szablony",callback=function()
                    UIManager:show(ConfirmBox:new{
                        text="Usunąć własne szablony? Dziesięć gotowych odpowiedzi pozostanie.",
                        ok_text="Usuń",
                        ok_callback=function()
                            self.custom_templates={}
                            G_reader_settings:delSetting("ilmail-k3-templates")
                            G_reader_settings:flush()
                            notice("Usunięto własne szablony.")
                        end,
                    })
                end},
            }},
            {text="Diagnostyka poczty",keep_menu_open=true,callback=function()self:diagnose()end},
            {text="Informacje i bezpieczeństwo",callback=function()
                notice("Poczta łączy się bezpośrednio przez Wi-Fi Kindle'a. Wymaga działającego DNS, poprawnego zegara oraz TLS 1.2 i certyfikatów CA. Logowanie: hasło aplikacji, bez OAuth. Hasło możesz zapisać w formie zaszyfrowanej i odblokowywać PIN-em. PIN 4-cyfrowy chroni głównie przed przypadkowym dostępem, nie przed odczytem pamięci przez zaawansowaną osobę.")
            end},
        }
    }
end
-- Keep the existing mail functions and menus; extend them with automatic
-- EPUB/PDF discovery and user-controlled downloads on the Kindle.
dofile(plugin_dir.."/auto.lua")(Mail)
return Mail
