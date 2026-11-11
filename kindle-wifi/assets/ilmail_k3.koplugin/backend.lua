-- Standalone LuaSec IMAP/SMTP transport on Kindle Keyboard 3's old kernel.
-- Mail is handled entirely by KOReader on-device. No phone/server relay.
local base=(debug.getinfo(1,"S").source:sub(2):match("^(.*)/") or ".")
local IMAP=dofile(base.."/imap.lua")
local MIME=dofile(base.."/mail.lua")
local socket=require("socket")
local ssl=require("ssl")
local mime=require("mime")
local lfs=require("libs/libkoreader-lfs")

local E={}
local DEST="/mnt/us/documents"
local MAX_TEXT=120000
local MAX_ATTACHMENT=10*1024*1024
local function b64(s) return (mime.b64(s):gsub("%s+$","")) end
local function q(s) return MIME.quote(s) end
local function checkEmail(s)
    return type(s)=="string" and #s<=250 and s:match("^[^%@%s<>]+@[^%@%s<>]+%.[^%@%s<>]+$")
        and not s:find("[%z\r\n]")
end
local function errorSafe(s)
    if type(s)~="string" then return "Nieznany błąd połączenia pocztowego." end
    return s:sub(1,350)
end
local function config(c, password)
    assert(type(c)=="table" and checkEmail(c.email),"Podaj prawidłowy adres konta.")
    assert(type(password)=="string" and password~="" and #password<=300,"Podaj hasło aplikacji do poczty.")
    assert(type(c.imap)=="string" and c.imap:match("^[%w%.%-]+$") and #c.imap<254,"Nieprawidłowy serwer IMAP.")
    assert(type(c.smtp)=="string" and c.smtp:match("^[%w%.%-]+$") and #c.smtp<254,"Nieprawidłowy serwer SMTP.")
    return {host=c.imap,port=993,username=c.email,password=password,ca_file=base.."/ca-bundle.crt"}
end
local function capture(im, cmd, limit)
    local out,size={},0
    local seen=0
    im:command(cmd, nil, function(chunk,total)
        seen=seen+1
        size=size+#chunk
        assert(size<=limit,"Wiadomość przekracza dozwolony rozmiar.")
        out[#out+1]=chunk
    end)
    assert(seen>0,"Serwer nie zwrócił danych wiadomości.")
    return table.concat(out)
end
local function headers(raw)
    raw=raw:gsub("\r\n[ \t]+"," ")
    local result={}
    for line in raw:gmatch("[^\r\n]+") do
        local key,value=line:match("^([%w%-]+):%s*(.*)$")
        if key then result[key:lower()]=MIME.decodeWords(value) end
    end
    return result
end
local function structure(im,uid)
    local lines=im:command("UID FETCH "..uid.." (UID BODYSTRUCTURE)")
    for _,line in ipairs(lines) do
        if line:match("^%* %d+ FETCH ") then
            local parsed=MIME.parse(line)
            local fields=parsed[4]
            if type(fields)=="table" then
                local values={}
                for i=1,#fields-1,2 do values[tostring(fields[i]):upper()]=fields[i+1] end
                if tostring(values.UID)==uid and type(values.BODYSTRUCTURE)=="table" then
                    return values.BODYSTRUCTURE
                end
            end
        end
    end
    error("Nie można odczytać struktury wiadomości.")
end
local function textPart(part,section)
    if type(part)~="table" then return nil end
    if type(part[1])=="table" then
        for i=1,#part do
            if type(part[i])=="table" then
                local sub=section=="" and tostring(i) or section.."."..i
                local val=textPart(part[i],sub)
                if val then return val end
            else break end
        end
        return nil
    end
    if tostring(part[1]):upper()=="TEXT" and tostring(part[2]):upper()=="PLAIN" then
        return {section=section=="" and "TEXT" or section,encoding=tostring(part[6] or "7bit"):lower(),size=tonumber(part[7]) or 0}
    end
end
local function getText(im,part)
    if not part then return "[Brak czytelnej części tekstowej.]" end
    local section=part.section
    assert(section:match("^[0-9.]+$") or section=="TEXT","Niedozwolony numer sekcji.")
    local fragments,bytes={},0
    local decode=MIME.decoder(part.encoding,function(chunk)
        if not chunk then return end
        bytes=bytes+#chunk
        assert(bytes<=MAX_TEXT,"Wiadomość jest zbyt długa.")
        fragments[#fragments+1]=chunk
    end)
    local sectionText=section=="TEXT" and "TEXT" or section
    im:command("UID FETCH "..im.mail_uid.." (UID BODY.PEEK["..sectionText.."])",nil,
        function(chunk,size,prefix) decode(chunk) end)
    decode(nil)
    return table.concat(fragments)
end
local function filterAttachments(parts)
    local out={}
    for _,p in ipairs(MIME.attachments(parts)) do
        if (p.format=="EPUB" or p.format=="PDF") and p.wire_size<=MAX_ATTACHMENT*2 then
            out[#out+1]=p
        end
    end
    return out
end
local function withImap(c,p,callback)
    local conf=config(c,p)
    local value,err=IMAP.run(conf, callback)
    if not value then error(errorSafe(err or "Błąd IMAP")) end
    return value
end
function E.list(c,p)
    return withImap(c,p,function(im)
        local ids={}
        for _,line in ipairs(im:command("UID SEARCH ALL")) do
            local words=line:match("^%* SEARCH(.*)")
            if words then for uid in words:gmatch("%d+") do ids[#ids+1]=uid end end
        end
        if #ids>12 then
            local last={}
            for i=#ids-11,#ids do last[#last+1]=ids[i] end
            ids=last
        end
        local rows={}
        for i=#ids,1,-1 do
            local uid=ids[i]
            if uid:match("^%d+$") then
                local ok,head=pcall(capture,im,
                    "UID FETCH "..uid.." (UID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])",16384)
                if ok then
                    local h=headers(head)
                    rows[#rows+1]=table.concat({"LIST",uid,b64(h.from or ""),b64(h.subject or "(bez tematu)"),b64(h.date or "")},"\t")
                end
            end
        end
        return table.concat(rows,"\n")
    end)
end
local function message(im,uid)
    assert(type(uid)=="string" and uid:match("^%d%d?%d?%d?%d?%d?%d?%d?%d?%d?%d?%d?$"),"Nieprawidłowy identyfikator e-mail.")
    local h=headers(capture(im,"UID FETCH "..uid.." (UID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])",16384))
    local str=structure(im,uid)
    return h,str
end
function E.read(c,p,uid)
    return withImap(c,p,function(im)
        local h,str=message(im,uid)
        local txt=textPart(str,"")
        local text="[Brak czytelnej części tekstowej.]"
        if txt then
            im.mail_uid=uid
            local ok,body=pcall(getText,im,txt)
            if ok then text=body else text="[Nie można odczytać treści tej wiadomości.]" end
        end
        local rows={
            "FROM\t"..b64(h.from or ""),
            "SUBJECT\t"..b64(h.subject or ""),
            "BODY\t"..b64(text),
        }
        for i,item in ipairs(filterAttachments(str)) do
            rows[#rows+1]=table.concat({"ATT",i,b64(item.name),item.size},"\t")
        end
        return table.concat(rows,"\n")
    end)
end
local function chooseName(name)
    local st=lfs.symlinkattributes(DEST,"mode")
    assert(st=="directory","Nie znaleziono folderu dokumentów.")
    name=MIME.safeFilename(name)
    assert(name:lower():match("%.epub$") or name:lower():match("%.pdf$"),"Nieobsługiwany załącznik.")
    local base,ext=name:match("^(.*)(%.[^.]+)$")
    for i=0,99 do
        local out=i==0 and name or (base.."-"..i..ext)
        local full=DEST.."/"..out
        if not lfs.symlinkattributes(full,"mode") then
            return full,out
        end
    end
    error("Zbyt wiele plików o podobnej nazwie.")
end
function E.save(c,p,uid,index)
    return withImap(c,p,function(im)
        local _,str=message(im,uid)
        local files=filterAttachments(str)
        index=tonumber(index)
        assert(index and index>=1 and index<=#files and index%1==0,"Nie znaleziono załącznika.")
        local chosen=files[index]
        chosen.uid=tonumber(uid)
        chosen.validity=im.validity
        assert(chosen.wire_size<=MAX_ATTACHMENT*2,"Załącznik jest za duży.")
        local full,name=chooseName(chosen.name)
        local tmp=full..".ilmail-download-"..os.time()
        local bytes,err=im:download(chosen,tmp)
        if not bytes then error(err or "Nie udało się pobrać załącznika.") end
        if bytes>MAX_ATTACHMENT then os.remove(tmp);error("Załącznik przekracza 10 MB.") end
        local f=io.open(tmp,"rb")
        if not f then os.remove(tmp);error("Nie można sprawdzić pobranego pliku.") end
        local sig=f:read(8) or ""
        f:close()
        local ext=name:lower():match("%.([^.]+)$")
        if (ext=="pdf" and sig:sub(1,5)~="%PDF-") or
            (ext=="epub" and sig:sub(1,2)~="PK") then
            os.remove(tmp);error("Załącznik nie jest prawidłowym plikiem EPUB lub PDF.")
        end
        if lfs.symlinkattributes(full,"mode") then os.remove(tmp);error("W międzyczasie pojawił się plik o tej nazwie.") end
        local ok,renameErr=os.rename(tmp,full)
        if not ok then os.remove(tmp);error("Błąd zapisu: "..tostring(renameErr)) end
        return "SAVED\t"..b64(name)
    end)
end
local function sendAll(conn,data)
    local sent=1
    while sent<=#data do
        local n,err,last=conn:send(data,sent)
        if not n then error("Wysyłanie SMTP przerwane: "..tostring(err)) end
        sent=n+1
    end
end
local function readReply(conn)
    local status
    for i=1,15 do
        local line,err=conn:receive("*l")
        assert(line,"SMTP nie odpowiada. Sprawdź Wi-Fi i serwer.")
        local code,separator=line:match("^(%d%d%d)([ -])")
        assert(code,"Serwer SMTP zwrócił nieprawidłową odpowiedź.")
        status=tonumber(code)
        if separator==" " then return status end
    end
    error("Zbyt długa odpowiedź SMTP.")
end
local function expect(conn,code)
    local got=readReply(conn)
    if got~=code then error("Serwer SMTP odrzucił operację (kod "..tostring(got)..").") end
end
local function smtpSession(c,p,recipient,subject,body)
    local cfg=config(c,p)
    assert(checkEmail(recipient),"Nieprawidłowy adres odbiorcy.")
    assert(type(subject)=="string" and #subject<=200 and not subject:find("[%z\r\n]"),"Nieprawidłowy temat.")
    assert(type(body)=="string" and #body>=1 and #body<=5000,"Wiadomość jest pusta lub zbyt długa.")
    local tcp=assert(socket.tcp(),"Nie można utworzyć połączenia SMTP.")
    tcp:settimeout(15)
    local connected=tcp:connect(c.smtp,465)
    assert(connected,"Nie udało się połączyć z SMTP na porcie 465.")
    local secured=assert(ssl.wrap(tcp,{
        mode="client",protocol="any",verify="peer",
        options={"all","no_sslv2","no_sslv3","no_tlsv1","no_tlsv1_1"},
        cafile=cfg.ca_file,
    }),"Nie można uruchomić bezpiecznego SMTP.")
    secured:settimeout(20)
    secured:sni(c.smtp)
    local ok,result=pcall(function()
        assert(secured:dohandshake(),"Weryfikacja TLS dla SMTP nie powiodła się.")
        IMAP.verifyHost(secured,c.smtp)
        expect(secured,220)
        sendAll(secured,"EHLO kindle.local\r\n");expect(secured,250)
        sendAll(secured,"AUTH LOGIN\r\n");expect(secured,334)
        sendAll(secured,b64(c.email).."\r\n");expect(secured,334)
        sendAll(secured,b64(p).."\r\n");expect(secured,235)
        sendAll(secured,"MAIL FROM:<"..c.email..">\r\n");expect(secured,250)
        sendAll(secured,"RCPT TO:<"..recipient..">\r\n");expect(secured,250)
        sendAll(secured,"DATA\r\n");expect(secured,354)
        local payload=b64(body:gsub("\r\n","\n"):gsub("\n","\r\n"))
        sendAll(secured,"From: <"..c.email..">\r\nTo: <"..recipient..">\r\n")
        sendAll(secured,"Subject: =?UTF-8?B?"..b64(subject).."?=\r\n")
        sendAll(secured,"MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
        for i=1,#payload,76 do sendAll(secured,payload:sub(i,i+75).."\r\n") end
        sendAll(secured,"\r\n.\r\n")
        expect(secured,250)
        pcall(sendAll,secured,"QUIT\r\n")
        return "SENT"
    end)
    pcall(secured.close,secured)
    if not ok then error(errorSafe(result)) end
    return result
end
function E.send(c,p,recipient,subject,body) return smtpSession(c,p,recipient,subject,body) end
return E
