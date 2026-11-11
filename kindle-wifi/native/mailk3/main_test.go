package main

import (
 "encoding/base64"
 "fmt"
 "strings"
 "testing"
)

func TestHostValidation(t *testing.T) {
 for _,v:=range []string{"imap.example.org","mail.domain.co.uk"} {
  if !validHost(v) {t.Errorf("rejected %q",v)}
 }
 for _,v:=range []string{"","-bad/evil","imap.example.org:993","foo\nbar"} {
  if validHost(v) {t.Errorf("accepted %q",v)}
 }
}
func TestMimeAttachment(t *testing.T) {
 text:="MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"x\"\r\nFrom: Reader <reader@example.org>\r\nSubject: =?UTF-8?B?S3NpxIV6a2E=?=\r\n\r\n"
 text+="--x\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nHello Kindle\r\n"
 text+="--x\r\nContent-Type: application/epub+zip; name=\"book.epub\"\r\nContent-Disposition: attachment; filename=\"book.epub\"\r\nContent-Transfer-Encoding: base64\r\n\r\n"+base64.StdEncoding.EncodeToString([]byte("EPUB-DATA"))+"\r\n--x--\r\n"
 m,parts,e:=extract([]byte(text))
 if e!=nil {t.Fatal(e)}
 if !strings.Contains(bodyText(parts),"Hello Kindle") {t.Fatal("missing body")}
 if m.Header.Get("From")=="" {t.Fatal("missing header")}
 a:=attachments(parts)
 if len(a)!=1 || a[0].name!="book.epub" || a[0].data!="EPUB-DATA" {t.Fatalf("attachments: %#v",a)}
}
func TestImapLiteralRegex(t *testing.T) {
 s:=fmt.Sprintf("* 1 FETCH (BODY[] {5}\r\nhello)\r\n")
 m:=litRE.FindSubmatchIndex([]byte(s))
 if m==nil || s[m[1]:m[1]+5]!="hello" {t.Fatal("literal not found")}
}
func TestUID(t *testing.T) {
 for _,v:=range []string{"1","1234567890"} {
  if !uidRE.MatchString(v) {t.Fatal(v)}
 }
 for _,v:=range []string{"1:5","-1","0)\nA"} {
  if uidRE.MatchString(v) {t.Fatal(v)}
 }
}
