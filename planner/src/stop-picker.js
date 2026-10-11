function ilClosePicker(inp,box){
 box.classList.remove('on');inp.setAttribute('aria-expanded','false');
 inp.removeAttribute('aria-activedescendant');box._active=-1;
}
function sg(inp,box){
 var a=nr(inp.value),items=window.IL_STOP_CATALOG?window.IL_STOP_CATALOG():CAT;
 var rows=items.filter(function(x){return !a||[x.n].concat(x.aliases||[]).some(function(name){return nr(name).indexOf(a)>=0})});
 rows.sort(function(x,y){var px=a&&nr(x.n).indexOf(a)===0?0:1,py=a&&nr(y.n).indexOf(a)===0?0:1;return px-py||x.n.localeCompare(y.n,'pl')});
 rows=rows.slice(0,12);box._active=-1;box.scrollTop=0;
 box.innerHTML=rows.length?rows.map(function(x,i){return '<button class="plsug" type="button" role="option" tabindex="-1" aria-selected="false" id="'+box.id+'-option-'+i+'" data-v="'+encodeURIComponent(x.n)+'"><span>'+xh(x.n)+'</span><span class="plsrc">'+xh(x.src)+'</span></button>'}).join(''):'<div class="plnone" role="status">Brak pasujących przystanków. Wybierz pełną nazwę z listy.</div>';
 box.classList.add('on');inp.setAttribute('aria-expanded','true');
}
function sb(inp,box){
 var pending=0,closing=0,suppressClickUntil=0,touch=null,mouse=null;
 var other=q(inp.id==='pfrom'?'pto':'pfrom'),otherBox=q(inp.id==='pfrom'?'psto':'psfrom');
 inp.setAttribute('role','combobox');inp.setAttribute('aria-autocomplete','list');
 inp.setAttribute('aria-controls',box.id);inp.setAttribute('aria-expanded','false');inp.setAttribute('spellcheck','false');box.setAttribute('role','listbox');
 function close(){clearTimeout(pending);pending=0;ilClosePicker(inp,box)}
 inp.ilClosePicker=close;
 function show(){pending=0;if(touch||mouse){pending=setTimeout(show,80);return}if(d0.activeElement!==inp||inp.value.trim().length<2){close();return}if(other.ilClosePicker)other.ilClosePicker();else ilClosePicker(other,otherBox);sg(inp,box)}
 function option(target){var b=target&&target.closest&&target.closest('.plsug');return b&&box.contains(b)?b:null}
 function select(button){
  if(!button||!box.contains(button))return;clearTimeout(closing);clearTimeout(pending);pending=0;
  inp.value=decodeURIComponent(button.getAttribute('data-v')||'');
  // Keep the same list and its scroll position until explicit acceptance.
  inp._ilSelecting=true;
  try{inp.focus({preventScroll:true})}catch(e){inp.focus()}
  inp._ilSelecting=false;box.classList.add('on');inp.setAttribute('aria-expanded','true');
  box.querySelectorAll('.plsug').forEach(function(b,i){var chosen=b===button;b.classList.toggle('il-r15-chosen',chosen);b.setAttribute('aria-selected',String(chosen));if(chosen)box._active=i});
  inp.setAttribute('aria-activedescendant',button.id);
  inp.dispatchEvent(new Event('change',{bubbles:true}));
  inp.dispatchEvent(new Event('il-stop-selected',{bubbles:true}));
 }
 inp.ilShowPicker=show;
 inp.onfocus=function(){clearTimeout(closing);if(other.ilClosePicker)other.ilClosePicker();if(!inp._ilSelecting)close()};
 inp.oninput=function(){clearTimeout(pending);clearTimeout(closing);inp.removeAttribute('data-il-stop-key');if(inp.value.trim().length<2){close();return}pending=setTimeout(show,110)};
 inp.onblur=function(){clearTimeout(pending);closing=setTimeout(close,200)};
 function point(list,id){for(var i=0;list&&i<list.length;i++)if(list[i].identifier===id)return list[i];return null}
 function moved(gesture,p){if(p&&Math.pow(p.clientX-gesture.x,2)+Math.pow(p.clientY-gesture.y,2)>100)gesture.moved=true}
 box.addEventListener('touchstart',function(e){var p=e.touches&&e.touches[0];touch=p?{id:p.identifier,x:p.clientX,y:p.clientY,button:option(e.target),moved:e.touches.length!==1}:null;mouse=null;}, {passive:true});
 box.addEventListener('touchmove',function(e){if(!touch)return;if(e.touches.length!==1)touch.moved=true;moved(touch,point(e.touches,touch.id));},{passive:true});
 box.addEventListener('touchcancel',function(){touch=null;suppressClickUntil=Date.now()+800;},{passive:true});
 box.addEventListener('touchend',function(e){
  var gesture=touch,p=gesture&&point(e.changedTouches,gesture.id);touch=null;suppressClickUntil=Date.now()+800;
  if(!gesture||!p)return;moved(gesture,p);
  var b=option(e.target);if(gesture.moved||!b||b!==gesture.button||e.touches&&e.touches.length)return;
  if(e.cancelable)e.preventDefault();e.stopPropagation();select(b);
 },true);
 function scrolled(){if(touch)touch.moved=true;if(mouse)mouse.moved=true}
 box.addEventListener('scroll',scrolled,{passive:true});
 var card=inp.closest('.plcard');if(card)card.addEventListener('scroll',scrolled,{passive:true});
 box.addEventListener('mousedown',function(e){var b=option(e.target);if(!b||e.button!==0)return;mouse={x:e.clientX,y:e.clientY,button:b,moved:false};e.preventDefault()});
 box.addEventListener('mousemove',function(e){if(mouse)moved(mouse,e)});
 d0.addEventListener('mouseup',function(e){if(mouse)moved(mouse,e);if(mouse&&(!option(e.target)||!box.contains(e.target)))mouse=null;});
 box.addEventListener('click',function(e){var b=option(e.target);if(!b)return;if(e.cancelable)e.preventDefault();e.stopPropagation();var gesture=mouse;mouse=null;if(Date.now()<suppressClickUntil||gesture&&(gesture.moved||gesture.button!==b))return;select(b)},true);
 inp.addEventListener('keydown',function(e){
  if(e.key==='Escape'){e.preventDefault();close();return}
  if(e.key==='ArrowDown'||e.key==='ArrowUp'){
   e.preventDefault();if(!box.classList.contains('on'))show();var options=box.querySelectorAll('.plsug');if(!options.length)return;
   var index=box._active;if(index<0)index=e.key==='ArrowDown'?0:options.length-1;else index=(index+(e.key==='ArrowDown'?1:-1)+options.length)%options.length;
   box._active=index;options.forEach(function(b,i){b.setAttribute('aria-selected',String(i===index))});inp.setAttribute('aria-activedescendant',options[index].id);options[index].scrollIntoView({block:'nearest'});
  }else if(e.key==='Enter'&&box.classList.contains('on')&&box._active>=0){e.preventDefault();e.stopImmediatePropagation();select(box.querySelectorAll('.plsug')[box._active])}
 },true);
}
