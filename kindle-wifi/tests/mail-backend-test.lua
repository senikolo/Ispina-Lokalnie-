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
assert(Mail.safeFilename("../../Test.epub")==".._.._Test.epub")
assert(Mail.decodeWords("=?UTF-8?B?S3NpxIV6a2E=?=")=="Książka")
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
print("mail K3: MIME, certificate hostname, credentials validation OK")
