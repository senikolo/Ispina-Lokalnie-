// mailk3 is an on-device, dependency-free IMAPS/SMTPS helper for KOReader.
// Cross-compile with CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=6.
// The Android phone is needed only for initial installation, not for mail access.
package main

import (
 "bufio"
 "bytes"
 "crypto/tls"
 "encoding/base64"
 "errors"
 "fmt"
 "html"
 "io"
 "mime"
 "mime/multipart"
 "net"
 "net/mail"
 "net/smtp"
 "net/textproto"
 "os"
 "path/filepath"
 "regexp"
 "strconv"
 "strings"
 "time"
)

const maxMail = 12 << 20
const maxPart = 10 << 20
const documents = "/mnt/us/documents"

type account struct { email, password, imap, smtp, recipient, subject, body string }
type imapClient struct { sock *tls.Conn; r *bufio.Reader; w *bufio.Writer; seq int }
type part struct { name, media, data string; attachment bool }

func validHost(h string) bool {
 if len(h)<3 || len(h)>253 || strings.HasPrefix(h,".") || strings.HasSuffix(h,".") { return false }
 for _,c := range h { if !((c>='a' && c<='z') || (c>='A' && c<='Z') || (c>='0' && c<='9') || c=='.' || c=='-') { return false } }
 return true
}
func oneLine(s string) bool { return !strings.ContainsAny(s,"\r\n\x00") }
func config(path string) (account,error) {
 var c account
 // Read once and immediately unlink: no password in process arguments or logs.
 data,e := os.ReadFile(path)
 _ = os.Remove(path)
 if e!=nil { return c,errors.New("Nie znaleziono jednorazowych ustawień konta.") }
 if len(data)>32768 { return c,errors.New("Ustawienia konta są zbyt duże.") }
 lines:=strings.Split(strings.TrimSuffix(string(data),"\n"),"\n")
 if len(lines)!=7 { return c,errors.New("Nieprawidłowe ustawienia konta.") }
 c.email,c.password,c.imap,c.smtp,c.recipient=lines[0],lines[1],lines[2],lines[3],lines[4]
 if !oneLine(c.email) || !oneLine(c.password) || !oneLine(c.recipient) || !validHost(c.imap) || !validHost(c.smtp) {
  return c,errors.New("Adres konta lub serwera jest nieprawidłowy.")
 }
 if _,e=mail.ParseAddress(c.email); e!=nil { return c,errors.New("Nieprawidłowy adres e-mail konta.") }
 sub,e:=base64.StdEncoding.DecodeString(lines[5]);if e!=nil {return c,e}
 body,e:=base64.StdEncoding.DecodeString(lines[6]);if e!=nil{return c,e}
 c.subject,c.body=string(sub),string(body)
 return c,nil
}
func quote(s string) string {
 return "\""+strings.ReplaceAll(strings.ReplaceAll(s,"\\","\\\\"),"\"","\\\"")+"\""
}
func connectTLS(host string, port string) (*tls.Conn,error) {
 dialer:=&net.Dialer{Timeout:12*time.Second}
 // Standard Go TLS validates both the certificate chain and the DNS name.
 conn,e:=tls.DialWithDialer(dialer,"tcp",net.JoinHostPort(host,port),&tls.Config{ServerName:host,MinVersion:tls.VersionTLS12})
 if e!=nil { return nil,fmt.Errorf("Bezpieczne połączenie z %s nie powiodło się: %w",host,e) }
 _=conn.SetDeadline(time.Now().Add(45*time.Second))
 return conn,nil
}
func newIMAP(c account) (*imapClient,error) {
 conn,e:=connectTLS(c.imap,"993");if e!=nil{return nil,e}
 im:=&imapClient{sock:conn,r:bufio.NewReader(conn),w:bufio.NewWriter(conn)}
 line,e:=im.r.ReadString('\n')
 if e!=nil || !strings.HasPrefix(line,"* OK") {conn.Close();return nil,errors.New("Serwer IMAP nie rozpoczął sesji.")}
 if _,e=im.cmd("LOGIN "+quote(c.email)+" "+quote(c.password)); e!=nil {conn.Close();return nil,errors.New("Serwer IMAP odrzucił logowanie. Sprawdź hasło aplikacji.")}
 if _,e=im.cmd("SELECT INBOX");e!=nil {conn.Close();return nil,errors.New("Nie można otworzyć skrzynki INBOX.")}
 return im,nil
}
var litRE=regexp.MustCompile("(?i)\\{([0-9]+)\\}\\r?\\n$")
func (c *imapClient) cmd(command string) ([]byte,error) {
 c.seq++
 tag:=fmt.Sprintf("A%04d",c.seq)
 if _,e:=c.w.WriteString(tag+" "+command+"\r\n");e!=nil{return nil,e}
 if e:=c.w.Flush();e!=nil{return nil,e}
 var out bytes.Buffer
 for out.Len()<maxMail+65536 {
  line,e:=c.r.ReadString('\n');if e!=nil{return nil,e}
  if strings.HasPrefix(line,tag+" ") {
   if !strings.HasPrefix(strings.ToUpper(line),tag+" OK") {return nil,errors.New("Serwer poczty odrzucił polecenie.")}
   return out.Bytes(),nil
  }
  if out.Len()+len(line)>maxMail+65536{return nil,errors.New("Odpowiedź serwera jest za duża.")}
  out.WriteString(line)
  if m:=litRE.FindStringSubmatch(line);m!=nil {
   n,_:=strconv.Atoi(m[1])
   if n<0 || n>maxMail || n+out.Len()>maxMail+65536 {return nil,errors.New("Wiadomość przekracza limit 12 MB.")}
   if _,e=io.CopyN(&out,c.r,int64(n));e!=nil{return nil,e}
  }
 }
 return nil,errors.New("Przekroczono limit danych IMAP.")
}
func (c *imapClient) close(){ _,_=c.cmd("LOGOUT");_=c.sock.Close() }
var uidRE=regexp.MustCompile("^[0-9]{1,12}$")
func (c *imapClient) search() ([]string,error) {
 raw,e:=c.cmd("UID SEARCH ALL");if e!=nil{return nil,e}
 var ids []string
 for _,line:=range strings.Split(string(raw),"\n") {
  line=strings.TrimSpace(line)
  if strings.HasPrefix(line,"* SEARCH") {
   for _,s:=range strings.Fields(strings.TrimPrefix(line,"* SEARCH")) {if uidRE.MatchString(s){ids=append(ids,s)}}
  }
 }
 if len(ids)>12 { ids=ids[len(ids)-12:] }
 for i,j:=0,len(ids)-1;i<j;i,j=i+1,j-1 {ids[i],ids[j]=ids[j],ids[i]}
 return ids,nil
}
func (c *imapClient) fetch(uid string, headerOnly bool) ([]byte,error) {
 if !uidRE.MatchString(uid) {return nil,errors.New("Nieprawidłowy identyfikator wiadomości.")}
 request:="BODY.PEEK[]"
 if headerOnly {request="BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)]"}
 raw,e:=c.cmd("UID FETCH "+uid+" ("+request+")");if e!=nil{return nil,e}
 // IMAP FETCH data are returned as a length-prefixed literal.
 m:=litRE.FindSubmatchIndex(raw)
 if m==nil {return nil,errors.New("Nie znaleziono treści wiadomości.")}
 n64,e:=strconv.ParseInt(string(raw[m[2]:m[3]]),10,64)
 if e!=nil || n64<=0 || n64>maxMail || int64(len(raw)-m[1])<n64 {return nil,errors.New("Nieprawidłowa długość wiadomości.")}
 return raw[m[1]:m[1]+int(n64)],nil
}
func decoded(h string)string{
 d:=new(mime.WordDecoder)
 if s,e:=d.DecodeHeader(h);e==nil{return s}
 return h
}
func b64(s string)string{return base64.StdEncoding.EncodeToString([]byte(s))}
func parseMessage(raw []byte)(*mail.Message,error){return mail.ReadMessage(bytes.NewReader(raw))}
func walk(h textproto.MIMEHeader, r io.Reader, depth int, result *[]part)error {
 if depth>5 {return errors.New("Zbyt wiele poziomów MIME.")}
 ct:=h.Get("Content-Type")
 media,params,e:=mime.ParseMediaType(ct)
 if e!=nil || media=="" { media="text/plain" }
 if strings.HasPrefix(media,"multipart/"){
  boundary:=params["boundary"];if boundary=="" {return errors.New("Brak granicy załączników MIME.")}
  mr:=multipart.NewReader(r,boundary)
  for {
   child,e:=mr.NextPart()
   if e==io.EOF {break}
   if e!=nil{return e}
   if e=walk(textproto.MIMEHeader(child.Header),child,depth+1,result);e!=nil{return e}
   _=child.Close()
  }
  return nil
 }
 disp,dp,_:=mime.ParseMediaType(h.Get("Content-Disposition"))
 name:=dp["filename"]
 if name=="" {name=params["name"]}
 name=decoded(name)
 encoding:=strings.ToLower(strings.TrimSpace(h.Get("Content-Transfer-Encoding")))
 var source io.Reader=r
 if encoding=="base64" { source=base64.NewDecoder(base64.StdEncoding,source) }
 if encoding=="quoted-printable" {source=quotedPrintable(source)}
 body,e:=io.ReadAll(io.LimitReader(source,maxPart+1))
 if e!=nil{return e}
 if len(body)>maxPart {return errors.New("Część wiadomości przekracza 10 MB.")}
 attach:=(name!="" || disp=="attachment")
 if len(*result)>=30{return errors.New("Za dużo części MIME.")}
 *result=append(*result,part{name:name,media:media,data:string(body),attachment:attach})
 return nil
}
func quotedPrintable(r io.Reader)io.Reader{return newQPReader(r)}
func extract(raw []byte)(*mail.Message,[]part,error){
 m,e:=parseMessage(raw);if e!=nil{return nil,nil,e}
 h:=make(textproto.MIMEHeader)
 for k,v:=range m.Header {h[k]=v}
 var parts []part
 if e=walk(h,m.Body,0,&parts);e!=nil{return nil,nil,e}
 return m,parts,nil
}
var tags=regexp.MustCompile("<[^>]*>")
func bodyText(parts []part) string {
 var fallback string
 for _,p:=range parts {
  if p.attachment {continue}
  if p.media=="text/plain" {return strings.TrimSpace(p.data)}
  if p.media=="text/html" && fallback=="" {
   fallback=html.UnescapeString(tags.ReplaceAllString(p.data," "))
  }
 }
 return strings.TrimSpace(fallback)
}
func attachments(parts []part)[]part{
 var out []part
 for _,p:=range parts {
  ext:=strings.ToLower(filepath.Ext(p.name))
  if p.attachment && (ext==".epub" || ext==".pdf") {out=append(out,p)}
 }
 return out
}
func saveFile(p part)(string,error){
 st,e:=os.Lstat(documents)
 if e!=nil || !st.IsDir() || st.Mode()&os.ModeSymlink!=0 {return "",errors.New("Folder dokumentów Kindle nie jest dostępny.")}
 name:=p.name
 // Strip paths, separators and control characters. Never trust an attachment filename.
 name=strings.ReplaceAll(name,"\\","/")
 name=filepath.Base(name)
 name=strings.Map(func(r rune)rune{if r<32 || r==127 || r=='/' || r=='\\' || r==':'{return '_'};return r},name)
 if len(name)>150 {name=name[len(name)-150:]}
 ext:=strings.ToLower(filepath.Ext(name))
 if (ext!=".epub" && ext!=".pdf") || name=="" || name=="." {return "",errors.New("Niedozwolony typ załącznika.")}
 base:=strings.TrimSuffix(name,filepath.Ext(name))
 for n:=0;n<100;n++ {
  target:=name
  if n>0 {target=fmt.Sprintf("%s-%d%s",base,n,ext)}
  f,e:=os.OpenFile(filepath.Join(documents,target),os.O_WRONLY|os.O_CREATE|os.O_EXCL,0644)
  if os.IsExist(e){continue}
  if e!=nil{return "",e}
  _,e=f.Write([]byte(p.data))
  closeErr:=f.Close()
  if e!=nil || closeErr!=nil { _=os.Remove(filepath.Join(documents,target));return "",errors.New("Nie udało się zapisać załącznika.")}
  return target,nil
 }
 return "",errors.New("Za dużo plików o tej samej nazwie.")
}
func list(c account)error{
 im,e:=newIMAP(c);if e!=nil{return e};defer im.close()
 ids,e:=im.search();if e!=nil{return e}
 for _,uid:=range ids{
  raw,e:=im.fetch(uid,true)
  if e!=nil {continue}
  m,e:=parseMessage(raw);if e!=nil {continue}
  fmt.Printf("LIST\t%s\t%s\t%s\t%s\n",uid,b64(decoded(m.Header.Get("From"))),b64(decoded(m.Header.Get("Subject"))),b64(m.Header.Get("Date")))
 }
 return nil
}
func read(c account,uid string,saveIndex int)error{
 im,e:=newIMAP(c);if e!=nil{return e};defer im.close()
 raw,e:=im.fetch(uid,false);if e!=nil{return e}
 m,parts,e:=extract(raw);if e!=nil{return e}
 aa:=attachments(parts)
 if saveIndex>0 {
  if saveIndex>len(aa) {return errors.New("Nie znaleziono tego załącznika.")}
  name,e:=saveFile(aa[saveIndex-1]);if e!=nil{return e}
  fmt.Println("SAVED\t"+b64(name));return nil
 }
 fmt.Println("FROM\t"+b64(decoded(m.Header.Get("From"))))
 fmt.Println("SUBJECT\t"+b64(decoded(m.Header.Get("Subject"))))
 body:=bodyText(parts)
 if len(body)>120000 {body=body[:120000]+"\n\n[Tekst skrócony]"}
 fmt.Println("BODY\t"+b64(body))
 for i,p:=range aa{fmt.Printf("ATT\t%d\t%s\t%d\n",i+1,b64(p.name),len(p.data))}
 return nil
}
func send(c account)error{
 if _,e:=mail.ParseAddress(c.recipient);e!=nil{return errors.New("Podaj prawidłowy adres odbiorcy.")}
 if strings.TrimSpace(c.body)=="" || len(c.body)>5000 || len(c.subject)>200{return errors.New("Wiadomość jest pusta albo zbyt długa.")}
 conn,e:=connectTLS(c.smtp,"465");if e!=nil{return e};defer conn.Close()
 client,e:=smtp.NewClient(conn,c.smtp);if e!=nil{return e};defer client.Close()
 if ok,_:=client.Extension("AUTH");!ok{return errors.New("Serwer SMTP nie udostępnia uwierzytelnienia.")}
 if e=client.Auth(smtp.PlainAuth("",c.email,c.password,c.smtp));e!=nil{return errors.New("SMTP odrzucił logowanie. Sprawdź hasło aplikacji.")}
 if e=client.Mail(c.email);e!=nil{return errors.New("SMTP nie przyjął adresu nadawcy.")}
 recipient,_:=mail.ParseAddress(c.recipient)
 if e=client.Rcpt(recipient.Address);e!=nil{return errors.New("Serwer SMTP nie przyjął odbiorcy.")}
 w,e:=client.Data();if e!=nil{return e}
 body:=strings.ReplaceAll(strings.ReplaceAll(c.body,"\r\n","\n"),"\n","\r\n")
 // Base64 makes Unicode and long lines safe for older SMTP servers.
 raw:=base64.StdEncoding.EncodeToString([]byte(body))
 var payload strings.Builder
 payload.WriteString("From: "+c.email+"\r\nTo: "+recipient.Address+"\r\n")
 payload.WriteString("Subject: "+mime.BEncoding.Encode("UTF-8",c.subject)+"\r\n")
 payload.WriteString("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
 for len(raw)>76 {payload.WriteString(raw[:76]+"\r\n");raw=raw[76:]}
 payload.WriteString(raw+"\r\n")
 _,e=io.WriteString(w,payload.String());if e!=nil {_=w.Close();return e}
 if e=w.Close();e!=nil{return errors.New("SMTP nie potwierdził przyjęcia wiadomości.")}
 _=client.Quit()
 fmt.Println("SENT");return nil
}
func main(){
 if len(os.Args)<2 {fmt.Println("ERROR\t"+b64("Brakuje polecenia."));os.Exit(2)}
 if os.Args[1]=="diag" {fmt.Println("DIAG\tDostępny silnik mailk3 (IMAPS 993 / SMTPS 465, TLS 1.2+)");return}
 if len(os.Args)<4 || os.Args[1]!="--config" {fmt.Println("ERROR\t"+b64("Brakuje konfiguracji."));os.Exit(2)}
 c,e:=config(os.Args[2])
 if e==nil {
  switch os.Args[3]{
  case "list":e=list(c)
  case "read":if len(os.Args)<5 {e=errors.New("Brakuje UID.")}else{e=read(c,os.Args[4],0)}
  case "save":if len(os.Args)<6 {e=errors.New("Brakuje UID lub numeru załącznika.")}else{
   n,err:=strconv.Atoi(os.Args[5]);if err!=nil || n<1 || n>30{e=errors.New("Nieprawidłowy numer załącznika.")}else{e=read(c,os.Args[4],n)}
  }
  case "send":e=send(c)
  default:e=errors.New("Nieznane polecenie.")
  }
 }
 if e!=nil {fmt.Println("ERROR\t"+b64(e.Error()));os.Exit(1)}
}
