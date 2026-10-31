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

function setup() {
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
        addEventListener(){},
      });
    }
    return dom.get(id);
  }
  const storage = new Map();
  const IL = {status(){},assistantStatus(){},openSite(){}};
  const c = {
    window:{IL, scrollTo(){}},IL,document:{
      getElementById:node,querySelectorAll(){return []}
    },setTimeout,clearTimeout,console,Date,TextEncoder,TextDecoder,
    localStorage:{
      getItem(k){return storage.get(k)||null},
      setItem(k,v){storage.set(k,v)},
      removeItem(k){storage.delete(k)}
    },confirm(){return true},btoa,atob
  };
  vm.createContext(c);
  vm.runInContext(code,c);
  vm.runInContext("connected=true;document.getElementById('post-title').value='Testowy artykuł';document.getElementById('post-body').value='Treść testowa';document.getElementById('post-mode').value='text';document.getElementById('post-category').value='10543';document.getElementById('post-media').value='0';",c);
  return {c,node,run:(script)=>vm.runInContext(script,c)};
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
  assert.match(a.node("finish-title").textContent,/SUKCES/);
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
  assert.match(a.node("finish-title").textContent,/NIE POWIODŁO/);
  assert.match(a.node("finish-detail").textContent,/HTTP 403/);
});


test("confirmed publication exposes real public permalink in a dialog and persistent editor button",async()=>{
  const a=setup();
  const expected="https://ispinalokalnie.wordpress.com/2026/10/31/probny-artykul/";
  const opened=[];
  a.c.IL.openSite=(url)=>opened.push(url);
  a.c.rpc=async(method,p,body)=>method==="POST"?
    {id:67,status:"publish"}:
    {id:67,status:"publish",link:expected};
  await a.run("savePost('publish')");
  assert.equal(a.node("finish-open").textContent,"🌐 Zobacz opublikowany artykuł na stronie IL");
  assert.equal(a.node("view-published-post").classList.contains("hidden"),false);
  assert.match(a.node("publication-phase").textContent,/SUKCES/);
  a.run("openFinishUrl()");
  a.run("openPublishedPost()");
  assert.deepEqual(opened,[expected,expected]);
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
  assert.match(a.node("finish-title").textContent,/SUKCES/);
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
