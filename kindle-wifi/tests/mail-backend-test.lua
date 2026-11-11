local basedir="assets/ilmail_k3.koplugin"
package.preload["libs/libkoreader-lfs"]=function() return require("lfs") end
local Mail=dofile(basedir.."/mail.lua")
local IMAP=dofile(basedir.."/imap.lua")
local backend=dofile(basedir.."/backend.lua")
local function fails(fn)
    local ok=pcall(fn)
    assert(not ok,"unsafe input unexpectedly accepted")
end

assert(Mail.quote("test@sample.net")=='"test@sample.net"')
fails(function()Mail.quote("evil\r\nheader")end)
assert(Mail.safeFilename("../../Test.epub")=="_.._.._Test.epub")
assert(Mail.decodeWords("=?UTF-8?B?S3NpxIXFvGth?=")=="Książka")
assert(IMAP.hostnameMatches("imap.gmail.com","*.gmail.com"))
assert(not IMAP.hostnameMatches("gmail.com","*.gmail.com"))
assert(not IMAP.hostnameMatches("evilgmail.com","*.gmail.com"))
assert(not IMAP.hostnameMatches("imap.gmail.com","*.example.com"))

local parsed=Mail.parse('* 1 FETCH (UID 123 BODYSTRUCTURE ("APPLICATION" "PDF" NIL NIL NIL "BASE64" 24 NIL ("ATTACHMENT" ("FILENAME" "book.pdf")) NIL NIL))')
assert(parsed[4][2]==123)
local files=Mail.attachments(parsed[4][4])
assert(#files==1 and files[1].name=="book.pdf" and files[1].section=="1")
fails(function()backend.list({email="broken",imap="imap.gmail.com",smtp="smtp.gmail.com"},"app-password")end)
fails(function()backend.send({email="sender@example.com",imap="imap.example.com",smtp="smtp.example.com"},"password","bad\nrecipient","Hi","body")end)
fails(function()backend.send({email="sender@example.com",imap="imap.example.com",smtp="smtp.example.com"},"password","receiver@example.com","evil\r\nBcc: x","body")end)
-- The error reported on Kindle: optional Reply-To/Message-ID can be
-- absent and LuaSocket mime.b64("") can return nil. Exercise the exact
-- private encoder used by the message viewer, without network access.
local with_imap_index, saved_with_imap
for i=1,20 do
    local name, value = debug.getupvalue(backend.read,i)
    if not name then break end
    if name=="withImap" then with_imap_index,saved_with_imap=i,value;break end
end
assert(with_imap_index,"Missing IMAP wrapper")
local base64_verified=false
debug.setupvalue(backend.read,with_imap_index,function(_,_,message_callback)
    for i=1,20 do
        local name,value=debug.getupvalue(message_callback,i)
        if not name then break end
        if name=="b64" then
            assert(value("")=="","Empty headers must not crash")
            assert(value(nil)=="","Missing headers must not crash")
            assert(value("abc")=="YWJj","Nonempty Base64 must remain correct")
            base64_verified=true
            return "TEST"
        end
    end
    error("Base64 encoder not found")
end)
local ok, result=pcall(backend.read,{},{},"123")
debug.setupvalue(backend.read,with_imap_index,saved_with_imap)
assert(ok and result=="TEST" and base64_verified,"Base64 regression test failed")

print("mail K3: MIME, certificate hostname, credentials validation OK")
