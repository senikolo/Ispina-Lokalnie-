-- IL Mail K3. All network connections originate on the Kindle; the phone only installs this plugin.
-- The backend does certificate and hostname verification. Passwords stay in RAM
-- until KOReader exits, and each command uses a short-lived local config file.
local UIManager = require("ui/uimanager")
local InfoMessage = require("ui/widget/infomessage")
local InputDialog = require("ui/widget/inputdialog")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local DataStorage = require("datastorage")
local ok_lfs, lfs = pcall(require, "libs/libkoreader-lfs")
if not ok_lfs then lfs = require("lfs") end
local plugin_dir = debug.getinfo(1, "S").source:sub(2):match("^(.*)/") or "."
local binary = plugin_dir .. "/mailk3"
local b64alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
local Mail = WidgetContainer:extend{ name="ilmail_k3", is_doc_only=false }

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
local function ask(title, initial, on_save, secret)
    local dlg=InputDialog:new{
        title=title, input=initial or "", fullscreen=false,
        input_type=secret and "password" or nil,
        save_callback=function(value)
            if type(value)~="string" then return false, "Nie podano wartości." end
            local result=on_save(value)
            if result==false then return false, "Sprawdź wprowadzone dane." end
            return true, "Zapisano."
        end,
    }
    UIManager:show(dlg)
    if dlg.onShowKeyboard then dlg:onShowKeyboard() end
end
function Mail:init()
    self.config=G_reader_settings:readSetting("ilmail-k3-account", {
        email="", imap="", smtp=""
    })
    self.password=nil
    self.items={}
    self.attachments={}
    self.current_uid=nil
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
        self.config[key]=value
        self:saveConfig()
    end)
end
function Mail:login()
    ask("Hasło aplikacji do poczty (tylko na tę sesję)","",function(value)
        if #value<1 or #value>300 or value:find("[%c]") then return false end
        self.password=value
        notice("Hasło jest przechowywane tylko w pamięci KOReadera. Nie zapisano go w ustawieniach.")
    end,true)
end
function Mail:ready()
    if not self.password or self.password=="" then
        notice("Najpierw wybierz „Hasło na tę sesję”. Do większości kont potrzebne jest hasło aplikacji.")
        return false
    end
    if not self.config.email:match("^[^%@%s]+@[^%@%s]+%.[^%@%s]+$")
        or not self.config.imap:match("^[%w%.%-]+$")
        or not self.config.smtp:match("^[%w%.%-]+$") then
        notice("Podaj e-mail oraz adresy IMAP i SMTP w ustawieniach.")
        return false
    end
    if lfs.attributes(binary,"mode")~="file" then
        notice("Brakuje silnika mailk3. Zaktualizuj wtyczkę w aplikacji Kindle przez Wi-Fi.")
        return false
    end
    return true
end
function Mail:run(command, args, recipient, subject, body)
    if not self:ready() then return nil end
    local settings_dir=DataStorage:getSettingsDir()
    math.randomseed(os.time())
    local cfg=settings_dir .. "/.ilmail-once-" .. os.time() .. "-" .. math.random(100000,999999)
    -- Private file is created with restrictive permissions before writing secrets.
    local touched=os.execute("umask 077; touch " .. quote(cfg) .. " && chmod 600 " .. quote(cfg))
    if touched~=0 and touched~=true then notice("Nie można zabezpieczyć konfiguracji sesji."); return nil end
    local file=io.open(cfg,"wb")
    if not file then os.remove(cfg);notice("Brak dostępu do tymczasowego pliku ustawień.");return nil end
    local line={
        self.config.email, self.password, self.config.imap, self.config.smtp,
        recipient or "", enc(subject or ""), enc(body or "")
    }
    for _,field in ipairs(line) do
        if field:find("[%c]") then file:close();os.remove(cfg);notice("Dane zawierają niedozwolone znaki.");return nil end
    end
    file:write(table.concat(line,"\n") .. "\n")
    file:close()
    local cmd=quote(binary).." --config "..quote(cfg).." "..command
    for _,arg in ipairs(args or {}) do
        if not tostring(arg):match("^[0-9]+$") then os.remove(cfg);return nil end
        cmd=cmd.." "..quote(arg)
    end
    local pipe=io.popen(cmd.." 2>/dev/null","r")
    if not pipe then os.remove(cfg); notice("Nie można uruchomić silnika poczty.");return nil end
    local data=pipe:read(1000000) or ""
    pipe:close()
    os.remove(cfg)
    if data=="" then notice("Silnik nie zwrócił odpowiedzi. Sprawdź zgodność ARMv6 i połączenie Wi-Fi.");return nil end
    local errorLine=data:match("^ERROR\t([^\r\n]+)")
    if errorLine then notice(dec(errorLine) or "Błąd połączenia pocztowego.");return nil end
    return data
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
    for row in data:gmatch("[^\r\n]+") do
        local typ,val=row:match("^([A-Z]+)\t(.+)$")
        if typ=="FROM" then from=dec(val) or ""
        elseif typ=="SUBJECT" then subject=dec(val) or ""
        elseif typ=="BODY" then body=dec(val) or ""
        elseif typ=="ATT" then
            local n,filename,bytes=row:match("^ATT\t(%d+)\t([^\t]+)\t(%d+)$")
            if n then
                self.attachments[#self.attachments+1]={index=n,name=dec(filename) or "plik",size=tonumber(bytes)}
            end
        end
    end
    local message="Od: "..from.."\nTemat: "..subject.."\n\n"..body
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
function Mail:send()
    if not self:ready() then return end
    ask("Adres odbiorcy","",function(recipient)
        if not recipient:match("^[^%@%s]+@[^%@%s]+%.[^%@%s]+$") then return false end
        ask("Temat", "", function(subject)
            if #subject>200 then return false end
            ask("Krótka wiadomość (maks. 5000 znaków)","",function(body)
                if #body<1 or #body>5000 then return false end
                local data=self:run("send",nil,recipient,subject,body)
                if data and data:match("^SENT") then notice("Serwer pocztowy przyjął wiadomość do wysłania.") end
            end)
        end)
    end)
end
function Mail:addToMainMenu(menu_items)
    menu_items.ilmail_k3={
        text="Poczta i książki · TEST",
        sorting_hint="more_tools",
        sub_item_table={
            {text="Odebrane — ostatnie 12 wiadomości",sub_item_table_func=function()return self:list()end},
            {text="Wyślij krótką wiadomość",callback=function()self:send()end},
            {text="Zapisz załącznik EPUB/PDF",sub_item_table_func=function()return self:saveMenu()end},
            {text="Konto i połączenie",sub_item_table={
                {text="Adres e-mail",callback=function()self:inputField("email","Adres konta e-mail")end},
                {text="Serwer IMAP (port 993)",callback=function()self:inputField("imap","Serwer IMAP — bez imaps://")end},
                {text="Serwer SMTP (port 465)",callback=function()self:inputField("smtp","Serwer SMTP — bez smtps://")end},
                {text="Hasło na tę sesję",callback=function()self:login()end},
                {text="Wyloguj / usuń hasło z pamięci",callback=function()self.password=nil;notice("Hasło usunięte z sesji.")end},
            }},
            {text="Informacje i bezpieczeństwo",callback=function()
                notice("Poczta łączy się bezpośrednio przez Wi-Fi Kindle'a. Wymaga działającego DNS, poprawnego zegara oraz TLS 1.2 i certyfikatów CA. Logowanie: hasło aplikacji, bez OAuth. Hasło nie jest zapisywane na stałe.")
            end},
        }
    }
end
return Mail
