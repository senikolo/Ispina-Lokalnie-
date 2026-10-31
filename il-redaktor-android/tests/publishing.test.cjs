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

function setup(initialStorage=[]) {
  const html = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/index.html"), "utf8");
  const code = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
  assert.ok(code, "Missing UI script");
  const dom = new Map();
  function node(id) {
    if (!dom.has(id)) {
      const classes = new Set(["hidden"]);
      dom.set(id, {
        id, value:"", textContent:"", innerHTML:"", className:"",
        style:{}, srcdoc:"",
        classList: {
          add(...xs){xs.forEach(x=>classes.add(x))},
          remove(...xs){xs.forEach(x=>classes.delete(x))},
          contains(x){return classes.has(x)},
          toggle(x,force){if(force===undefined? !classes.has(x):force)classes.add(x);else classes.delete(x)}
        },
        checked:false,disabled:false,
        addEventListener(){},
        setAttribute(){},removeAttribute(){},
      });
    }
    return dom.get(id);
  }
  const storage = new Map(initialStorage);
  const IL = {status(){},assistantStatus(){},openSite(){},showPublishedPage(){}};
  const c = {
    window:{IL, scrollTo(){}},IL,document:{
      getElementById:node,querySelectorAll(){return []},documentElement:{setAttribute(){}}
    },setTimeout,clearTimeout,console,Date,TextEncoder,TextDecoder,URL,
    localStorage:{
      getItem(k){return storage.get(k)||null},
      setItem(k,v){storage.set(k,v)},
      removeItem(k){storage.delete(k)}
    },confirm(){return true},btoa,atob
  };
  vm.createContext(c);
  vm.runInContext(code,c);
  vm.runInContext("connected=true;document.getElementById('post-title').value='Testowy artykuł';document.getElementById('post-body').value='Treść testowa';document.getElementById('post-mode').value='text';document.getElementById('post-category').value='10543';document.getElementById('post-media').value='0';",c);
  return {c,node,storage,run:(script)=>vm.runInContext(script,c)};
}

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
});

test("OAuth waiting indicator is pending, not a forever-spinning progress state",()=>{
  const a=setup();
  a.run('window.onNativeResult("auth-progress",true,"Krok 3/4: oczekuję na powrót.")');
  assert.match(a.node("working-state").textContent,/Oczekuję/);
  assert.match(a.node("working-state").className,/pending/);
  assert.equal(a.node("login-recovery").classList.contains("hidden"),false);
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
  const a=setup(),calls=[];a.run("postId=203;postStatus='future'");a.node("post-schedule").value=dateInFuture(60);
  a.c.rpc=async(method,path,body)=>{calls.push({method,path,body});return method==="POST"?{id:203,status:"future"}:{id:203,status:"future",date_gmt:calls[0].body.date}};
  await a.run("savePost('draft')");
  assert.equal(calls[0].path,"posts/203");
  assert.equal(calls[0].body.status,"future");
  assert.equal(calls[0].body.date.endsWith("Z"),true);
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
  const a=setup(),calls=[];a.run("postId=209;postStatus='publish'");
  a.c.rpc=async(method,path,body)=>{calls.push({method,path,body});return method==="POST"?{id:209,status:"publish"}:{id:209,status:"publish"}};
  await a.run("savePost('draft')");
  assert.equal(calls.filter(c=>c.method==="POST").length,1);assert.equal(calls[0].path,"posts/209");
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
  assert.match(gradle,/versionCode 21/);assert.match(gradle,/versionName '1\.15\.0'/);
  assert.match(gradle,/applicationId 'com\.ispinalokalnie\.redaktorsmart\.stable'/);
  assert.match(workflow,/versionCode='21' versionName='1\.15\.0'/);
});

test("AI editor shortcut copies current title and text into a reviewable prompt without sending it",()=>{
  const a=setup();a.node("post-title").value="Wiadomości z Ispiny";a.node("post-body").value="Treść do poprawienia";
  a.run("assistantFromEditor('Skróć tekst')");
  assert.match(a.node("assistant-prompt").value,/Skróć tekst/);
  assert.match(a.node("assistant-prompt").value,/Wiadomości z Ispiny/);
  assert.match(a.node("assistant-prompt").value,/Treść do poprawienia/);
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

test("OAuth DCR, PKCE, deep-link scheme and manifest callback match without a client secret",()=>{
  const root=path.join(__dirname,"../app/src/main");
  const oauth=fs.readFileSync(path.join(root,"java/com/ispinalokalnie/redaktor/OAuthBrowserLogin.java"),"utf8");
  const wp=fs.readFileSync(path.join(root,"java/com/ispinalokalnie/redaktor/WpMcp.java"),"utf8");
  const manifest=fs.readFileSync(path.join(root,"AndroidManifest.xml"),"utf8");
  assert.match(oauth,/scope=global/);assert.match(oauth,/code_challenge_method=S256/);
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
 a.c.rpc=async()=>({id:61,status:'publish',link:'https://ispinalokalnie.wordpress.com/realny-artykul/'});await a.run("savePost('publish')");assert.equal(a.run('openPromotion()'),true);assert.match(a.node('promotion-text').value,/https:\/\/ispinalokalnie.wordpress.com\/realny-artykul\//);assert.match(a.node('promotion-text').value,/Łączy nas Ispina/);
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
