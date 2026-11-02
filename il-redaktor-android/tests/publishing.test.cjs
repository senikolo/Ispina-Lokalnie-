/*
 * IL Redaktor Smart publication contract tests.
 * Mock transport: NEVER writes to the live Ispina Lokalnie WordPress site.
 * Validates the actual in-app JS, including confirmation, status and retry safety.
 */
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

function wpFixture(id=200,status='publish'){return {id,status,title:{raw:'Wpis'},content:{raw:'Treść na stronie'},excerpt:{raw:'Zajawka'},modified:'2026-10-31T10:00:00',categories:[10543],tags:[10],featured_media:0,date_gmt:'2026-10-31T10:00:00',link:'https://ispinalokalnie.wordpress.com/?p='+id};}
function setup(initialStorage=[],nativeData) {
  const html = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/index.html"), "utf8");
  const code = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
  assert.ok(code, "Missing UI script");
  const dom = new Map(), documentListeners=new Map();
  function node(id) {
    if (!dom.has(id)) {
      const classes = new Set(["hidden"]);
      dom.set(id, {
        id, value:"", textContent:"", innerHTML:"", className:"",
        selectionStart:0,selectionEnd:0,selectionDirection:"none",
        style:{}, dataset:{}, srcdoc:"",
        classList: {
          add(...xs){xs.forEach(x=>classes.add(x))},
          remove(...xs){xs.forEach(x=>classes.delete(x))},
          contains(x){return classes.has(x)},
          toggle(x,force){if(force===undefined? !classes.has(x):force)classes.add(x);else classes.delete(x)}
        },
        checked:false,disabled:false,
        addEventListener(){},focus(){},setSelectionRange(a,b){this.selectionStart=a;this.selectionEnd=b},
        setAttribute(){},removeAttribute(){},
      });
    }
    return dom.get(id);
  }
  const storage = new Map(initialStorage);
  const IL = {status(){},assistantStatus(){},openSite(){},showPublishedPage(){}};
  if(nativeData!==undefined){let data=nativeData;IL.readDraftLibrary=()=>JSON.stringify({ok:true,exists:data!==null,data});IL.writeDraftLibrary=p=>{data=p;return JSON.stringify({ok:true})};}
  const c = {
    window:{IL, scrollTo(){},addEventListener(type,listener){documentListeners.set('window:'+type,{listener})}},IL,document:{
      visibilityState:'visible',getElementById:node,querySelectorAll(){return []},addEventListener(type,listener,capture){documentListeners.set(type,{listener,capture})},documentElement:{setAttribute(){}}
    },setTimeout,clearTimeout,setInterval,clearInterval,console,Date,TextEncoder,TextDecoder,URL,
    localStorage:{
      getItem(k){return storage.get(k)||null},
      setItem(k,v){storage.set(k,v)},
      removeItem(k){storage.delete(k)}
    },confirm(){return true},btoa,atob
  };
  vm.createContext(c);
  vm.runInContext(code,c);
  // Mock WordPress GET returns the exact article created by the preceding POST.
  // Tests that explicitly supply a mismatching field keep it for negative checks.
  let latestPost=null,activeRpc=c.rpc;
  Object.defineProperty(c,'rpc',{configurable:true,
    get(){return activeRpc;},
    set(impl){activeRpc=async(method,url,body)=>{
      const response=await impl(method,url,body);
      if(method==='POST'&&/^posts(?:\/\d+)?$/.test(url)&&body&&response&&Number(response.id)>0)
        latestPost={id:Number(response.id),body};
      if(method==='GET'&&/^posts\/\d+\?context=edit$/.test(url)&&latestPost&&Number(url.match(/^posts\/(\d+)/)?.[1])===latestPost.id
         &&response&&typeof response==='object'&&!Array.isArray(response)){
        const stored=latestPost.body;
        return {...response,
          title:Object.prototype.hasOwnProperty.call(response,'title')?response.title:{raw:stored.title,rendered:stored.title},
          content:Object.prototype.hasOwnProperty.call(response,'content')?response.content:{raw:stored.content},
          excerpt:Object.prototype.hasOwnProperty.call(response,'excerpt')?response.excerpt:{raw:stored.excerpt},
          categories:Object.prototype.hasOwnProperty.call(response,'categories')?response.categories:stored.categories,
          featured_media:Object.prototype.hasOwnProperty.call(response,'featured_media')?response.featured_media:(stored.featured_media||0),
          tags:Object.prototype.hasOwnProperty.call(response,'tags')?response.tags:(stored.tags||[])
        };
      }
      return response;
    };}
  });
  vm.runInContext("connected=true;document.getElementById('post-title').value='Testowy artykuł';document.getElementById('post-body').value='Treść testowa';document.getElementById('post-mode').value='text';document.getElementById('post-category').value='10543';document.getElementById('post-media').value='0';",c);
  return {c,node,storage,documentListeners,run:(script)=>vm.runInContext(script,c)};
}

test("backup reminder only records a completed export, never a canceled picker",()=>{
  const fs=require("node:fs"),path=require("node:path");
  const ui=fs.readFileSync(path.join(__dirname,"../app/src/main/assets/index.html"),"utf8");
  assert.match(ui,/if\(id==="draft-backup"\)\{if\(ok\)/);
  assert.match(ui,/id="backup-health"/);
  assert.match(ui,/function renderBackupHealth/);
});
test("1.29.1-test.1 free Radar date selector, local SEO and diagnostic actions are exposed",()=>{ 
  const fs=require("node:fs"),path=require("node:path");
  const ui=fs.readFileSync(path.join(__dirname,"../app/src/main/assets/index.html"),"utf8");
  const java=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java"),"utf8");
  const rss=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/FreeIspinaSearchClient.java"),"utf8");
  assert.match(ui,/id="research-days"/);
  assert.match(ui,/function runLocalSeoAudit/);
  assert.match(ui,/function runLocalDiagnostics/);
  assert.match(java,/askResearchWithDays/);
  assert.match(rss,/entry".equals\(name\)/);
  assert.match(rss,/sourcesAreFactChecked", false/);
});
test("publishing issues exactly one POST and verifies by GET before SUCCESS",async()=>{
  const a=setup(),calls=[];
  a.c.rpc=async(method,p,body)=>{
    calls.push({method,path:p,body});
    return method==="POST"?{id:987654,status:"publish"}:{id:987654,status:"publish",title:"Testowy artykuł"};
  };
  await a.run("savePost('publish')");
  assert.equal(calls.filter(x=>x.method==="POST").length,1);
  assert.equal(calls[0].path,"posts");
  assert.equal(calls[0].body.status,"publish");
  assert.equal(calls[1].method,"GET");
  assert.equal(calls[1].path,"posts/987654?context=edit");
  assert.equal(a.node("finish-title").textContent,"✓ Sukces! Artykuł został opublikowany na Ispina Lokalnie.");
  assert.match(a.node("finish-detail").textContent,/ponowny odczyt/);
  assert.match(a.node("edit-status").textContent,/Opublikowany/);
});

test("draft sends only draft, and confirms persisted draft status",async()=>{
  const a=setup(),calls=[];
  a.c.rpc=async(method,p,body)=>{
    calls.push({method,p,body});
    return method==="POST"?{id:41,status:"draft"}:{id:41,status:"draft"};
  };
  await a.run("savePost('draft')");
  assert.equal(calls[0].body.status,"draft");
  assert.match(a.node("finish-title").textContent,/Szkic zapisany/);
});

test("false success is prevented when WordPress returns a different status",async()=>{
  const a=setup(),calls=[];
  a.c.rpc=async(method,p,body)=>{
    calls.push({method,p,body});
    return method==="POST"?{id:42,status:"publish"}:{id:42,status:"draft"};
  };
  await a.run("savePost('publish')");
  assert.equal(calls.filter(x=>x.method==="POST").length,1);
  assert.equal(a.node("finish-title").textContent,"STATUS NIEPOTWIERDZONY");
  assert.match(a.node("edit-status").textContent,/Do sprawdzenia/);
});

test("failed read after write never triggers duplicate publication",async()=>{
  const a=setup(),calls=[];
  a.c.rpc=async(method,p,body)=>{
    calls.push({method,p,body});
    if(method==="POST")return {id:51,status:"publish"};
    throw new Error("brak odpowiedzi na GET");
  };
  await a.run("savePost('publish')");
  assert.equal(calls.filter(x=>x.method==="POST").length,1);
  assert.equal(a.node("finish-title").textContent,"ZAPIS NIEZWERYFIKOWANY");
  assert.match(a.node("finish-detail").textContent,/Nie klikaj ponownie/);
});

test("rejected POST shows failure, never SUCCESS",async()=>{
  const a=setup(),calls=[];
  a.c.rpc=async(method,p,body)=>{calls.push({method,p,body});throw new Error("HTTP 403");};
  await a.run("savePost('publish')");
  assert.equal(calls.length,1);
  assert.match(a.node("finish-title").textContent,/PUBLIKACJA NIEZAKOŃCZONA/);
  assert.match(a.node("finish-detail").textContent,/HTTP 403/);
});

test("publishing failure explains WordPress write permissions in plain Polish",async()=>{
  const a=setup();
  a.c.rpc=async()=>{throw new Error("WordPress: HTTP 403 Forbidden: posts.create disabled")};
  await a.run("savePost('publish')");
  assert.equal(a.node("finish-title").textContent,"PUBLIKACJA NIEZAKOŃCZONA");
  assert.match(a.node("finish-detail").textContent,/WordPress nie pozwolił/);
  assert.match(a.node("finish-detail").textContent,/ustawieniach dostępu MCP WordPress.com/);
  assert.match(a.node("finish-detail").textContent,/HTTP 403/);
  assert.match(a.node("finish-detail").textContent,/sprawdź ostatni zapis/i);
});


test("confirmed publication opens the live article inside the app and keeps browser access optional",async()=>{
  const a=setup();
  const expected="https://ispinalokalnie.wordpress.com/2026/10/31/probny-artykul/";
  const openedInApp=[],openedInBrowser=[];
  a.c.IL.showPublishedPage=(url)=>openedInApp.push(url);
  a.c.IL.openSite=(url)=>openedInBrowser.push(url);
  a.c.rpc=async(method,p,body)=>method==="POST"?
    {id:67,status:"publish"}:
    {id:67,status:"publish",link:expected};
  await a.run("savePost('publish')");
  assert.equal(a.node("finish-preview").textContent,"📖 Zobacz artykuł na WordPressie");
  assert.equal(a.node("finish-open").textContent,"Otwórz w przeglądarce");
  assert.equal(a.node("view-published-post").classList.contains("hidden"),false);
  assert.match(a.node("publication-phase").textContent,/SUKCES/);
  a.run("openFinishUrl()");
  a.run("openPublishedPreview()");
  a.run("openPublishedPost()");
  assert.deepEqual(openedInBrowser,[expected]);
  assert.deepEqual(openedInApp,[expected,expected]);
});

test("missing schedule date shows the actionable error in the visible banner without internal field IDs",async()=>{
  const a=setup();let writes=0;
  a.c.rpc=async()=>{writes++;throw Error("Unexpected request")};
  await a.run("savePost('future')");
  assert.equal(writes,0);
  assert.match(a.node("working-state").textContent,/Wybierz termin/);
  assert.doesNotMatch(a.node("working-state").textContent,/post-output/);
});

test("related-reading completion is not labeled as publication success",async()=>{
  const a=setup();a.c.rpc=async()=>[{id:8,status:'publish',title:'Testowy tekst'}];
  await a.run("suggestRelatedReading()");
  assert.match(a.node("working-state").textContent,/Gotowe:/);
  assert.doesNotMatch(a.node("working-state").textContent,/SUKCES/);
});

test("native host wires real confirmation before loading the editor and supports both user decisions",()=>{
  const activity=fs.readFileSync(path.join(__dirname,'../app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java'),'utf8');
  assert.ok(activity.indexOf('editor.setWebChromeClient(new WebChromeClient()')<activity.indexOf('editor.loadUrl('));
  assert.match(activity,/onJsConfirm/);assert.match(activity,/result\.confirm\(\)/);assert.match(activity,/result\.cancel\(\)/);
  assert.match(activity,/setPositiveButton\("Potwierdzam"/);assert.match(activity,/setNegativeButton\("Anuluj"/);
});

test("immediate publish ignores the optional schedule and shows success only after readback",async()=>{
  const a=setup();let releaseWrite;let signals=0;
  a.node("post-schedule").value="2000-01-01T12:00";
  a.c.IL.publicationSuccess=()=>signals++;
  a.c.rpc=(method,p,body)=>{
    if(method==="POST"){
      assert.equal(body.status,"publish");assert.equal(body.date,undefined);
      return new Promise(resolve=>{releaseWrite=resolve});
    }
    return Promise.resolve({id:801,status:"publish",link:"https://ispinalokalnie.wordpress.com/?p=801"});
  };
  const pending=a.run("savePost('publish')");
  assert.equal(a.node("publication-progress").classList.contains("hidden"),false);
  assert.equal(a.node("save-publish").disabled,true);assert.equal(signals,0);
  releaseWrite({id:801,status:"publish"});await pending;
  assert.equal(a.node("publication-progress").classList.contains("hidden"),true);
  assert.equal(a.node("save-publish").disabled,false);
  assert.equal(a.node("finish-symbol").textContent,"✓ SUKCES!");assert.equal(signals,1);
  assert.equal(a.node("finish-preview").textContent,"📖 Zobacz artykuł na WordPressie");
});

test("publication failure closes the waiting window and never plays the success signal",async()=>{
  const a=setup();let signals=0;a.c.IL.publicationSuccess=()=>signals++;
  a.c.rpc=async()=>{throw Error("HTTP 403")};
  await a.run("savePost('publish')");
  assert.equal(a.node("publication-progress").classList.contains("hidden"),true);
  assert.equal(a.node("finish-dialog").classList.contains("hidden"),false);
  assert.equal(signals,0);assert.equal(a.node("save-publish").disabled,false);
});

test("publication sound preference can disable sound without hiding success",async()=>{
  const a=setup();let signals=0;a.storage.set("il_publication_sound","false");
  a.c.IL.publicationSuccess=()=>signals++;
  a.c.rpc=async()=>({id:802,status:"publish"});
  await a.run("savePost('publish')");
  assert.equal(a.node("finish-symbol").textContent,"✓ SUKCES!");assert.equal(signals,0);
});

test("reader recommendations use published IL articles and insert a confirmed internal link",async()=>{
  const a=setup();
  a.node("post-title").value="Historia mostu w Ispinie";
  a.node("post-body").value="Historia mostu i dawnych przepraw w Ispinie.";
  const publishedUrl="https://ispinalokalnie.wordpress.com/historia-mostu/";
  a.c.rpc=async(method,path)=>{
    assert.equal(method,"GET");
    assert.match(path,/status=publish/);
    return [
      {id:712,status:"publish",title:{rendered:"Historia mostu w Ispinie"},excerpt:{rendered:"Opowieść o dawnych przeprawach."},link:publishedUrl},
      {id:713,status:"draft",title:{rendered:"Historia mostu – szkic"},excerpt:{rendered:"Nieopublikowany tekst."},link:"https://ispinalokalnie.wordpress.com/?p=713"},
      {id:714,status:"publish",title:{rendered:"Prace w ogrodzie"},excerpt:{rendered:"Porady ogrodnicze."},link:"https://ispinalokalnie.wordpress.com/ogrod/"}
    ];
  };
  await a.run("suggestRelatedReading()");
  assert.match(a.node("reading-suggestions").innerHTML,/Historia mostu w Ispinie/);
  assert.doesNotMatch(a.node("reading-suggestions").innerHTML,/Nieopublikowany tekst/);
  a.run("appendReadingLink(712)");
  assert.match(a.node("post-body").value,/href="https:\/\/ispinalokalnie\.wordpress\.com\/historia-mostu\/"/);
  assert.match(a.node("post-body").value,/Przeczytaj także/);
  assert.equal(a.node("post-mode").value,"html");
});

test("published article without canonical link falls back to real public WordPress ?p URL, not admin",async()=>{
  const a=setup(),opened=[];
  a.c.IL.openSite=(url)=>opened.push(url);
  a.c.rpc=async(method)=>method==="POST"?{id:69,status:"publish"}:{id:69,status:"publish"};
  await a.run("savePost('publish')");
  a.run("openFinishUrl()");
  assert.equal(opened[0],"https://ispinalokalnie.wordpress.com/?p=69");
});

test("a failing tags lookup may be skipped after explicit confirmation so it does not block publishing",async()=>{
  const a=setup(), calls=[];
  a.node("post-tags").value="Ispina";
  a.c.rpc=async(method,p,body)=>{
    calls.push({method,p,body});
    if(p.startsWith("tags"))throw new Error("Tag API disabled");
    return method==="POST"?{id:70,status:"publish"}:{id:70,status:"publish"};
  };
  await a.run("savePost('publish')");
  assert.equal(calls.filter(c=>c.p==="posts").length,1);
  assert.equal(calls.find(c=>c.p==="posts").body.tags,undefined);
  assert.match(a.node("finish-title").textContent,/Sukces/);
});

test("publish guard prevents second parallel request even if user taps twice",async()=>{
  const a=setup(),calls=[];
  let release;
  a.c.rpc=(method,p,body)=>{
    calls.push({method,p,body});
    if(method==="POST")return new Promise(resolve=>release=()=>resolve({id:71,status:"publish"}));
    return Promise.resolve({id:71,status:"publish"});
  };
  const first=a.run("savePost('publish')");
  const second=a.run("savePost('publish')");
  await second;
  assert.equal(calls.filter(c=>c.method==="POST").length,1);
  assert.match(a.node("publication-phase").textContent,/już trwa/);
  release();await first;
  assert.equal(a.node("save-publish").disabled,false);
});


test("last publication check verifies an existing ID without writing again",async()=>{
  const a=setup(),calls=[];
  a.run("postId=19;postStatus='publish';");
  a.c.rpc=async(method,p)=>{calls.push({method,p});return {id:19,status:"publish",link:"https://ispinalokalnie.wordpress.com/?p=19"};};
  await a.run("verifyLastPublication()");
  assert.equal(calls.length,1);
  assert.equal(calls[0].method,"GET");
  assert.match(a.node("finish-title").textContent,/STATUS ZWERYFIKOWANY/);
});

test("services need read-after-write confirmation and do not retry an uncertain write",async()=>{
  const a=setup(),calls=[];
  a.run("servicesLoaded=true;servicesRaw='<!-- existing -->';services=[];");
  a.node("service-name").value="Firma próbna";
  a.node("service-description").value="Opis usługi";
  a.node("service-category").value="Budowa domów";
  a.c.rpc=async(method,p,body)=>{
    calls.push({method,p,body});
    if(method==="POST")return {id:818};
    if(calls.length===1)return {id:818,content:{raw:"<!-- existing -->"}};
    throw new Error("Błąd kontrolnego odczytu strony");
  };
  await a.run("saveService()");
  assert.equal(calls.filter(c=>c.method==="POST").length,1);
  assert.match(a.node("finish-title").textContent,/NIEPOTWIERDZONY/);
  assert.equal(a.node("save-service").disabled,false);
});

test("OAuth recovery and adjustable haptics controls are present in editor UI",()=>{
  const a=setup();
  assert.ok(a.node("haptics-select"));
  assert.ok(a.node("login-recovery"));
  a.c.IL.finishLoginWithUrl=(v)=>{a.c.resumeValue=v};
  a.node("oauth-return").value="com.ispinalokalnie.redaktorsmart.publish190:/callback?code=TEST&state=TEST";
  a.run("finishManualLogin()");
  assert.match(a.c.resumeValue,/publish190:\/callback/);
  assert.equal(a.node("oauth-return").value,"");
  a.c.IL.setHaptics=(v)=>{a.c.hapticValue=v};
  a.run("setHapticsMode('0')");
  assert.equal(a.c.hapticValue,0);
  a.run("setHapticsMode('3')");
  assert.equal(a.c.hapticValue,3);
  a.c.IL.testHaptics=()=>a.run("window.onNativeResult('haptics-test',true,'Wysłano sygnał testowy.')");
  a.run("testHaptics()");
  assert.match(a.node("haptics-status").textContent,/Wysłano sygnał testowy/);
  assert.ok(a.node("facebook-teaser-dialog"));
});

test("every dashboard, navigation and calendar tile tap requests haptic feedback",()=>{
 const a=setup(),taps=[];a.c.IL.haptic=kind=>taps.push(kind);
 const listener=a.documentListeners.get("click");assert.ok(listener);assert.equal(listener.capture,true);
 const kinds="button.tile, nav button, button.calendar-day";
 for(let i=0;i<12;i++)assert.equal(listener.listener({target:{closest(selector){assert.equal(selector,kinds);return {disabled:false}}}}),true);
 assert.equal(a.run("hapticTileTap({target:{closest:()=>({disabled:true})}})"),false);
 assert.equal(taps.length,12);assert.ok(taps.every(kind=>kind==="tap"));
});

test("OAuth recovery offers re-opening the same browser flow and diagnoses missing callback",()=>{
  const a=setup();
  let resumed=0;
  a.c.IL.resumeLogin=()=>{resumed++};
  a.run("window.onNativeResult('auth-return',true,'Wrócono bez potwierdzenia WordPress')");
  assert.match(a.node("working-state").textContent,/Brak potwierdzenia/);
  assert.match(a.node("working-state").className,/pending/);
  assert.equal(a.node("login-recovery").classList.contains("hidden"),false);
  a.run("resumeLoginFlow()");
  assert.equal(resumed,1);
  a.run('window.onNativeResult("login-state",true,JSON.stringify({pending:true}))');
  assert.match(a.node("working-state").textContent,/Czekam na potwierdzenie/);
  a.run("stopAuthWaitTimer()");
});

test("OAuth waiting indicator is pending, not a forever-spinning progress state",()=>{
  const a=setup();
  a.run('window.onNativeResult("auth-progress",true,"Krok 3/4: oczekuję na powrót.")');
  assert.match(a.node("working-state").textContent,/Oczekuję/);
  assert.match(a.node("working-state").className,/pending/);
  assert.equal(a.node("login-recovery").classList.contains("hidden"),false);
  a.run("stopAuthWaitTimer()");
});

test("public post read remains available without a WordPress OAuth session",()=>{
  const a=setup(),calls=[];
  a.c.IL.publicPosts=()=>calls.push("publicPosts");
  a.run("connected=false;loadPosts()");
  assert.deepEqual(calls,["publicPosts"]);
  a.run('window.onNativeResult("public-posts",true,JSON.stringify([{id:1410,title:{rendered:"Ispina"},status:"publish",modified:"2026-11-01T08:00:00",link:"https://ispinalokalnie.wordpress.com/2026/11/01/ispina/"}]))');
  assert.match(a.node("posts-access").textContent,/Tryb publiczny/);
  assert.match(a.node("posts-output").innerHTML,/Czytaj artykuł/);
  assert.doesNotMatch(a.node("posts-output").innerHTML,/>Edytuj</);
  const urls=[];a.c.IL.openSite=url=>urls.push(url);
  a.run("openPublicPost(1410)");
  assert.deepEqual(urls,["https://ispinalokalnie.wordpress.com/2026/11/01/ispina/"]);
  a.run("allPosts[0].link='https://evil.invalid/';openPublicPost(1410)");
  assert.equal(urls.length,1);
});

test("failed verification reports the actual reason and does not erase public reading",()=>{
  const a=setup();
  a.run('window.onNativeResult("verify",false,"NO_SESSION: Brak sesji WordPress.com")');
  assert.match(a.node("home-connection").innerHTML,/NO_SESSION/);
  assert.match(a.node("posts-access").textContent,/NO_SESSION/);
});

test("OAuth waiting shows recovery, then clears it on cancellation",()=>{
  const a=setup();
  a.run('window.onNativeResult("auth-progress",true,"Krok 3/4: oczekuję na powrót.")');
  assert.match(a.node("settings-state").textContent,/Logowanie otwarte/);
  assert.equal(a.node("login-recovery").classList.contains("hidden"),false);
  a.run('window.onNativeResult("auth-return",true,"Powrót bez potwierdzenia")');
  assert.match(a.node("working-state").textContent,/Brak potwierdzenia/);
  a.run('window.onNativeResult("auth-cancel",true,"Anulowano")');
  assert.equal(a.node("login-recovery").classList.contains("hidden"),true);
});

test("diagnostic really creates a draft and verifies it via GET before success",async()=>{
  const a=setup(),calls=[];
  a.c.rpc=async(method,path,body)=>{
    calls.push({method,path,body});
    return method==="POST"?{id:8801,status:"draft"}:{id:8801,status:"draft"};
  };
  await a.run("testDraftWrite()");
  assert.equal(calls.length,2);
  assert.equal(calls[0].method,"POST");
  assert.equal(calls[0].body.status,"draft");
  assert.equal(calls[0].path,"posts");
  assert.equal(calls[1].method,"GET");
  assert.equal(calls[1].path,"posts/8801?context=edit");
  assert.match(a.node("finish-title").textContent,/TEST ZAPISU: SUKCES/);
  assert.equal(a.node("test-draft-write").disabled,false);
});
test("draft diagnostic never claims success if verification fails and never retries writes",async()=>{
  const a=setup(),calls=[];
  a.c.rpc=async(method,path)=>{
    calls.push({method,path});
    if(method==="POST")return {id:8802,status:"draft"};
    throw Error("Serwer odmówił odczytu");
  };
  await a.run("testDraftWrite()");
  assert.equal(calls.filter(x=>x.method==="POST").length,1);
  assert.match(a.node("finish-title").textContent,/NIEZWERYFIKOWANY/);
  assert.equal(a.node("test-draft-write").disabled,false);
});

function dateInFuture(minutes=30){const d=new Date(Date.now()+minutes*60_000);const pad=n=>String(n).padStart(2,"0");return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`}

test("scheduled publication sends future status and ISO date, then verifies by GET",async()=>{
  const a=setup(),calls=[];a.node("post-schedule").value=dateInFuture();
  a.c.rpc=async(method,path,body)=>{calls.push({method,path,body});return method==="POST"?{id:201,status:"future"}:{id:201,status:"future",date_gmt:calls[0].body.date}};
  await a.run("savePost('future')");
  assert.equal(calls[0].body.status,"future");
  assert.match(calls[0].body.date,/Z$/);
  assert.equal(calls[1].path,"posts/201?context=edit");
  assert.match(a.node("finish-title").textContent,/zaplanowana/);
  assert.equal(a.node("finish-preview").classList.contains("hidden"),true);
});

test("schedule without a date is rejected before any write",async()=>{
  const a=setup();let writes=0;a.c.rpc=async()=>{writes++;throw Error("unexpected")};
  await a.run("savePost('future')");
  assert.equal(writes,0);
  assert.match(a.node("post-output").innerHTML,/Wybierz termin/);
});

test("schedule less than two minutes away is rejected",async()=>{
  const a=setup();a.node("post-schedule").value=dateInFuture(1);let writes=0;a.c.rpc=async()=>{writes++};
  await a.run("savePost('future')");
  assert.equal(writes,0);
});

test("schedule cancellation leaves WordPress untouched",async()=>{
  const a=setup();a.node("post-schedule").value=dateInFuture();a.c.confirm=()=>false;let writes=0;a.c.rpc=async()=>{writes++};
  await a.run("savePost('future')");
  assert.equal(writes,0);
  assert.equal(a.node("save-schedule").disabled,false);
});

test("scheduled status mismatch is not reported as success",async()=>{
  const a=setup();a.node("post-schedule").value=dateInFuture();
  a.c.rpc=async(method)=>method==="POST"?{id:202,status:"future"}:{id:202,status:"draft"};
  await a.run("savePost('future')");
  assert.equal(a.node("finish-title").textContent,"STATUS NIEPOTWIERDZONY");
  assert.equal(a.node("finish-preview").classList.contains("hidden"),true);
});

test("schedule date mismatch is reported as an error even when WordPress says future",async()=>{
  const a=setup();a.node("post-schedule").value=dateInFuture();
  a.c.rpc=async(method)=>method==="POST"?{id:212,status:"future"}:{id:212,status:"future",date_gmt:new Date(Date.now()+3*60*60_000).toISOString()};
  await a.run("savePost('future')");
  assert.equal(a.node("finish-title").textContent,"TERMIN NIEPOTWIERDZONY");
});

test("editing a scheduled post preserves its existing future status after confirmation",async()=>{
  const a=setup(),calls=[];a.node("post-schedule").value=dateInFuture(60);const remote=wpFixture(203,'future');remote.date_gmt=new Date(a.node('post-schedule').value).toISOString();a.c.remote=remote;a.run("postId=203;postStatus='future';remoteBaseline=remoteVersion(remote)");
  a.c.rpc=async(method,path,body)=>{calls.push({method,path,body});return method==='POST'?{...remote,date_gmt:body.date}:remote};await a.run("savePost('draft')");
  const posted=calls.find(c=>c.method==='POST');assert.equal(posted.path,'posts/203');assert.equal(posted.body.status,'future');assert.equal(posted.body.date.endsWith('Z'),true);
});

test("editor schedules controls and mentions timezone and status confirmation",()=>{
  const a=setup();
  assert.ok(a.node("post-schedule"));assert.ok(a.node("save-schedule"));
  const html=fs.readFileSync(path.join(__dirname,"../app/src/main/assets/index.html"),"utf8");
  assert.match(html,/strefy czasowej telefonu/);
});

test("publication sends title, HTML content, excerpt and chosen category",async()=>{
  const a=setup(),calls=[];a.node("post-title").value="Tytuł";a.node("post-body").value="Akapit 1\n\n## Nagłówek";
  a.node("post-excerpt").value="Opis SEO";a.node("post-category").value="3503";
  a.c.rpc=async(method,path,body)=>{calls.push({method,path,body});return method==="POST"?{id:204,status:"draft"}:{id:204,status:"draft"}};
  await a.run("savePost('draft')");
  assert.equal(calls[0].body.title,"Tytuł");assert.equal(calls[0].body.excerpt,"Opis SEO");
  assert.deepEqual(Array.from(calls[0].body.categories),[3503]);
  assert.match(calls[0].body.content,/<h2>/);
});

test("featured image is sent as WordPress media ID",async()=>{
  const a=setup(),calls=[];a.node("post-media").value="345";
  a.c.rpc=async(method,path,body)=>{calls.push({method,path,body});return method==="POST"?{id:205,status:"draft"}:{id:205,status:"draft"}};
  await a.run("savePost('draft')");assert.equal(calls[0].body.featured_media,345);
});

test("HTML mode preserves editor markup for WordPress",async()=>{
  const a=setup(),calls=[];a.node("post-mode").value="html";a.node("post-body").value="<h2>W Ispinie</h2><p>Tekst</p>";
  a.c.rpc=async(method,path,body)=>{calls.push({method,path,body});return method==="POST"?{id:206,status:"draft"}:{id:206,status:"draft"}};
  await a.run("savePost('draft')");assert.equal(calls[0].body.content,"<h2>W Ispinie</h2><p>Tekst</p>");
});

test("professional article formatting converts headings, lists, quotes, emphasis and safe links",()=>{
 const a=setup();
 const html=a.run("asHtml('Wprowadzenie z **ważną informacją** i _szczegółem_.\\n\\n## Co się zmienia?\\n\\n- Pierwszy punkt\\n- Drugi punkt\\n\\n> Cytat ze wskazanym źródłem\\n\\n[Oficjalny komunikat](https://example.org/info)\\n\\n[Niebezpieczny](javascript:alert(1))')");
 assert.ok(html.includes("<strong>ważną informacją</strong>"));assert.ok(html.includes("<em>szczegółem</em>"));
 assert.ok(html.includes("<h2>Co się zmienia?</h2>"));assert.ok(html.includes("<ul><li>Pierwszy punkt</li><li>Drugi punkt</li></ul>"));
 assert.ok(html.includes("<blockquote><p>Cytat ze wskazanym źródłem</p></blockquote>"));assert.match(html,/<a href="https:\/\/example\.org\/info"/);
 assert.doesNotMatch(html,/<a href="javascript:/);
});

test("format toolbar preserves selected words and validates link destinations",()=>{
 const a=setup();a.node("post-body").value="Ważny komunikat";a.node("post-body").selectionStart=0;a.node("post-body").selectionEnd=5;
 assert.equal(a.run("formatEditor('bold')"),true);assert.equal(a.node("post-body").value,"**Ważny** komunikat");assert.ok(a.run("editorHtml()").includes("<strong>Ważny</strong>"));
 a.node("post-body").value="Przeczytaj komunikat";a.node("post-body").selectionStart=11;a.node("post-body").selectionEnd=20;a.node("editor-link-url").value="https://example.org/komunikat";
 assert.equal(a.run("formatEditor('link')"),true);assert.equal(a.node("post-body").value,"Przeczytaj [komunikat](https://example.org/komunikat)");
 a.node("post-body").value="Zwykły tekst";a.node("post-body").selectionStart=0;a.node("post-body").selectionEnd=5;a.node("editor-link-url").value="javascript:alert(1)";
 assert.equal(a.run("formatEditor('link')"),false);assert.equal(a.node("post-body").value,"Zwykły tekst");
});

test("headings and list toolbar actions keep article wording unchanged",()=>{
 const a=setup();a.node("post-body").value="Co się zmienia?";a.node("post-body").selectionStart=0;a.node("post-body").selectionEnd=16;a.run("formatEditor('h2')");assert.equal(a.node("post-body").value,"## Co się zmienia?");
 a.node("post-body").value="Pierwszy punkt\nDrugi punkt";a.node("post-body").selectionStart=0;a.node("post-body").selectionEnd=a.node("post-body").value.length;a.run("formatEditor('bullets')");
 assert.equal(a.node("post-body").value,"- Pierwszy punkt\n- Drugi punkt");assert.ok(a.run("editorHtml()").includes("<ul><li>Pierwszy punkt</li><li>Drugi punkt</li></ul>"));
});

test("successful tag creation and lookup are attached to post",async()=>{
  const a=setup(),calls=[];a.node("post-tags").value="Historia Ispiny";
  a.c.rpc=async(method,path,body)=>{calls.push({method,path,body});if(path.startsWith("tags?"))return [];
    if(path==="tags")return {id:900};return method==="POST"?{id:207,status:"draft"}:{id:207,status:"draft"}};
  await a.run("savePost('draft')");
  assert.deepEqual(Array.from(calls.find(x=>x.path==="posts").body.tags),[900]);
});

test("declining to continue after tag preparation failure prevents post write",async()=>{
  const a=setup();a.node("post-tags").value="Tag";a.c.confirm=()=>false;let postWrites=0;
  a.c.rpc=async(method,path)=>{if(path.startsWith("tags"))throw Error("403");if(path==="posts")postWrites++;return {id:208,status:"draft"}};
  await a.run("savePost('draft')");assert.equal(postWrites,0);
});

test("cancelling immediate-publication confirmation causes no network call",async()=>{
  const a=setup();a.c.confirm=()=>false;let requests=0;a.c.rpc=async()=>{requests++};
  await a.run("savePost('publish')");assert.equal(requests,0);
});

test("editing an existing article updates its ID exactly once",async()=>{
 const a=setup(),calls=[];a.c.remote=wpFixture(209);a.run("postId=209;postStatus='publish';remoteBaseline=remoteVersion(remote)");a.c.rpc=async(method,path,body)=>{calls.push({method,path,body});return a.c.remote};await a.run("savePost('draft')");assert.equal(calls.filter(c=>c.method==='POST').length,1);assert.equal(calls.find(c=>c.method==='POST').path,'posts/209');
});

test("network timeout after POST is surfaced without retry",async()=>{
  const a=setup(),calls=[];a.c.rpc=async(method,path)=>{calls.push({method,path});if(method==="POST")throw Error("timeout");return {}};
  await a.run("savePost('publish')");assert.equal(calls.filter(c=>c.method==="POST").length,1);
  assert.match(a.node("finish-detail").textContent,/timeout/);
});

test("external canonical links are not opened and use the site-owned permalink fallback",async()=>{
  const a=setup(),opened=[];a.c.IL.openSite=url=>opened.push(url);
  a.c.rpc=async(method)=>method==="POST"?{id:210,status:"publish"}:{id:210,status:"publish",link:"https://evil.example/article"};
  await a.run("savePost('publish')");a.run("openFinishUrl()");
  assert.deepEqual(opened,["https://ispinalokalnie.wordpress.com/?p=210"]);
});

test("legacy catalog parser recognizes real Gutenberg H3 cards and ignores own managed block",()=>{
  const a=setup();a.run("renderLegacyServices('<h3>RemoTerm &ndash; Marcin Mrukot</h3><h3>N-Tech</h3><!-- IL_REDAKTOR_SMART_START --> <h3>Managed duplicate</h3> <!-- IL_REDAKTOR_SMART_END -->')");
  assert.deepEqual(Array.from(a.run("legacyServices")),["RemoTerm – Marcin Mrukot","N-Tech"]);
});

test("service image URL allows trusted WordPress media HTTPS only",()=>{
  const a=setup();
  assert.equal(a.run("safeMediaUrl('https://ispinalokalnie.wordpress.com/photo.jpg')"),"https://ispinalokalnie.wordpress.com/photo.jpg");
  assert.equal(a.run("safeMediaUrl('http://example.com/photo.jpg')"),"");
  assert.equal(a.run("safeMediaUrl('https://wordpress.com.evil.example/photo.jpg')"),"");
});

test("hide operation preserves existing manual catalog HTML while updating managed block",async()=>{
  const a=setup();a.run(`servicesLoaded=true;servicesRaw='<h3>Legacy listing</h3>';services=[{id:'il_a',name:'A',description:'Opis'}]`);
  let written="";a.c.rpc=async(method,path,body)=>{
    if(method==="GET"&&path.startsWith("pages/")&&!written)return {id:818,content:{raw:a.run("servicesRaw")}};
    if(method==="POST"){written=body.content;return {id:818};}
    if(method==="GET")return {id:818,content:{raw:written}};
    throw Error("unexpected");
  };
  a.c.confirm=()=>true;await a.run("toggleServiceVisibility(0)");
  assert.match(a.run("servicesRaw"),/<h3>Legacy listing<\/h3>/);
  assert.equal(a.run("services[0].hidden"),true);
});

test("delete operation asks for confirmation before changing managed listing",async()=>{
  const a=setup();a.run(`servicesLoaded=true;servicesRaw='legacy';services=[{id:'il_a',name:'A',description:'Opis'}]`);
  let confirms=0,writes=0;a.c.confirm=()=>{confirms++;return false};a.c.rpc=async()=>{writes++};
  await a.run("deleteService(0)");assert.equal(confirms,1);assert.equal(writes,0);
});

test("theme and battery preferences persist without needing Android bridge",()=>{
  const a=setup();a.node("theme-select").value="dark";a.node("text-zoom-select").value="150";
  a.node("animations-enabled").checked=false;a.node("battery-saving").checked=true;
  a.run("applyUiPreferences(true)");
  const prefs=JSON.parse(a.storage.get("il_redaktor_ui_preferences_v1"));
  assert.deepEqual(prefs,{theme:"dark",zoom:150,animations:false,battery:true});
});

test("native text zoom callback accepts supported values and ignores arbitrary values",()=>{
  const a=setup();a.run(`window.onNativeResult('text-zoom',true,'{"value":150}')`);
  assert.equal(a.node("text-zoom-select").value,"150");
  a.run(`window.onNativeResult('text-zoom',true,'{"value":999}')`);
  assert.equal(a.node("text-zoom-select").value,"150");
});

test("settings expose theme, animation, battery and accessible text size controls",()=>{
  const html=fs.readFileSync(path.join(__dirname,"../app/src/main/assets/index.html"),"utf8");
  for(const id of ["theme-select","animations-enabled","battery-saving","text-zoom-select"])assert.match(html,new RegExp(`id="${id}"`));
});

test("OAuth callback mismatch and encrypted token storage rules remain explicit in Android code",()=>{
  const root=path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor");
  const oauth=fs.readFileSync(path.join(root,"OAuthBrowserLogin.java"),"utf8");
  const activity=fs.readFileSync(path.join(root,"MainActivity.java"),"utf8");
  assert.match(oauth,/if \(!expectedState\.equals\(state\)\)/);assert.match(oauth,/acceptedState = true/);
  assert.match(activity,/AES\/GCM\/NoPadding/);assert.match(activity,/AndroidKeyStore/);
});

test("MCP post mapping accepts future status and disallows unsupported status values",()=>{
  const java=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/WpMcp.java"),"utf8");
  assert.match(java,/draft\|publish\|private\|pending\|future/);
});

test("repeat scheduling taps are blocked while a write is in flight",async()=>{
  const a=setup(),calls=[];a.node("post-schedule").value=dateInFuture();let release;
  a.c.rpc=(method,path)=>{calls.push({method,path});if(method==="POST")return new Promise(resolve=>release=()=>resolve({id:211,status:"future"}));return Promise.resolve({id:211,status:"future"})};
  const first=a.run("savePost('future')"),second=a.run("savePost('future')");await second;
  assert.equal(calls.filter(c=>c.method==="POST").length,1);release();await first;
});

test("successful outcome actions are present and only shown for verified published articles",()=>{
  const a=setup();a.run(`showOutcome('success','headline','detail','https://ispinalokalnie.wordpress.com/p/','Zobacz artykuł',true)`);
  for(const id of ["finish-preview","finish-edit","finish-home"])assert.equal(a.node(id).classList.contains("hidden"),false);
  a.run(`showOutcome('success','headline','detail','https://ispinalokalnie.wordpress.com/p/','Otwórz',false)`);
  for(const id of ["finish-preview","finish-edit","finish-home"])assert.equal(a.node(id).classList.contains("hidden"),true);
});

test("native published-page viewer is restricted to the IL site and has no JavaScript bridge",()=>{
  const activity=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java"),"utf8");
  const viewer=activity.slice(activity.indexOf("private void showPublishedPageInApp"),activity.indexOf("@Override protected void onNewIntent"));
  assert.match(activity,/@JavascriptInterface public void showPublishedPage\(String target\)/);
  assert.match(viewer,/isPublishedSiteUrl\(target\)/);
  assert.match(viewer,/settings\.setAllowFileAccess\(false\)/);
  assert.match(viewer,/MIXED_CONTENT_NEVER_ALLOW/);
  assert.doesNotMatch(viewer,/addJavascriptInterface/);
});

test("AI context exposes published posts only for reader recommendations",()=>{
  const a=setup();
  a.run(`allPosts=[{id:1,status:'publish',title:{rendered:'Wpis publiczny'}},{id:2,status:'draft',title:{rendered:'Szkic prywatny'}}]`);
  const context=JSON.parse(a.run("JSON.stringify(assistantContext())"));
  assert.deepEqual(context.posts.map(post=>post.id),[1]);
  assert.equal(context.posts[0].status,"publish");
});

test("new build metadata and the PR APK workflow agree",()=>{
  const gradle=fs.readFileSync(path.join(__dirname,"../app/build.gradle"),"utf8");
  const workflow=fs.readFileSync(path.join(__dirname,"../../.github/workflows/il-redaktor-apk.yml"),"utf8");
  const versionCode=gradle.match(/versionCode (\d+)/)[1],versionName=gradle.match(/versionName '([^']+)'/)[1];
  assert.ok(Number(versionCode)>=45);assert.match(versionName,/^2\.0\.0-professional-test\./);assert.match(gradle,/https:\/\/il-redaktor-assistant\.vercel\.app\/api\/assistant/);
  assert.match(gradle,/applicationId 'com\.ispinalokalnie\.redaktorsmart\.stable'/);
  assert.ok(workflow.includes("versionCode='"+versionCode+"' versionName='"+versionName+"'"));
  const instrumentation=fs.readFileSync(path.join(__dirname,"../app/src/androidTest/java/com/ispinalokalnie/redaktor/ConfirmationInstrumentation.java"),"utf8");
  assert.match(instrumentation,/seed-current.*BuildConfig.VERSION_CODE/);
});

test("AI editor shortcut copies current title and text into a reviewable prompt without sending it",()=>{
  const a=setup();a.node("post-title").value="Wiadomości z Ispiny";a.node("post-body").value="Treść do poprawienia";
  a.run("assistantFromEditor('Skróć tekst')");
  assert.match(a.node("assistant-prompt").value,/Skróć tekst/);
  assert.match(a.node("assistant-prompt").value,/Wiadomości z Ispiny/);
  assert.match(a.node("assistant-prompt").value,/Treść do poprawienia/);
});

test("ChatGPT/Gemini handoff adds fact guardrails and sends only after the user taps",()=>{
  const a=setup();let sent="";a.node("assistant-prompt").value="Skróć tekst artykułu";a.c.IL.shareAssistantPrompt=text=>{sent=text};
  assert.equal(a.run("sendAssistantToApps()"),true);
  assert.match(sent,/w Ispinie, do Ispiny, z Ispiny/);assert.match(sent,/Nie wymyślaj dat/);assert.match(sent,/Skróć tekst artykułu/);
  assert.match(a.node("assistant-handoff-status").textContent,/otwieram ChatGPT Free/);
});

test("configured AI backend is not used by the default free local button",async()=>{
  const a=setup();let requests=0;a.run("assistantEnabled=true;connected=true");a.node("assistant-prompt").value="Skróć tekst";a.c.requestAssistant=async()=>{requests++;throw Error("must not run")};
  await a.run("runAssistant()");assert.equal(requests,0);assert.match(a.node("assistant-output").innerHTML,/Propozycja/);
});

test("online AI backend requires separate user confirmation before sending",async()=>{
  const a=setup();let requests=0,confirmation="";a.run("assistantEnabled=true;connected=true;servicesLoaded=true");a.node("assistant-prompt").value="Zredaguj tekst";a.c.refreshPublishedPostsForReading=async()=>{};a.c.requestAssistant=async()=>{requests++;return {intent:"question",message:"ok",internalLinks:[],readingPromotions:[],factsToVerify:[]}};a.c.confirm=text=>{confirmation=text;return false};
  assert.equal(await a.run("runAssistantWithConfiguredAI()"),false);assert.equal(requests,0);assert.match(confirmation,/Mogą obowiązywać płatne limity/);
});

test("external assistant answer must be imported as a comparison before changing article text",()=>{
  const a=setup();a.node("assistant-return-text").value="Poprawiona treść z odpowiedzi AI";a.node("assistant-return-target").value="body";
  a.run("compareExternalAssistantResponse()");
  assert.equal(a.node("post-body").value,"Treść testowa");
  assert.equal(a.run("pendingCorrection.changes.body"),"Poprawiona treść z odpowiedzi AI");
  assert.match(a.node("correction-reason").textContent,/Odpowiedź z ChatGPT\/Gemini/);
});

test("external AI answer from an older editor snapshot is rejected",()=>{
  const a=setup();a.run("assistantFromEditor('Popraw artykuł')");a.node("assistant-return-text").value="Stara propozycja";a.node("assistant-return-target").value="body";a.node("post-body").value="Nowsza wersja";
  assert.equal(a.run("compareExternalAssistantResponse()"),false);
  assert.equal(a.run("pendingCorrection"),null);assert.match(a.node("assistant-handoff-status").textContent,/dotyczy wcześniejszej wersji/);
});

test("smart local template marks unknown information and reports it before publication",()=>{
  const a=setup();a.run("insertTemplate(0)");a.node("template-field-0").value="Zmiana godzin odjazdu";a.node("template-field-1").value="2";a.run("applyTemplate(0)");
  assert.match(a.node("post-title").value,/Zmiany w rozkładach MLD: Zmiana godzin odjazdu/);
  assert.match(a.node("post-body").value,/Zmiana godzin odjazdu/);assert.match(a.node("post-body").value,/\[DO UZUPEŁNIENIA: Od kiedy obowiązuje zmiana\?\]/);
  assert.match(a.node("editor-audit").innerHTML,/Uzupełnij: Od kiedy obowiązuje zmiana/);
  assert.match(a.node("editor-metrics").textContent,/pól do uzupełnienia/);
});

test("publication bundle contains only the current draft and its local preflight checks",()=>{
  const a=setup();a.node("post-title").value="Zmiany w rozkładzie";a.node("post-excerpt").value="Sprawdź nowe godziny.";a.node("post-body").value="Linia 2 odjeżdża później.";
  a.run("showPublicationBundle()");const html=a.node("publication-bundle").innerHTML;
  assert.match(html,/Zmiany w rozkładzie/);assert.match(html,/Sprawdź nowe godziny/);assert.match(html,/Linia 2 odjeżdża później/);assert.match(html,/Ostatnia kontrola przed publikacją/);
});

test("native AI handoff prefers ChatGPT, then Gemini, and reads clipboard only on request",()=>{
  const java=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java"),"utf8");
  assert.ok(java.indexOf('"com.openai.chatgpt"')<java.indexOf('"com.google.android.apps.bard"'));
  assert.ok(java.indexOf('newPlainText("Polecenie IL",text)')<java.indexOf('"com.openai.chatgpt"'),'the prompt must be copied before trying direct app handoff');
  assert.match(java,/void shareAssistantPrompt/);assert.match(java,/void readAssistantClipboard/);assert.match(java,/Intent\.ACTION_SEND/);
  const manifest=fs.readFileSync(path.join(__dirname,"../app/src/main/AndroidManifest.xml"),"utf8");
  assert.match(manifest,/com\.openai\.chatgpt/);assert.match(manifest,/com\.google\.android\.apps\.bard/);
});

test("free Facebook draft uses only current title and excerpt without adding facts",()=>{
  const a=setup();a.node("post-title").value="Wpis z Ispiny";a.node("post-excerpt").value="Potwierdzona zajawka";
  const result=JSON.parse(a.run("JSON.stringify(localAssistant('Przygotuj post na Facebooka'))"));
  assert.equal(result.facebookPost,"Wpis z Ispiny\n\nPotwierdzona zajawka");
  assert.equal(result.imageSuggestion,null);
});

test("assistant renderer escapes generated content and exposes Facebook copy and photo library search",()=>{
  const a=setup();
  a.run(`renderAssistantResult({intent:'article_draft',message:'Propozycja',title:'W Ispinie',content:'<script>alert(1)</script>',excerpt:'Opis',category:'Historia',tags:[],internalLinks:[],factsToVerify:[],service:null,facebookPost:'Post testowy',imageSuggestion:{description:'Archiwalne zdjęcie',altText:'Zdjęcie z archiwum IL',searchTerms:['archiwum Ispiny']}})`);
  const html=a.node("assistant-output").innerHTML;
  assert.match(html,/&lt;script&gt;/);assert.doesNotMatch(html,/<script>/);
  assert.match(html,/Kopiuj tekst na Facebooka/);assert.match(html,/Szukaj w galerii IL/);
});

test("AI reading promotions are matched to exact published post IDs before display",()=>{
  const a=setup();
  a.run(`allPosts=[{id:712,status:'publish',title:{rendered:'Potwierdzony tytuł IL'},link:'https://ispinalokalnie.wordpress.com/realny-wpis/'}];postId=0;renderAssistantResult({intent:'article_draft',message:'Propozycja',title:'W Ispinie',content:'Treść',excerpt:'',category:'',tags:[],internalLinks:[],readingPromotions:[{postId:712,promo:'Przeczytaj również ten tekst.'},{postId:999,promo:'Nieistniejący artykuł.'}],factsToVerify:[],service:null,facebookPost:'',imageSuggestion:null})`);
  const html=a.node("assistant-output").innerHTML;
  assert.match(html,/Podpowiedzi dla czytelnika IL/);
  assert.match(html,/Potwierdzony tytuł IL/);
  assert.match(html,/Przeczytaj również ten tekst/);
  assert.doesNotMatch(html,/Nieistniejący artykuł/);
});

test("local language correction is reviewed and keeps context-sensitive Ispina forms",()=>{
  const a=setup();const before="w Ispini, do Ispinii, z Ispina, Ispinii i Ispini";a.node("post-body").value=before;
  a.run("suggest()");assert.equal(a.node("post-body").value,before);
  a.run("$('accept-body').checked=true;acceptCorrection()");
  assert.equal(a.node("post-body").value,"w Ispinie, do Ispiny, z Ispiny, Ispinii i Ispini");
});

test("AI contract only returns image search terms, not unverifiable image URLs",()=>{
  const backend=fs.readFileSync(path.join(__dirname,"../backend/api/assistant.ts"),"utf8");
  assert.match(backend,/facebookPost: z\.string\(\)\.max\(1800\)/);
  assert.match(backend,/imageSuggestion: z\.object/);
  assert.match(backend,/Nie zwracaj adresów URL zdjęć/);
  assert.match(backend,/readingPromotions: z\.array/);
  assert.match(backend,/wyłącznie do przekazanych wpisów ze statusem publish/);
});

test("WordPress MCP OAuth uses documented auth scope and reports server permission mismatches",()=>{
  const main=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java"),"utf8");
  const oauth=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/OAuthBrowserLogin.java"),"utf8");
  assert.equal((oauth.match(/&scope=" \+ Uri\.encode\(SCOPE\)/g)||[]).length,2,"Start and resume must use one documented scope");
  assert.match(oauth,/public static final String SCOPE = "auth"/);
  assert.match(main,/AUTH_SCOPE: Serwer MCP wymaga zakresu global/);
  assert.match(main,/required scope/);
});
test("OAuth DCR, PKCE, deep-link scheme and manifest callback match without a client secret",()=>{
  const root=path.join(__dirname,"../app/src/main");
  const oauth=fs.readFileSync(path.join(root,"java/com/ispinalokalnie/redaktor/OAuthBrowserLogin.java"),"utf8");
  const wp=fs.readFileSync(path.join(root,"java/com/ispinalokalnie/redaktor/WpMcp.java"),"utf8");
  const manifest=fs.readFileSync(path.join(root,"AndroidManifest.xml"),"utf8");
  assert.match(oauth,/String SCOPE = "auth"/);assert.doesNotMatch(oauth,/&scope=global/);assert.match(oauth,/code_challenge_method=S256/);
  assert.match(oauth,/stable:\/callback/);assert.match(manifest,/com\.ispinalokalnie\.redaktorsmart\.stable/);
  assert.match(wp,/token_endpoint_auth_method", "none"/);assert.match(wp,/code_verifier/);
  assert.doesNotMatch(wp,/put\("client_secret"/);
});

test("photo upload source downsamples and compresses images before sending",()=>{
  const java=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java"),"utf8");
  const photo=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/PhotoEditor.java"),"utf8");
  assert.match(photo,/inSampleSize=1/);assert.match(photo,/>1600/);
  assert.match(java,/Bitmap\.CompressFormat\.JPEG, 80/);assert.match(java,/bytes\.length > 6_000_000/);
});

test('multiple local drafts survive creating and opening another article',()=>{
 const a=setup();assert.equal(a.run('persistDraft(true)'),true);const first=a.run('activeDraftId');
 a.run("resetEditor();$('post-title').value='Drugi';$('post-body').value='Drugi tekst';persistDraft(true)");
 assert.equal(a.run('readDraftStore().drafts.length'),2);a.c.targetId=first;a.run('openLocalDraft(targetId)');
 assert.equal(a.node('post-title').value,'Testowy artykuł');assert.equal(a.run('readDraftStore().drafts.length'),2);
});
test('restoring revision preserves current text in history and never writes remotely',()=>{
 const a=setup();let writes=0;a.c.rpc=()=>{writes++;throw Error('Unexpected request')};
 a.run("persistDraft(true);$('post-body').value='Wersja druga';persistDraft(true)");
 assert.equal(a.run('restoreDraftVersion(activeDraftId,0)'),true);assert.equal(a.node('post-body').value,'Treść testowa');
 assert.equal(a.run('readDraftStore().drafts[0].revisions.some(v=>v.snapshot.body===\'Wersja druga\')'),true);assert.equal(writes,0);
});
test('storage failure prevents resetting or switching away from the current unsaved text',()=>{
 const a=setup();a.run('persistDraft()');a.c.localStorage.setItem=()=>{throw Error('quota')};a.node('post-body').value='Nie zgub mnie';
 assert.equal(a.run('resetEditor()'),false);assert.equal(a.node('post-body').value,'Nie zgub mnie');assert.match(a.node('local-save-status').textContent,/Nie udało/);
});
test('legacy local draft migrates durably and retains its WordPress identity',()=>{
 const legacy=JSON.stringify({title:'Stary szkic',body:'Treść',postId:123,postStatus:'draft'});
 const a=setup([['il_redaktor_draft_v2',legacy]]);a.run('readDraftStore()');
 const store=JSON.parse(a.storage.get('il_redaktor_drafts_v3'));assert.equal(store.drafts[0].postId,123);assert.equal(a.storage.get('il_redaktor_draft_v2'),legacy);
});
test('malformed draft storage is not overwritten by autosave',()=>{
 const a=setup([['il_redaktor_drafts_v3','broken json']]);assert.equal(a.run('persistDraft()'),false);assert.equal(a.storage.get('il_redaktor_drafts_v3'),'broken json');
});
test('prepublication review performs no writes and optional photo does not block',()=>{
 const a=setup();let writes=0;a.c.rpc=()=>{writes++};a.run('reviewPublication()');assert.equal(writes,0);assert.equal(a.node('review-confirm').disabled,false);
 assert.match(a.node('review-summary').innerHTML,/Brak zdjęcia/);assert.match(a.node('review-preview').srcdoc,/Content-Security-Policy/);a.run('closeReview()');assert.equal(writes,0);
});
test('prepublication review prevents missing title and requires fresh consent after changes',async()=>{
 const a=setup();a.node('post-title').value='';a.run('reviewPublication()');assert.equal(a.node('review-confirm').disabled,true);
 a.node('post-title').value='Tytuł';a.run('reviewPublication()');a.node('post-body').value='Zmieniony tekst';let writes=0;a.c.rpc=()=>{writes++};await a.run('confirmReviewPublication()');assert.equal(writes,0);assert.match(a.node('review-feedback').textContent,/zmieniła/);
});
test('review confirmation publishes exactly once without a second confirm dialog',async()=>{
 const a=setup();let writes=0;a.c.confirm=()=>{throw Error('Redundant confirmation')};a.c.rpc=async(method)=>{if(method==='POST')writes++;return {id:24,status:'publish'}};
 a.run('reviewPublication()');await a.run('confirmReviewPublication()');assert.equal(writes,1);assert.match(a.node('finish-title').textContent,/Sukces/);
});
test('local draft retains remote ID after verified publication so later saves update it',async()=>{
 const a=setup();a.c.rpc=async()=>({id:25,status:'publish'});await a.run("savePost('publish')");assert.equal(a.run('readDraftStore().drafts[0].postId'),25);assert.equal(a.run('readDraftStore().drafts[0].dirty'),false);
});


test('restoring a prepublication revision preserves the current WordPress post ID',async()=>{
 const a=setup();a.run('persistDraft(true)');a.c.rpc=async()=>({id:81,status:'publish'});await a.run("savePost('publish')");
 assert.equal(a.run('restoreDraftVersion(activeDraftId,0)'),true);assert.equal(a.run('postId'),81);assert.equal(a.run('postStatus'),'publish');
});
test('startup restores the last local draft without requiring WordPress login',()=>{
 const draft={id:'offline',title:'Tekst offline',body:'Treść offline',postId:null,postStatus:'',revisions:[]};
 const a=setup([['il_redaktor_drafts_v3',JSON.stringify({version:1,activeId:'offline',drafts:[draft]})]]);
 assert.equal(a.run('activeDraftId'),'offline');assert.equal(a.run('readDraftStore().drafts[0].title'),'Tekst offline');
});


test('restoring oldest revision from a full history selects the requested version',()=>{
 const a=setup();a.run("persistDraft(true);for(let i=0;i<13;i++){$('post-body').value='Version '+i;persistDraft(true)}");
 const selected=a.run('readDraftStore().drafts[0].revisions[0].snapshot.body');a.node('post-body').value='Unsaved current text';
 a.run('restoreDraftVersion(activeDraftId,0)');assert.equal(a.node('post-body').value,selected);
});
test('uploaded featured photo appears in review and survives local reopening',()=>{
 const a=setup();a.run('window.onNativeResult("upload",true,JSON.stringify({id:99,source_url:"https://ispinalokalnie.wordpress.com/wp-content/uploads/photo.jpg"}));persistDraft()');
 assert.equal(a.run('readDraftStore().drafts[0].mediaUrl'),'https://ispinalokalnie.wordpress.com/wp-content/uploads/photo.jpg');
 a.run('reviewPublication()');assert.match(a.node('review-summary').innerHTML,/<img[^>]+photo.jpg/);
 a.run('closeReview();mediaById.clear();applyDraft(readDraftStore().drafts[0]);reviewPublication()');assert.match(a.node('review-summary').innerHTML,/<img[^>]+photo.jpg/);
});

test('backup round trip preserves all drafts and revisions without auth or settings',()=>{
 const a=setup();a.storage.set('token','NEVER-EXPORT-THIS');a.storage.set('il_ui_preferences','PRIVATE-PREFERENCES');
 a.run("persistDraft();$('post-body').value='Wersja druga';persistDraft(true);resetEditor();$('post-title').value='Drugi';$('post-body').value='Tekst drugi';persistDraft()");
 const raw=a.run('makeDraftBackup()'),data=JSON.parse(raw);assert.equal(data.drafts.length,2);assert.equal(data.drafts[0].revisions[0].snapshot.body,'Treść testowa');assert.doesNotMatch(raw,/NEVER-EXPORT|PRIVATE-PREFERENCES|token/);
 a.c.backup=raw;assert.equal(a.run('parseDraftBackup(backup).length'),2);
});
test('backup preview and cancel never import automatically',()=>{
 const a=setup();a.c.backup=a.run('makeDraftBackup()');a.run('previewDraftBackup(backup)');assert.equal(a.run('readDraftStore().drafts.length'),1);a.run('cancelBackupImport()');assert.equal(a.run('pendingBackupImport'),null);
});
test('import merges ID collisions without overwrite and skips identical copies',()=>{
 const a=setup();a.c.data=JSON.parse(a.run('makeDraftBackup()'));a.c.data.drafts[0].title='Z drugiego telefonu';a.c.backup=JSON.stringify(a.c.data);
 a.run('previewDraftBackup(backup);mergeDraftBackup()');assert.equal(a.run('readDraftStore().drafts.length'),2);assert.equal(a.run('new Set(readDraftStore().drafts.map(d=>d.id)).size'),2);assert.equal(a.node('post-title').value,'Testowy artykuł');
 a.run('previewDraftBackup(backup);mergeDraftBackup()');assert.equal(a.run('readDraftStore().drafts.length'),2);assert.match(a.node('backup-output').textContent,/Pominięto identyczne kopie: 1/);
});
test('malformed or wrong-site backup leaves existing library unchanged',()=>{
 const a=setup();a.run('persistDraft()');const before=a.storage.get('il_redaktor_drafts_v3');
 for(const raw of ['{}','broken',JSON.stringify({format:'IL_REDAKTOR_BACKUP',version:1,site:'other.wordpress.com',drafts:[]})]){a.c.raw=raw;a.run('previewDraftBackup(raw)');assert.equal(a.run('pendingBackupImport'),null);assert.equal(a.storage.get('il_redaktor_drafts_v3'),before)}
});
test('import quota failure preserves existing library and pending backup for retry',()=>{
 const a=setup();a.c.data=JSON.parse(a.run('makeDraftBackup()'));a.c.data.drafts[0].title='Import';a.c.backup=JSON.stringify(a.c.data);a.run('previewDraftBackup(backup)');a.c.localStorage.setItem=()=>{throw Error('quota')};
 assert.equal(a.run('mergeDraftBackup()'),false);assert.equal(a.run('readDraftStore().drafts.length'),1);assert.equal(a.run('pendingBackupImport.length'),1);
});
test('backup strips unexpected properties and rejects oversized revision arrays',()=>{
 const a=setup();a.c.data=JSON.parse(a.run('makeDraftBackup()'));a.c.data.drafts[0].token='PRIVATE';a.c.data.drafts[0].__proto__={injected:'x'};a.c.backup=JSON.stringify(a.c.data);
 assert.equal(a.run('parseDraftBackup(backup)[0].token'),undefined);a.c.data.drafts[0].revisions=Array(13).fill({});a.c.backup=JSON.stringify(a.c.data);assert.throws(()=>a.run('parseDraftBackup(backup)'),/historia/);
});
test('correction selection applies only checked fields and preserves published identity',()=>{
 const a=setup();a.run("postId=50;postStatus='publish';offerCorrection({title:'Nowy tytuł',body:'Nowa treść'},'Test');$('accept-title').checked=false;$('accept-body').checked=true;acceptCorrection()");
 assert.equal(a.node('post-title').value,'Testowy artykuł');assert.equal(a.node('post-body').value,'Nowa treść');assert.equal(a.run('postId'),50);assert.equal(a.run('postStatus'),'publish');
});
test('discarded correction leaves text unchanged and sends no remote writes',()=>{
 const a=setup();let calls=0;a.c.rpc=()=>{calls++};a.run("offerCorrection({body:'Inna'},'Test');dismissCorrection()");assert.equal(a.node('post-body').value,'Treść testowa');assert.equal(calls,0);
});
test('stale correction cannot overwrite text changed after proposal',()=>{
 const a=setup();a.run("offerCorrection({body:'Inna'},'Test');$('accept-body').checked=true");a.node('post-body').value='Nowszy tekst';assert.equal(a.run('acceptCorrection()'),false);assert.equal(a.node('post-body').value,'Nowszy tekst');
});
test('accepted correction can be undone and both versions remain in history',()=>{
 const a=setup();a.run("offerCorrection({body:'Inna treść'},'Test');$('accept-body').checked=true;acceptCorrection()");assert.equal(a.run('undoEditorialChange()'),true);assert.equal(a.node('post-body').value,'Treść testowa');assert.equal(a.run("readDraftStore().drafts[0].revisions.some(r=>r.snapshot.body==='Inna treść')"),true);
});
test('undo never overwrites later manual changes',()=>{
 const a=setup();a.run("offerCorrection({body:'Inna'},'Test');$('accept-body').checked=true;acceptCorrection()");a.node('post-body').value='Nowsza zmiana';assert.equal(a.run('undoEditorialChange()'),false);assert.equal(a.node('post-body').value,'Nowsza zmiana');
});
test('diff escapes HTML and marks additions and removals',()=>{
 const a=setup();const out=a.run("diffMarkup('stare <img onerror=x>','nowe <script>')");assert.match(out,/diff-added/);assert.match(out,/diff-removed/);assert.doesNotMatch(out,/<script>|<img/);assert.match(out,/&lt;script&gt;/);
});
test('local name correction skips HTML attributes and preserves correct grammatical forms',()=>{
 const a=setup();a.c.value='<a href="https://example.com/w Ispini">W Ispinii</a><code>w Ispini</code><p>do Ispina, z Ispinii, w Ispinie</p>';
 const out=a.run("correctLocalNames(value,'html')");assert.match(out,/href="https:\/\/example.com\/w Ispini"/);assert.match(out,/>W Ispinie<\/a>/);assert.match(out,/<code>w Ispini<\/code>/);assert.match(out,/do Ispiny, z Ispiny, w Ispinie/);
});
test('fact audit identifies dates and ambiguous names without claiming external verification',()=>{
 const a=setup();a.node('post-body').value='Historia z 1903 roku w Ispinii. Niepoł.';a.run('auditEditorial()');assert.match(a.node('editor-audit').innerHTML,/1903/);assert.match(a.node('editor-audit').innerHTML,/Niepołomice albo Podłęże/);assert.match(a.node('editor-audit').innerHTML,/nie weryfikuje faktów w internecie/);assert.equal(a.node('post-body').value,'Historia z 1903 roku w Ispinii. Niepoł.');
});
test('local editor help prepares a shorter opening for review without changing text',async()=>{
 const a=setup();a.node('post-body').value='Pierwsze zdanie. Drugie zdanie. Trzecie zdanie.\n\nDrugi akapit.';await a.run("editorHelp('Skróć początek')");assert.match(a.node('post-body').value,/Trzecie/);assert.equal(a.run('pendingCorrection.changes.body'),'Pierwsze zdanie. Drugie zdanie.\n\nDrugi akapit.');
});
test('freeform command clearly requires configured AI and does not send text when unavailable',async()=>{
 const a=setup();let requests=0;a.c.requestAssistant=()=>{requests++};await a.run("editorHelp('Przeanalizuj argumenty i napisz reportaż')");assert.equal(requests,0);assert.match(a.node('editor-chat-log').innerHTML,/wymaga skonfigurowanego AI/);
});
test('stale AI response cannot be applied to another draft',async()=>{
 const a=setup();a.run('assistantEnabled=true');a.c.requestAssistant=async()=>{a.node('post-body').value='Nowy tekst';return {content:'Propozycja',title:'AI'}};await a.run("editorHelp('Przebuduj argumentację')");assert.equal(a.node('post-body').value,'Nowy tekst');assert.equal(a.run('pendingCorrection'),null);assert.match(a.node('editor-chat-log').innerHTML,/zmienił się/);
});
test('assistant article acceptance uses comparison and retains current remote ID',()=>{
 const a=setup();a.run("postId=99;postStatus='publish';assistantDraft={intent:'article_draft',title:'Propozycja',content:'<p>Nowa treść</p>'};acceptAssistantArticle()");assert.equal(a.run('postId'),99);assert.equal(a.node('post-body').value,'Treść testowa');assert.equal(a.run('pendingCorrection.changes.body'),'<p>Nowa treść</p>');
});
test('promotion exists only after verified published status, with the canonical link',async()=>{
 const a=setup();assert.equal(a.run('openPromotion()'),false);a.c.rpc=async()=>({id:61,status:'draft'});await a.run("savePost('publish')");assert.equal(a.run('promotionArticle'),null);
 a.run('resetEditor()');a.node('post-title').value='Testowy artykuł';a.node('post-body').value='Treść';a.c.rpc=async()=>({id:61,status:'publish',link:'https://ispinalokalnie.wordpress.com/realny-artykul/'});await a.run("savePost('publish')");assert.equal(a.run('openPromotion()'),true);assert.match(a.node('promotion-text').value,/https:\/\/ispinalokalnie.wordpress.com\/realny-artykul\//);assert.match(a.node('promotion-text').value,/Łączy nas Ispina/);
});
test('draft save does not create a public promotion',async()=>{
 const a=setup();a.c.rpc=async()=>({id:62,status:'draft'});await a.run("savePost('draft')");assert.equal(a.run('promotionArticle'),null);assert.equal(a.storage.has('il_last_verified_promotion_v1'),false);
});
test('promotion snapshot remains tied to published article while editing a new draft',()=>{
 const a=setup();a.run("rememberPublishedArticle({id:63,title:'Opublikowany',body:'Pierwszy'},'https://ispinalokalnie.wordpress.com/?p=63');resetEditor();$('post-title').value='Nowy szkic';openPromotion()");assert.match(a.node('promotion-text').value,/Opublikowany/);assert.doesNotMatch(a.node('promotion-text').value,/Nowy szkic/);
});
test('promotion rejects external links and copying shares no data automatically',()=>{
 const a=setup();a.run("rememberPublishedArticle({id:64,title:'Bad'},'https://example.com/x')");assert.equal(a.run('promotionArticle'),null);let copied='';a.c.IL.copyText=t=>{copied=t};a.run("rememberPublishedArticle({id:64,title:'W Ispinie',body:'Tekst'},'https://ispinalokalnie.wordpress.com/?p=64');openPromotion();copyPromotion()");assert.match(copied,/W Ispinie/);
});

test("Facebook teaser is generated from the current draft and can be edited locally",()=>{
 const a=setup();a.node("post-title").value="Zmiany w rozkładach MLD";a.node("post-excerpt").value="Nowe godziny kursów zaczną obowiązywać w listopadzie.";
 assert.equal(a.run("openFacebookTeaser()"),true);
 assert.equal(a.node("facebook-teaser-text").value,"Zmiany w rozkładach MLD.\n\nNowe godziny kursów zaczną obowiązywać w listopadzie.\n\nZobacz, co się zmieni.");
 assert.equal(a.storage.has("il_last_verified_promotion_v1"),false);
 a.node("facebook-teaser-text").value="Moja wersja posta";let shared="";a.c.IL.shareText=t=>{shared=t};a.run("shareFacebookTeaser()");assert.equal(shared,"Moja wersja posta");
});

test("Facebook teaser gives useful missing-content feedback and generic titles get a neutral CTA",()=>{
 const a=setup();a.run("$('post-title').value='';$('post-excerpt').value='';$('post-body').value=''");assert.equal(a.run("openFacebookTeaser()"),false);assert.match(a.node("post-output").innerHTML,/Dodaj tytuł/);
 a.node("post-title").value="Nowa galeria zdjęć";a.node("post-body").value="Opis galerii.";assert.equal(a.run("openFacebookTeaser()"),true);assert.match(a.node("facebook-teaser-text").value,/Przeczytaj więcej na Ispina Lokalnie/);
});

test('storage failure while accepting correction is visible inside comparison and changes nothing',()=>{
 const a=setup();a.run("offerCorrection({body:'Inna'},'Test');$('accept-body').checked=true");a.c.localStorage.setItem=()=>{throw Error('quota')};assert.equal(a.run('acceptCorrection()'),false);assert.equal(a.node('post-body').value,'Treść testowa');assert.match(a.node('correction-feedback').textContent,/Zmiany nie zostały nałożone/);
});
test('import storage error is visible inside the confirmation dialog',()=>{
 const a=setup();a.c.backup=a.run('makeDraftBackup()');a.run('previewDraftBackup(backup)');a.c.localStorage.setItem=()=>{throw Error('quota')};a.run('mergeDraftBackup()');assert.match(a.node('backup-feedback').textContent,/Import wstrzymany/);
});
test('published-status lookup for a different article cannot offer the previous article promotion as its own',()=>{
 const a=setup();a.run("rememberPublishedArticle({id:5,title:'Poprzedni'},'https://ispinalokalnie.wordpress.com/?p=5');postId=6;showOutcome('success','Status','Sprawdzono','https://ispinalokalnie.wordpress.com/?p=6','Zobacz',true)");assert.equal(a.node('finish-promotion').classList.contains('hidden'),true);
});

test('AI SEO correction also opens comparison without silently changing the article',()=>{
 const a=setup();a.run("renderAssistantResult({intent:'seo_review',title:'',content:'<p>Korekta</p>',internalLinks:[],factsToVerify:[]})");assert.match(a.node('assistant-output').innerHTML,/Porównaj z artykułem/);a.run('acceptAssistantArticle()');assert.equal(a.run('pendingCorrection.changes.body'),'<p>Korekta</p>');assert.equal(a.node('post-body').value,'Treść testowa');
});

test('native durable draft write succeeds even when the WebView cache has no space',()=>{
 const a=setup([],null);a.c.localStorage.setItem=()=>{throw Error('WebView quota')};assert.equal(a.run('persistDraft()'),true);const data=JSON.parse(JSON.parse(a.c.IL.readDraftLibrary()).data);assert.equal(data.drafts[0].title,'Testowy artykuł');
});
test('leaving the editor synchronously flushes the latest draft to native storage',()=>{
 const a=setup([],null);a.node('post-title').value='Artykuł przed wyjściem';a.node('post-body').value='Ostatnie zdanie zapisane chwilę przed zmianą ekranu.';a.run('scheduleLocalSave();showScreen("home")');
 const result=JSON.parse(a.c.IL.readDraftLibrary()),saved=JSON.parse(result.data).drafts[0];assert.equal(saved.title,'Artykuł przed wyjściem');assert.equal(saved.body,'Ostatnie zdanie zapisane chwilę przed zmianą ekranu.');assert.match(a.node('local-save-status').textContent,/Zapisano na telefonie/);
});
test('app background lifecycle flushes a pending draft and Android onPause calls the flush hook',()=>{
 const a=setup([],null);a.node('post-body').value='Zapis przed przejściem do innej aplikacji.';a.run('scheduleLocalSave()');a.c.document.visibilityState='hidden';a.documentListeners.get('visibilitychange').listener();
 const saved=JSON.parse(JSON.parse(a.c.IL.readDraftLibrary()).data).drafts[0];assert.equal(saved.body,'Zapis przed przejściem do innej aplikacji.');assert.ok(a.documentListeners.has('window:pagehide'));
 const java=fs.readFileSync(path.join(__dirname,'../app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java'),'utf8');assert.match(java,/onPause\(\)[\s\S]{0,200}flushDraftOnExit/);
});
test('native storage failure blocks editor reset and preserves current text',()=>{
 const a=setup([],null);a.run('persistDraft()');a.c.IL.writeDraftLibrary=()=>JSON.stringify({ok:false,message:'Disk full'});a.node('post-body').value='Zachowaj ten tekst';assert.equal(a.run('resetEditor()'),false);assert.equal(a.node('post-body').value,'Zachowaj ten tekst');
});
test('startup reads native primary library rather than a stale WebView cache',()=>{
 const native={version:1,activeId:'native',drafts:[{id:'native',title:'Trwały tekst',body:'Nowa wersja',revisions:[]}]};const stale={version:1,activeId:'stale',drafts:[{id:'stale',title:'Stary tekst',body:'Stara wersja',revisions:[]}]};
 const a=setup([['il_redaktor_drafts_v3',JSON.stringify(stale)]],JSON.stringify(native));assert.equal(a.run('activeDraftId'),'native');assert.equal(a.run('readDraftStore().drafts[0].body'),'Nowa wersja');
});
test('existing 1.14 WebView draft library is migrated to native durable storage on startup',()=>{
 const previous={version:1,activeId:'previous',drafts:[{id:'previous',title:'Tekst z 1.14',body:'Treść',revisions:[]}]};const a=setup([['il_redaktor_drafts_v3',JSON.stringify(previous)]],null);const result=JSON.parse(a.c.IL.readDraftLibrary());assert.equal(result.exists,true);assert.equal(JSON.parse(result.data).drafts[0].title,'Tekst z 1.14');
});
test('correction comparison shows readable category names instead of numeric IDs',()=>{
 const a=setup();a.run("offerCorrection({category:'3503'},'Test')");assert.match(a.node('correction-fields').innerHTML,/Historia/);assert.match(a.node('correction-fields').innerHTML,/Aktualności/);assert.doesNotMatch(a.node('correction-fields').innerHTML,/3503|10543/);
});
test('general language request is not misrepresented as completed local grammar correction',async()=>{
 const a=setup();await a.run("editorHelp('Popraw język polski i usuń powtórzenia')");assert.equal(a.run('pendingCorrection'),null);assert.match(a.node('editor-chat-log').innerHTML,/wymaga skonfigurowanego AI/);
});

test('remote changes block article and tag writes and keep the local text',async()=>{
 const a=setup(),old=wpFixture(300),changed={...old,modified:'2026-10-31T11:00:00',content:{raw:'Nowsza wersja ze strony'}};a.c.old=old;a.run("postId=300;postStatus='publish';remoteBaseline=remoteVersion(old)");a.node('post-tags').value='Nowy tag';const calls=[];a.c.rpc=async(method)=>{calls.push(method);return changed};await a.run("savePost('draft')");assert.deepEqual(calls,['GET']);assert.equal(a.node('post-body').value,'Treść testowa');assert.equal(a.node('conflict-dialog').classList.contains('hidden'),false);assert.equal(a.node('save-publish').disabled,false);
});
test('old linked drafts without a baseline require comparison before any overwrite',async()=>{
 const a=setup();a.run("postId=301;postStatus='publish'");let writes=0;a.c.rpc=async(method)=>{if(method==='POST')writes++;return wpFixture(301)};await a.run("savePost('draft')");assert.equal(writes,0);assert.match(a.node('conflict-message').textContent,/wersji odniesienia/);
});
test('second preflight detects changes made while preparing tags',async()=>{
 const a=setup(),old=wpFixture(302);a.c.old=old;a.run("postId=302;postStatus='publish';remoteBaseline=remoteVersion(old)");a.node('post-tags').value='Tag';let reads=0,writes=0;a.c.ensureTags=async()=>[10];a.c.rpc=async(method)=>{if(method==='POST')writes++;return ++reads===1?old:{...old,modified:'changed'}};await a.run("savePost('draft')");assert.equal(writes,0);assert.equal(reads,2);
});
test('preflight read failure never falls back to an unsafe update',async()=>{
 const a=setup();a.c.old=wpFixture(303);a.run("postId=303;postStatus='publish';remoteBaseline=remoteVersion(old)");let writes=0;a.c.rpc=async(method)=>{if(method==='POST')writes++;throw Error('offline')};await a.run("savePost('draft')");assert.equal(writes,0);assert.match(a.node('finish-detail').textContent,/offline/);
});
test('local edits during preflight stop the update',async()=>{
 const a=setup();a.c.old=wpFixture(304);a.run("postId=304;postStatus='publish';remoteBaseline=remoteVersion(old)");let writes=0;a.c.rpc=async(method)=>{if(method==='POST')writes++;a.node('post-body').value='Nowszy tekst lokalny';return a.c.old};await a.run("savePost('draft')");assert.equal(writes,0);assert.equal(a.node('post-body').value,'Nowszy tekst lokalny');
});
test('keeping both copies detaches the local draft and loads the latest remote without writing WordPress',async()=>{
 const a=setup();a.c.old=wpFixture(305);a.c.latest={...a.c.old,content:{raw:'Aktualny tekst WP'},modified:'new'};a.run("postId=305;postStatus='publish';remoteBaseline=remoteVersion(old);persistDraft();showRemoteConflict(latest,'Zmiana')");const methods=[];a.c.rpc=async(method)=>{methods.push(method);return a.c.latest};assert.equal(await a.run('resolveRemoteConflict(true)'),true);assert.deepEqual(methods,['GET']);assert.equal(a.node('post-body').value,'Aktualny tekst WP');assert.equal(a.run("readDraftStore().drafts.some(d=>d.postId===null&&d.body==='Treść testowa')"),true);assert.equal(a.run('postId'),305);
});
test('loading remote version preserves the earlier local draft and its sources',async()=>{
 const a=setup();a.c.old=wpFixture(306);a.c.latest={...a.c.old,content:{raw:'Nowe WP'}};a.run("postId=306;postStatus='publish';remoteBaseline=remoteVersion(old);$('post-sources').value=JSON.stringify([{id:'s1',name:'Źródło',notes:'Notatka',checked:true}]);persistDraft();showRemoteConflict(latest,'Zmiana')");a.c.rpc=async()=>a.c.latest;assert.equal(await a.run('resolveRemoteConflict(false)'),true);assert.equal(a.run("readDraftStore().drafts.some(d=>d.body==='Treść testowa')"),true);assert.equal(JSON.parse(a.node('post-sources').value)[0].name,'Źródło');
});
test('baseline survives local saving and portable backup',()=>{
 const a=setup();a.c.remote=wpFixture(307);a.run("postId=307;postStatus='publish';remoteBaseline=remoteVersion(remote);persistDraft()");const backup=JSON.parse(a.run('makeDraftBackup()'));assert.equal(backup.drafts[0].remoteBaseline.id,307);assert.match(backup.drafts[0].remoteBaseline.signature,/Treść na stronie/);
});
test('restoring an earlier draft keeps the current remote baseline',()=>{
 const a=setup();a.c.old=wpFixture(308);a.run("postId=308;postStatus='publish';remoteBaseline=remoteVersion(old);persistDraft();$('post-body').value='Nowa';persistDraft(true)");a.c.latest={...a.c.old,modified:'later'};a.run("remoteBaseline=remoteVersion(latest);persistDraft(true);restoreDraftVersion(activeDraftId,0)");assert.equal(a.run('remoteBaseline.modified'),'later');
});
test('sources can create a draft before the article has a title or body',()=>{
 const a=setup();a.run("resetEditor();openSources();$('source-name').value='Gazeta';$('source-notes').value='Ważna informacja';saveSource()");assert.equal(a.run('readDraftStore().drafts.length'),2);assert.equal(JSON.parse(a.run('readDraftStore().drafts[1].sources'))[0].name,'Gazeta');
});
test('source status is manual and source notes never go to the post payload',async()=>{
 const a=setup();a.run("openSources();$('source-name').value='Archiwum';$('source-url').value='https://example.com/skan';$('source-notes').value='Prywatna notatka';$('source-checked').checked=true;saveSource()");const s=JSON.parse(a.node('post-sources').value)[0];assert.equal(s.checked,true);assert.ok(s.verifiedAt>0);let data;a.c.rpc=async(method,path,body)=>{if(method==='POST')data=body;return {id:309,status:'draft'}};await a.run("savePost('draft')");assert.doesNotMatch(JSON.stringify(data),/Prywatna notatka|example.com/);
});
test('sources reject script links and escape names and notes',()=>{
 const a=setup();a.run("openSources();$('source-name').value='<img onerror=bad>';$('source-url').value='javascript:alert(1)';saveSource()");assert.equal(a.run('currentSources().length'),0);a.node('source-url').value='https://example.com';a.run('saveSource()');assert.match(a.node('sources-list').innerHTML,/&lt;img/);assert.doesNotMatch(a.node('sources-list').innerHTML,/<img/);
});
test('research photos are portable and stored only once across revisions',()=>{
 const a=setup();a.run("persistDraft();sourcePhotoTarget={id:activeDraftId,source:null}");a.c.payload=JSON.stringify({mime:'image/jpeg',name:'Skan',base64:'/9j/2Q=='});a.run("receiveSourcePhoto(true,payload);$('post-body').value='Zmiana';persistDraft(true)");const data=JSON.parse(a.run('makeDraftBackup()'));assert.equal(Object.keys(data.sourceFiles).length,1);assert.equal(data.drafts[0].revisions.some(v=>JSON.stringify(v).includes('/9j/2Q==')),false);a.c.backup=JSON.stringify(data);assert.equal(a.run('Object.keys(parseDraftBackup(backup).sourceFiles).length'),1);
});
test('import keeps research photo files and source references',()=>{
 const a=setup();a.run("persistDraft();sourcePhotoTarget={id:activeDraftId,source:null}");a.c.payload=JSON.stringify({mime:'image/jpeg',name:'Skan',base64:'/9j/2Q=='});a.run('receiveSourcePhoto(true,payload)');const raw=a.run('makeDraftBackup()'),b=setup();b.c.backup=raw;b.run('previewDraftBackup(backup);mergeDraftBackup()');assert.equal(b.run('Object.keys(readDraftStore().sourceFiles).length'),1);assert.equal(b.run("readDraftStore().drafts.some(d=>JSON.parse(d.sources||'[]').some(s=>s.photo))"),true);
});
test('find and replace treats regex symbols and dollar replacements literally',()=>{
 const a=setup();a.c.value='a.b aXb a.b';const r=a.run("replacementResult(value,'a.b','$&',true,false)");assert.equal(r.count,2);assert.equal(r.value,'$& aXb $&');
});
test('HTML find and replace never changes attributes or code blocks',()=>{
 const a=setup();a.c.value='<a href="/most">most</a><code>most</code>';const r=a.run("replacementResult(value,'most','droga',false,true)");assert.equal(r.count,1);assert.equal(r.value,'<a href="/most">droga</a><code>most</code>');
});
test('find replacement remains a proposal until accepted',()=>{
 const a=setup();a.node('find-text').value='testowa';a.node('replace-text').value='nowa';a.node('find-field').value='body';a.run('proposeReplacement()');assert.equal(a.node('post-body').value,'Treść testowa');assert.equal(a.run('pendingCorrection.changes.body'),'Treść nowa');
});
test('preview switches between real phone and desktop viewport widths without a write',()=>{
 const a=setup();a.run("preview();setPreviewDevice('desktop')");assert.equal(a.node('post-preview').style.width,'1024px');a.run("setPreviewDevice('phone')");assert.equal(a.node('post-preview').style.width,'360px');assert.match(a.node('post-preview').srcdoc,/Treść testowa/);
});
test('calendar distinguishes local plans from remotely confirmed schedules',()=>{
 const a=setup();a.node('post-schedule').value='2026-11-03T15:30';a.run('persistDraft()');a.c.remote={...wpFixture(310,'future'),date_gmt:'2026-11-04T15:00:00'};a.run("allPosts=[remote];$('calendar-month').value='2026-11';renderCalendar()");assert.match(a.node('calendar-entries').innerHTML,/Plan roboczy/);assert.match(a.node('calendar-entries').innerHTML,/WordPress · zaplanowany/);assert.match(a.node('calendar-grid').innerHTML,/2026-11-03/);
});
test('calendar navigation handles year boundaries and changing months clears day selection',()=>{
 const a=setup();a.run("$('calendar-month').value='2026-12';moveCalendar(1)");assert.equal(a.node('calendar-month').value,'2027-01');a.run("selectedCalendarDay='2026-12-31';renderCalendar()");assert.equal(a.run('selectedCalendarDay'),'');
});
test('refreshing the calendar only reads data and never schedules a post',async()=>{
 const a=setup(),methods=[];a.c.rpc=async(method)=>{methods.push(method);return []};await a.run('refreshCalendar()');assert.deepEqual(methods,['GET','GET']);assert.match(a.node('calendar-output').textContent,/Nie zmieniono harmonogramu/);
});
test('opening animations respect reduced motion, app preference and battery saving',()=>{
 const a=setup();const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/index.html'),'utf8');assert.match(html,/@keyframes ilOpen/);assert.match(html,/prefers-reduced-motion:reduce/);assert.match(html,/data-effects="off"/);assert.match(html,/data-battery-saving="on"/);assert.match(html,/Kalendarz/);
});

test('remote calendar dates accept both UTC suffix and explicit offset',()=>{
 const a=setup();a.c.p={date_gmt:'2026-11-04T15:00:00+00:00'};assert.equal(a.run('remoteCalendarDate(p).toISOString()'),'2026-11-04T15:00:00.000Z');
});
test('changing source photo retains the old image while history still references it',()=>{
 const a=setup();a.run("persistDraft();sourcePhotoTarget={id:activeDraftId,source:null}");a.c.payload=JSON.stringify({mime:'image/jpeg',name:'Skan',base64:'/9j/2Q=='});a.run("receiveSourcePhoto(true,payload);sourcePhotoTarget={id:activeDraftId,source:currentSources()[0].id};receiveSourcePhoto(true,payload)");assert.equal(a.run('Object.keys(readDraftStore().sourceFiles).length'),2);
});
test('orphan research photos are removed only when no current or historical source references them',()=>{
 const a=setup();a.run("persistDraft();draftStore.sourceFiles={unused:{mime:'image/jpeg',base64:'/9j/2Q==',name:'Unused'}};persistDraft()");assert.equal(a.run('Object.keys(readDraftStore().sourceFiles).length'),0);
});

test('new portable backups use format version 2 to prevent old apps silently dropping research files',()=>{
 const a=setup();assert.equal(JSON.parse(a.run('makeDraftBackup()')).version,2);a.c.legacy=JSON.stringify({format:'IL_REDAKTOR_BACKUP',version:1,site:'ispinalokalnie.wordpress.com',drafts:[]});assert.equal(a.run('parseDraftBackup(legacy).length'),0);
});

test('dialogs have explicit viewport edges for Android WebViews without CSS inset support',()=>{
 const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/index.html'),'utf8');assert.match(html,/\.finish-dialog\{position:fixed;top:0;right:0;bottom:0;left:0;/);assert.match(html,/Witaj w redakcji IL!/);
});

test("WordPress readback rejects changed article text despite publish status",async()=>{
  const a=setup();
  a.c.rpc=async(method,path,body)=>method==="POST"?{id:401,status:"publish"}:
    {id:401,status:"publish",content:{raw:"<p>Inny tekst niż wysłany</p>"}};
  await a.run("savePost('publish')");
  assert.equal(a.node("finish-title").textContent,"TREŚĆ NIEPOTWIERDZONA");
  assert.match(a.node("finish-detail").textContent,/treść HTML/);
});

test("WordPress readback rejects missing content even when status matches",async()=>{
  const a=setup();
  a.c.rpc=async(method)=>method==="POST"?{id:402,status:"publish"}:{id:402,status:"publish",content:null};
  await a.run("savePost('publish')");
  assert.equal(a.node("finish-title").textContent,"TREŚĆ NIEPOTWIERDZONA");
});

test("WordPress readback detects different media and category",async()=>{
  const a=setup();a.node("post-media").value="33";
  a.c.rpc=async(method)=>method==="POST"?{id:403,status:"publish"}:
    {id:403,status:"publish",featured_media:44,categories:[999]};
  await a.run("savePost('publish')");
  assert.equal(a.node("finish-title").textContent,"TREŚĆ NIEPOTWIERDZONA");
  assert.match(a.node("finish-detail").textContent,/kategorie|zdjęcie/);
});

test("WordPress UTC date uses Z for Polish local time comparison",()=>{
  const a=setup();
  assert.equal(a.run("readWordPressScheduleTime({date_gmt:'2026-10-31T10:00:00'})"),Date.parse("2026-10-31T10:00:00Z"));
  assert.equal(a.run("readWordPressScheduleTime({date_gmt:'2026-10-31T10:00:00Z'})"),Date.parse("2026-10-31T10:00:00Z"));
});

test("automated local formatting preserves editorial words and requires review",()=>{
  const a=setup();a.node("post-body").value="W Ispinie rozpoczęto prace.\n\n## Co wiadomo?\n\n- Pierwszy fakt\n- Drugi fakt";
  assert.equal(a.run("formatArticleLocally()"),true);
  assert.equal(a.node("correction-dialog").classList.contains("hidden"),false);
  assert.match(a.run("pendingCorrection.changes.body"),/<h2>Co wiadomo\?<\/h2>/);
  assert.equal(a.node("post-mode").value,"text");
});

test("AI formatting guard rejects fabricated additional information",()=>{
  const a=setup();
  assert.equal(a.run("formattingMatchesOriginal('W Ispinie trwa remont.','text','<p>W Ispinie trwa remont. Prace zakończą się jutro.</p>')"),false);
  assert.equal(a.run("formattingMatchesOriginal('W Ispinie trwa remont.','text','<p>W Ispinie trwa remont.</p>')"),true);
  assert.equal(a.run("formattingMatchesOriginal('W Ispinie trwa remont.','text','<p onclick=\"alert(1)\">W Ispinie trwa remont.</p>')"),false);
  assert.equal(a.run("formattingMatchesOriginal('W Ispinie trwa remont.','text','<p>W Ispinie trwa remont.</p><script></script>')"),false);
});

test("free AI formatting handoff preserves original text until accepted",async()=>{
  const a=setup();
  a.node("post-body").value="W Ispinie trwa remont.";
  assert.equal(await a.run("formatArticleWithAI()"),true);
  assert.equal(a.node("assistant-return-target").value,"body");
  assert.equal(a.node("post-mode").value,"text");
  a.node("assistant-return-text").value="<p>W Ispinie trwa remont. Dodatkowe zdanie.</p>";
  assert.equal(a.run("compareExternalAssistantResponse()"),false);
  a.node("assistant-return-text").value="<p>W Ispinie trwa remont.</p>";
  assert.equal(a.run("compareExternalAssistantResponse()"),true);
  assert.equal(a.node("post-mode").value,"text");
  assert.equal(a.run("pendingCorrection.changes.mode"),"html");
});

test("editor change and focusout handlers persist drafts without needing network",()=>{
  const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/index.html'),'utf8');
  assert.match(html,/document\.addEventListener\("focusout"/);
  assert.match(html,/window\.addEventListener\("beforeunload",flushDraftOnExit\)/);
  assert.match(html,/addEventListener\("change",\(\)=>\{updateMetrics\(\);if\(!persistDraft\(true\)\)/);
});


test("short free RSS chat followups use preceding user topic without paid AI",()=>{
 const fs=require("node:fs"),path=require("node:path");
 const java=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java"),"utf8");
 const rss=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/FreeIspinaSearchClient.java"),"utf8");
 assert.match(java,/askConversation\(MainActivity\.this, prompt,/);
 assert.match(rss,/resolveConversationPrompt/);
 assert.match(rss,/contextInherited/);
});
test("wide radar has thematic public profiles and explicit scope coverage",()=>{
  const native=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/FreeIspinaSearchClient.java"),"utf8");
  for(const host of ["drwinia.pl","bip.malopolska.pl","powiat.bochnia.pl","zdw.krakow.pl","ezamowienia.gov.pl","bochnianin.pl","e-bochnia.pl","radiokrakow.pl","kolejemalopolskie.com.pl"])
    assert.ok(native.includes("site:"+host),host);
  assert.match(native,/plannedFeeds/);assert.match(native,/skippedFeeds/);
  assert.match(native,/elapsedRealtime\(\)\+51000L/);
  const ui=fs.readFileSync(path.join(__dirname,"../app/src/main/assets/index.html"),"utf8");
  assert.match(ui,/buildRadarChatGptPrompt/);assert.match(ui,/openRadarInChatGpt/);
  assert.match(ui,/copyRadarChatGptPrompt/);
  const a=setup();a.node("research-days").value="90";a.node("research-query").value="most i komunikacja";
  const prompt=a.run("buildRadarChatGptPrompt()");
  assert.match(prompt,/site:drwinia.pl/);assert.match(prompt,/site:bip.malopolska.pl/);assert.match(prompt,/site:bochnianin.pl/);
  assert.match(prompt,/most i komunikacja/);
  assert.match(prompt,/Nie wymyślaj faktów/);
  a.node("research-days").value="0";
  assert.match(a.run("buildRadarChatGptPrompt()"),/Cały dostępny zakres archiwum/);
});
test("free radar never claims RSS results are fact-checked and latest query applies 30 days",()=>{
 const a=setup();
 const found={freeSearch:true,sourceLinksFound:true,verified:true,sources:[{title:"Most w Ispinie",url:"https://example.org/most"}],text:"Most w Ispinie",answer:"Most w Ispinie",successfulFeeds:4,failedFeeds:2,partialResults:true};
 a.run("renderResearchResult("+JSON.stringify(found)+",'Ispina')");
 const output=a.node("research-results").innerHTML+" "+a.node("research-status").textContent;
 assert.match(output,/do sprawdzenia|do sprawdzenia/);
 assert.match(output,/wynik jest niepełny/);
 assert.doesNotMatch(output,/Zweryfikowane profile|potwierdzone źródła/);
 const ui=require("node:fs").readFileSync(require("node:path").join(__dirname,"../app/src/main/assets/index.html"),"utf8");
 assert.match(ui,/\$\("research-days"\)\.value="30"/);
 assert.match(ui,/\$\("research-query"\)\.value="Ispina"/);
});
test("research UI shows only source-backed section text and safe source links",()=>{
  const a=setup();
  const result={
    searchedAt:"2026-11-01T12:00:00Z",
    text:"Zbiorcze podsumowanie",
    sources:[],
    sections:[
      {status:"ok",title:"Profil potwierdzony",text:"Treść potwierdzona",sources:[{title:"Dokument",url:"https://example.com/raport"}],queries:["Ispina raport"]},
      {status:"inconclusive",title:"Profil bez potwierdzenia",text:"Ukryj tę niepotwierdzoną treść",sources:[],queries:[]}
    ]
  };
  a.run("renderResearchResult("+JSON.stringify(result)+")");
  const rendered=a.node("research-results").innerHTML;
  assert.match(rendered,/Profil potwierdzony/);
  assert.match(rendered,/data-research-link="https:\/\/example\.com\/raport"/);
  assert.match(rendered,/Profil bez znalezionych linków/);
  assert.doesNotMatch(rendered,/Ukryj tę niepotwierdzoną treść|Zbiorcze podsumowanie/);
});

test("free search cancellation clears pending callbacks and preserves the typed topic",async()=>{
 const a=setup();let id="",cancelled="";
 a.c.IL.askResearch=(rid)=>{id=rid};a.c.IL.cancelIspinaSearch=rid=>{cancelled=rid};
 a.run("connected=false;freeSearchReady=true");a.node("research-query").value="Most w Ispinie";
 const pending=a.run("runDeepIspinaResearch()");
 assert.ok(id);assert.equal(a.node("research-cancel").classList.contains("hidden"),false);
 a.run("cancelIspinaSearch('research')");
 assert.equal(await pending,false);assert.equal(cancelled,id);assert.equal(a.run("requests.size"),0);
 assert.equal(a.run("researchInProgress"),false);assert.equal(a.node("research-query").value,"Most w Ispinie");
 a.c.window.onNativeResult(id,true,JSON.stringify({sources:[{url:"https://example.org/late"}],text:"Late"}));
 assert.equal(a.node("research-results").innerHTML,"");
});
test("free search displays empty results without an AI login or false source error",async()=>{
 const a=setup();a.c.IL.askResearch=(id)=>a.c.window.onNativeResult(id,true,JSON.stringify({freeSearch:true,sources:[],answer:"Brak pasujących materiałów w dostępnych kanałach."}));
 a.run("connected=false;freeSearchReady=true");a.node("research-query").value="Ispina";
 await a.run("runDeepIspinaResearch()");
 assert.match(a.node("research-results").innerHTML,/Brak pasujących/);
 assert.equal(a.run("requests.size"),0);assert.equal(a.node("research-button").disabled,false);
});

test("ChatGPT Free handoff prefers dedicated no-API bridge",()=>{
  const a=setup();a.node("assistant-prompt").value="Przygotuj propozycję do sprawdzenia";
  let prompt="";a.c.IL.shareChatGptFreePrompt=text=>{prompt=text};
  a.c.IL.shareAssistantPrompt=()=>{throw Error("Do not use paid or generic assistant");};
  assert.equal(a.run("sendAssistantToApps()"),true);
  assert.match(prompt,/Przygotuj propozycję do sprawdzenia/);
  assert.match(prompt,/w Ispinie, do Ispiny, z Ispiny/);
  assert.match(a.node("assistant-handoff-status").textContent,/ChatGPT Free/);
});

test("audit repairs: partial radar scans never appear as a complete success",()=>{
  const a=setup();
  const found={freeSearch:true,sourceLinksFound:true,sources:[{title:"Ispina",url:"https://example.org/ispina"}],text:"Ispina",answer:"Ispina",successfulFeeds:12,failedFeeds:0,skippedFeeds:3,plannedFeeds:15,partialResults:true};
  a.run("renderResearchResult("+JSON.stringify(found)+",'Ispina')");
  assert.match(a.node("research-status").textContent,/12\/15/);
  assert.equal(a.node("research-status").className,"small muted");
});
test("audit repairs: restored memory, published dates, and source tracking are integrated",()=>{
  const a=setup();
  assert.equal(a.run("editorialDisplayDate('')"),"BRAK DATY");
  assert.match(a.run("editorialDisplayDate('2026-11-01')"),/2026/);
  const sqlite=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/EditorialMemory.java"),"utf8");
  const native=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java"),"utf8");
  const radar=fs.readFileSync(path.join(__dirname,"../app/src/main/java/com/ispinalokalnie/redaktor/IspinaRadarWorker.java"),"utf8");
  assert.match(sqlite,/importAll\(arg\)/);assert.match(sqlite,/db\.beginTransaction\(\)/);
  assert.match(native,/importEditorialMemory\(\)/);assert.match(native,/runDiagnostics\(String rid\)/);
  assert.match(radar,/skippedFeeds/);assert.match(radar,/Automatyczny Radar Ispiny/);
});
test("audit repairs: search includes excerpts, body and identifiers",()=>{
  const a=setup();
  a.run("allPosts=[{id:734,status:'publish',title:{rendered:'Aktualność'},excerpt:{rendered:'Historia OSP Ispina'},content:{rendered:'Kapliczka we wsi'},modified:'2026-11-01'}]");
  a.node("posts-search").value="Kapliczka";a.run("renderPosts()");
  assert.match(a.node("posts-output").innerHTML,/Aktualność/);
  a.node("posts-search").value="734";a.run("renderPosts()");
  assert.match(a.node("posts-output").innerHTML,/Aktualność/);
  a.node("posts-search").value="nieistniejące";a.run("renderPosts()");
  assert.match(a.node("posts-output").innerHTML,/Brak wyników/);
});
test("audit repairs: cannot publish unresolved source placeholders",async()=>{
  const a=setup();
  a.run("connected=true");
  a.node("post-title").value="Wpis o Ispinie";
  a.node("post-body").value="Ważny fakt [DO UZUPEŁNIENIA: data]";
  await a.run("savePost('publish')");
  assert.match(a.node("post-output").innerHTML,/Nie publikuję artykułu/);
});


test("returned WordPress ID is durably saved before a failing read-after-write",async()=>{
  const a=setup([],null);a.node("post-title").value="Wpis";a.node("post-body").value="Treść na stronie";a.run("connected=true");
  let storedId;
  a.c.rpc=async(method)=>{if(method==='POST')return {id:456,status:'draft'};
    storedId=a.run("readDraftStore().drafts.find(d=>d.id===activeDraftId).postId");throw Error('offline after write');};
  await a.c.savePost('draft');
  assert.equal(storedId,456);
  a.run("draftStore=null;activeDraftId=null;$('post-title').value='';$('post-body').value='';restoreLocal(true)");
  assert.equal(a.run('postId'),456);
  assert.match(a.node('finish-title').textContent,/ZAPIS NIEZWERYFIKOWANY/);
});

test("memory pagination uses offset and rejects stale responses",async()=>{
  const a=setup();let resolveOld;
  a.c.editorialRequest=()=>new Promise(resolve=>resolveOld=resolve);
  const old=a.c.loadEditorialMemory();
  a.node('editorial-memory-topic').value='OSP';let request;
  a.c.editorialRequest=async(action,data)=>{request=data;return {items:[],total:0,hasMore:false}};
  await a.c.loadEditorialMemory();resolveOld({items:[{url:'https://example.org/old',title:'Stary wynik'}]});await old;
  assert.equal(request.topic,'OSP');assert.equal(request.offset,0);
  assert.doesNotMatch(a.node('editorial-memory-results').innerHTML,/Stary wynik/);
});


test("zero and absent source dates are never converted to a fabricated publication date",()=>{
 const a=setup();assert.equal(a.c.editorialDisplayDate(0),'BRAK DATY');assert.equal(a.c.editorialDisplayDate('0'),'BRAK DATY');assert.equal(a.c.editorialDisplayDate(''),'BRAK DATY');
});
test("source coverage reports escaped failures even when no source was found",()=>{
 const a=setup();a.c.renderResearchResult({freeSearch:true,sources:[],profiles:[{name:'BIP',status:'unavailable',reason:'HTTP 403 <script>bad</script>'}],answer:'Brak wyników'},'Ispina');
 assert.match(a.node('research-results').innerHTML,/BIP/);assert.match(a.node('research-results').innerHTML,/HTTP 403 &lt;script&gt;/);assert.doesNotMatch(a.node('research-results').innerHTML,/<script>/);
});


test("services retain the full original page before an uncertain write",async()=>{
 const a=setup();a.run("servicesRaw='<h3>Dawna firma</h3>';servicesLoaded=true");let calls=0;
 a.c.rpc=async(method)=>{if(method==='GET')return {id:818,content:{raw:'<h3>Dawna firma</h3>'}};calls++;throw Error('offline');};
 await assert.rejects(a.c.persistServiceList([]),/offline/);assert.equal(calls,1);
 a.c.showServiceRecovery();assert.equal(a.node('service-recovery-content').value,'<h3>Dawna firma</h3>');
});
test("services never write when the recovery copy cannot be saved",async()=>{
 const a=setup();a.run("servicesRaw='Original page';servicesLoaded=true");let writes=0;
 a.c.IL.saveServicePageBackup=()=>JSON.stringify({ok:false,message:'Brak miejsca'});
 a.c.rpc=async(method)=>{if(method==='POST')writes++;return {id:818,content:{raw:'Original page'}};};
 await assert.rejects(a.c.persistServiceList([]),/Brak miejsca/);assert.equal(writes,0);
});


test("a POST timeout remains flagged after restart and cannot silently send again",async()=>{
 const a=setup([],null);let writes=0;a.c.rpc=async(method)=>{if(method==='POST')writes++;throw Error('timeout');};
 await a.c.savePost('draft');assert.equal(writes,1);
 a.run("draftStore=null;activeDraftId=null;pendingPublicationWrite=null;$('post-title').value='';$('post-body').value='';restoreLocal(true)");
 assert.equal(a.run('pendingPublicationWrite.status'),'draft');
 let warning='';a.c.confirm=text=>{warning=text;return false;};
 await a.c.savePost('draft');assert.equal(writes,1);assert.match(warning,/duplikat/);
 const backup=JSON.parse(a.c.makeDraftBackup());assert.equal(backup.drafts[0].pendingWrite.status,'draft');
});
test("verified successful writes clear the durable pending-operation warning",async()=>{
 const a=setup([],null);a.c.rpc=async()=>({id:789,status:'draft'});
 await a.c.savePost('draft');assert.equal(a.run('pendingPublicationWrite'),null);
 assert.equal(a.run('readDraftStore().drafts[0].pendingWrite'),null);
});


for(const days of [7,30,90]) test(`ordinary OSP and bridge questions always search fresh sources with ${days} days`,async()=>{
 const a=setup();a.run('freeSearchReady=true');a.node('ispina-chat-days').value=String(days);
 for(const question of ['Czy coś się pojawiło o OSP?','Czy wiadomo coś o moście w Ispinie?']){
  let requested=null,archiveReads=0;
  a.c.IL.editorialMemory=(id,action)=>{if(action==='browse')archiveReads++;a.c.window.onNativeResult(id,true,JSON.stringify({saved:true,items:[{title:'Stary dokument'}]}));};
  a.c.IL.askIspinaChatWithDays=(id,prompt,history,range)=>{requested={prompt,range};a.c.window.onNativeResult(id,true,JSON.stringify({answer:'Brak nowych publikacji',sources:[],lookbackDays:range,profiles:[{name:'BIP',status:'ok'}]}));};
  a.node('ispina-chat-input').value=question;
  await a.c.sendIspinaChat();assert.equal(archiveReads,0);assert.equal(requested.range,days);
  assert.match(a.node('ispina-chat-status').textContent,new RegExp('Ostatnie '+days+' dni'));assert.match(a.node('ispina-chat-log').innerHTML,/Zakres sprawdzenia/);
 }
});
test('a period in the question overrides the selector and is saved',async()=>{
 const a=setup();a.run('freeSearchReady=true');a.node('ispina-chat-days').value='30';a.node('ispina-chat-input').value='A z ostatnich 90 dni o OSP?';let days;
 a.c.IL.askIspinaChatWithDays=(id,p,h,d)=>{days=d;a.c.window.onNativeResult(id,true,JSON.stringify({answer:'Brak wyników',sources:[]}));};
 await a.c.sendIspinaChat();assert.equal(days,90);assert.equal(a.node('ispina-chat-days').value,'90');assert.equal(a.storage.get('il_chat_lookback_days'),'90');
});
test('fresh search refuses to silently use an old unfiltered native bridge',async()=>{
 const a=setup();a.run('freeSearchReady=true');a.node('ispina-chat-input').value='Most w Ispinie';let oldCalls=0;a.c.IL.askIspinaChat=()=>oldCalls++;
 await a.c.sendIspinaChat();assert.equal(oldCalls,0);assert.match(a.node('ispina-chat-status').textContent,/nie obsługuje zakresu dat/);assert.equal(a.node('ispina-chat-input').value,'Most w Ispinie');
});
test('archive is used only for an explicit question about remembered materials',async()=>{
 const a=setup();a.run('freeSearchReady=true');a.node('ispina-chat-input').value='Co znaleźliśmy w pamięci o moście?';let live=0;
 a.c.IL.askIspinaChatWithDays=()=>live++;
 a.c.IL.editorialMemory=(id,action)=>a.c.window.onNativeResult(id,true,JSON.stringify(action==='browse'?{items:[{title:'Dawne źródło',url:'https://example.org/old'}]}:{saved:true}));
 await a.c.sendIspinaChat();assert.equal(live,0);assert.match(a.node('ispina-chat-log').innerHTML,/Archiwalna odpowiedź/);
});
test('all-new preset respects the selected window and has no fixed thirty-day wording',()=>{
 const a=setup();a.node('ispina-chat-days').value='7';let value;a.c.sendIspinaChat=()=>{value=a.node('ispina-chat-input').value};a.c.prefillIspinaChat('Cokolwiek nowego o Ispinie');
 assert.equal(value,'Cokolwiek nowego o Ispinie');assert.equal(a.c.chatLookbackDays(value),7);
 const ui=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/index.html'),'utf8');assert.match(ui,/>Autobusy<\/button>/);assert.match(ui,/>Cokolwiek nowego o Ispinie<\/button>/);
});
test('dates and incomplete channel reports remain visible alongside chat sources',()=>{
 const a=setup();a.run("ispinaChatMessages=[{role:'assistant',verified:true,content:'Nowy link',sources:[{title:'OSP Ispina',url:'https://example.org/osp',publishedAt:1793505600000}],profiles:[{name:'PSP',status:'unavailable',reason:'HTTP 403'}]}];renderIspinaChat()");
 assert.match(a.node('ispina-chat-log').innerHTML,/Data publikacji według źródła/);assert.match(a.node('ispina-chat-log').innerHTML,/HTTP 403/);
});


test('live progress shows actual source URLs and access errors before the final result',async()=>{
 const a=setup();a.run('freeSearchReady=true');a.node('ispina-chat-input').value='Cokolwiek nowego o Ispinie';let id,finish;
 a.c.IL.askIspinaChatWithDays=rid=>{id=rid;finish=()=>a.c.window.onNativeResult(rid,true,JSON.stringify({answer:'Wynik nierozstrzygający',sources:[],outcome:'INCONCLUSIVE'}));};
 const pending=a.c.sendIspinaChat();assert.ok(id);
 a.c.window.onIspinaSearchProgress(id,{sequence:1,name:'Urząd',url:'https://www.drwinia.pl/aktualnosci',stage:'READING',method:'DIRECT_PUBLISHER',detail:'Czytam artykuły'});
 assert.match(a.node('ispina-chat-live').innerHTML,/www.drwinia.pl\/aktualnosci/);assert.match(a.node('ispina-chat-live').innerHTML,/Czytam stronę/);
 a.c.window.onIspinaSearchProgress(id,{sequence:2,name:'Urząd',url:'https://www.drwinia.pl/aktualnosci',stage:'ERROR',method:'DIRECT_PUBLISHER',detail:'HTTP 403 <script>bad</script>'});
 assert.match(a.node('ispina-chat-live').innerHTML,/Błąd odczytu/);assert.doesNotMatch(a.node('ispina-chat-live').innerHTML,/<script>/);
 finish();await pending;assert.match(a.node('ispina-chat-status').textContent,/nie ustalono/);
 const before=a.node('ispina-chat-live').innerHTML;a.c.window.onIspinaSearchProgress(id,{sequence:3,name:'Stara odpowiedź',url:'https://example.org/stale',stage:'DONE'});assert.equal(a.node('ispina-chat-live').innerHTML,before);
});
test('undated discoveries are visible separately from new publications',async()=>{
 const a=setup();a.run('freeSearchReady=true');a.node('ispina-chat-input').value='OSP';
 a.c.IL.askIspinaChatWithDays=id=>a.c.window.onNativeResult(id,true,JSON.stringify({answer:'Nie ustalono dat',sources:[],outcome:'INCONCLUSIVE',undatedSources:[{title:'Dokument OSP',url:'https://example.org/osp'}]}));
 await a.c.sendIspinaChat();assert.match(a.node('ispina-chat-log').innerHTML,/Materiały do ustalenia daty/);assert.match(a.node('ispina-chat-log').innerHTML,/Dokument OSP/);
});
test('quick buttons never overwrite a new question while a scan is running',()=>{
 const a=setup();a.run('ispinaChatInProgress=true');a.node('ispina-chat-input').value='Moje nowe pytanie';assert.equal(a.c.prefillIspinaChat('Most'),false);assert.equal(a.node('ispina-chat-input').value,'Moje nowe pytanie');
});

test('live progress opens the source journal once and keeps current reads first',async()=>{
 const a=setup();a.run('freeSearchReady=true');a.node('ispina-chat-input').value='OSP';let id,finish,scrolls=0;
 a.node('ispina-chat-live-panel').scrollIntoView=()=>scrolls++;
 a.c.IL.askIspinaChatWithDays=rid=>{id=rid;finish=()=>a.c.window.onNativeResult(rid,true,JSON.stringify({answer:'Wynik nierozstrzygający',sources:[]}));};
 const pending=a.c.sendIspinaChat();
 a.c.window.onIspinaSearchProgress(id,{sequence:1,name:'Pierwszy',url:'https://example.org/done',stage:'DONE'});
 a.c.window.onIspinaSearchProgress(id,{sequence:2,name:'Teraz',url:'https://example.org/now',stage:'READING'});
 assert.equal(scrolls,1);assert.equal(a.node('ispina-chat-live-panel').open,true);
 const rendered=a.node('ispina-chat-live').innerHTML;assert.ok(rendered.indexOf('Teraz')<rendered.indexOf('Pierwszy'));
 finish();await pending;
});
test('external news precedes the collapsed archive of the editors own portal',()=>{
 const a=setup();a.run("ispinaChatMessages=[{role:'assistant',verified:true,content:'Publikacje do sprawdzenia',sources:[{title:'Własny tekst',url:'https://ispinalokalnie.wordpress.com/test',sourceKind:'OWN_PORTAL'},{title:'Wiadomość zewnętrzna',url:'https://example.org/article',snippet:'Fragment <script>bad</script>'}]}];renderIspinaChat()");
 const rendered=a.node('ispina-chat-log').innerHTML;
 assert.ok(rendered.indexOf('Wiadomość zewnętrzna')<rendered.indexOf('Własny tekst'));
 assert.match(rendered,/<details class="source-item"><summary>Twój portal/);
 assert.match(rendered,/Publikacje zewnętrzne \(1\)/);assert.doesNotMatch(rendered,/<script>/);
});

test('wide Radar keeps publisher dates and identifies own portal entries',()=>{
 const a=setup();a.c.renderResearchResult({freeSearch:true,text:'Znalezione publikacje',sources:[{title:'Własny tekst',url:'https://ispinalokalnie.wordpress.com/test',sourceKind:'OWN_PORTAL',published:'2026-10-09T10:00:00+02:00'}]},'Ispina');
 const rendered=a.node('research-results').innerHTML;assert.match(rendered,/Twój portal/);assert.match(rendered,/Data publikacji według źródła/);assert.doesNotMatch(rendered,/BRAK DATY/);
});

for (const changed of ['status','content']) test(`recovery preserves pending write when remote ${changed} differs`,async()=>{
 const a=setup([],null);let reads=0;
 a.c.rpc=async(method)=>{if(method==='POST')return {id:901,status:'draft'};if(++reads===1)throw Error('timeout');return changed==='status'?{id:901,status:'publish'}:{id:901,status:'draft',content:{raw:'Old content'}};};
 await a.c.savePost('draft');
 a.run("draftStore=null;activeDraftId=null;pendingPublicationWrite=null;lastPublicationAttempt=null;postId=null;$('post-title').value='';$('post-body').value='';restoreLocal(true)");
 await a.c.verifyLastPublication();
 assert.ok(a.run('pendingPublicationWrite'),'must retain uncertainty');
 assert.doesNotMatch(a.node('finish-title').textContent,/STATUS ZWERYFIKOWANY/);
 assert.equal(a.run('publicationBusy'),false);
});
test('recovery validates the sent payload after restart, not subsequent local edits',async()=>{
 const a=setup([],null);let reads=0;
 a.c.rpc=async(method)=>{if(method==='POST')return {id:902,status:'draft'};if(++reads===1)throw Error('timeout');return {id:902,status:'draft',modified:'2026-11-02T08:00:00'};};
 await a.c.savePost('draft');
 a.node('post-body').value='New unsent local text';a.c.persistDraft(true);
 a.run("draftStore=null;activeDraftId=null;pendingPublicationWrite=null;lastPublicationAttempt=null;postId=null;$('post-title').value='';$('post-body').value='';restoreLocal(true)");
 const backup=JSON.parse(a.c.makeDraftBackup());assert.equal(backup.drafts[0].pendingWrite.expected.content,'<p>Treść testowa</p>');
 await a.c.verifyLastPublication();
 assert.equal(a.run('pendingPublicationWrite'),null);
 assert.equal(a.node('post-body').value,'New unsent local text');
 assert.equal(a.run('readDraftStore().drafts[0].dirty'),true);
 assert.ok(a.run('remoteBaseline'));
});
test('recovery locks draft switching and concurrent writes until its read finishes',async()=>{
 const a=setup();a.run("postId=903;postStatus='draft'");let finish;let calls=0;
 a.c.rpc=()=>{calls++;return new Promise(resolve=>finish=resolve)};
 const checking=a.c.verifyLastPublication();
 assert.equal(a.c.resetEditor(),false);
 await a.c.verifyLastPublication();await a.c.savePost('draft');assert.equal(calls,1);
 finish({id:903,status:'draft'});await checking;assert.equal(a.run('publicationBusy'),false);
});
test('legacy pending write without payload stays uncertain after checking its status',async()=>{
 const a=setup();a.run("postId=904;pendingPublicationWrite={id:904,status:'draft',title:'Old draft'}");
 a.c.rpc=async()=>({id:904,status:'draft'});await a.c.verifyLastPublication();assert.ok(a.run('pendingPublicationWrite'));
});

test('opening another draft cannot verify a failed attempt belonging to the previous draft',async()=>{
 const a=setup();a.c.rpc=async()=>{throw Error('timeout')};await a.c.savePost('draft');
 a.c.resetEditor();assert.equal(a.run('lastPublicationAttempt'),null);
 a.run("lastPublicationAttempt={id:77,status:'draft',title:'Other'}");
 a.c.applyDraft({title:'Current',body:'Current body',postId:88,postStatus:'draft'});
 const calls=[];a.c.rpc=async(method,path)=>{calls.push(path);return {id:88,status:'draft'}};
 await a.c.verifyLastPublication();assert.deepEqual(calls,['posts/88?context=edit']);
});
test('recovery cannot confirm a changed scheduled publication time',async()=>{
 const a=setup([],null);let reads=0;a.node('post-schedule').value=new Date(Date.now()+86400000).toISOString().slice(0,16);
 a.c.rpc=async(method)=>{if(method==='POST')return {id:905,status:'future'};if(++reads===1)throw Error('timeout');return {id:905,status:'future',date_gmt:'2000-01-01T00:00:00'}};
 await a.c.savePost('future');await a.c.verifyLastPublication();assert.ok(a.run('pendingPublicationWrite'));assert.match(a.node('finish-detail').textContent,/termin/);
});
test('failed local confirmation keeps pending state even after a valid remote recovery',async()=>{
 const a=setup([],null);let reads=0;
 a.c.rpc=async(method)=>{if(method==='POST')return {id:906,status:'draft'};if(++reads===1)throw Error('timeout');return {id:906,status:'draft'}};
 await a.c.savePost('draft');a.c.IL.writeDraftLibrary=()=>JSON.stringify({ok:false,message:'Disk full'});
 await a.c.verifyLastPublication();assert.ok(a.run('pendingPublicationWrite'));assert.match(a.node('finish-title').textContent,/KOPIA LOKALNA NIEZAPISANA/);assert.equal(a.run('publicationBusy'),false);
});
test('editing while read-after-write is pending preserves unsent text as dirty',async()=>{
 const a=setup([],null);let finish;
 a.c.rpc=async(method)=>method==='POST'?{id:907,status:'draft'}:new Promise(resolve=>finish=resolve);
 const saving=a.c.savePost('draft');
 while(!finish)await new Promise(resolve=>setImmediate(resolve));
 a.node('post-body').value='Later text not sent';
 finish({id:907,status:'draft'});await saving;
 assert.equal(a.node('post-body').value,'Later text not sent');
 assert.equal(a.run('readDraftStore().drafts[0].dirty'),true);
 assert.match(a.node('finish-detail').textContent,/nie zostały jeszcze wysłane/);
});

test('TEST-6 recovery marks unchanged remote-confirmed draft as synchronized',async()=>{
  const a=setup([],null);let reads=0;
  a.c.rpc=async method=>{if(method==='POST')return {id:947,status:'draft'};if(++reads===1)throw Error('temporary network loss');return {id:947,status:'draft'}};
  await a.c.savePost('draft');
  a.run("draftStore=null;activeDraftId=null;pendingPublicationWrite=null;lastPublicationAttempt=null;postId=null;$('post-title').value='';$('post-body').value='';restoreLocal(true)");
  assert.match(a.run('pendingPublicationWrite.editorSignature'),/post/);
  await a.c.verifyLastPublication();
  assert.equal(a.run('pendingPublicationWrite'),null);
  assert.equal(a.run('readDraftStore().drafts[0].dirty'),false);
  assert.match(a.node('finish-title').textContent,/STATUS ZWERYFIKOWANY/);
});
test('TEST-6 recovery keeps changed tags and body as unsent after restart',async()=>{
  const a=setup([],null);let reads=0;
  a.c.rpc=async method=>{if(method==='POST')return {id:948,status:'draft'};if(++reads===1)throw Error('temporary network loss');return {id:948,status:'draft'}};
  await a.c.savePost('draft');
  a.node('post-tags').value='nowe tagi lokalne';a.node('post-body').value='Dodatkowe zdanie napisane później';a.c.persistDraft(true);
  a.run("draftStore=null;activeDraftId=null;pendingPublicationWrite=null;lastPublicationAttempt=null;postId=null;$('post-title').value='';$('post-body').value='';restoreLocal(true)");
  await a.c.verifyLastPublication();
  assert.equal(a.run('pendingPublicationWrite'),null);
  assert.equal(a.run('readDraftStore().drafts[0].dirty'),true);
  assert.equal(a.node('post-tags').value,'nowe tagi lokalne');
  assert.match(a.node('finish-detail').textContent,/wyłącznie lokalnie/);
});
