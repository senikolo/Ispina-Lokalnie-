/* Ispina Lokalnie — Planer R13.2 MOTION RELIABLE: explicit time-based SVG drawing even when Android limits UI animations.
   Progressive-enhancement layer over R11. No timetable or route algorithm changes. */
(function(){
 'use strict';
 var d=document, v=d.getElementById('vplan'), f=d.getElementById('pfrom'),t=d.getElementById('pto'),
     pres=d.getElementById('pres'),mode=d.getElementById('ilTransferMode');
 if(!v||!f||!t||!pres||!mode||d.getElementById('il-r12-style'))return;
 var st=d.createElement('style');st.id='il-r12-style';st.textContent=[
  '#vplan .il-r12-confirm-slot{flex:0 0 70px;width:70px;min-width:70px;display:flex;align-items:center;justify-content:center;height:36px;position:relative;box-sizing:border-box}',
  '#vplan .il-r12-accept{display:inline-flex;align-items:center;justify-content:center;width:68px;height:32px;min-height:32px;border:1.5px solid #248b77;border-radius:11px;padding:0 3px!important;background:#e3f6ed;color:#126450;font-size:11px;line-height:1.15;font-weight:850;white-space:nowrap;touch-action:manipulation;cursor:pointer;-webkit-tap-highlight-color:transparent;transition:background .16s ease,box-shadow .16s ease}',
  '#vplan .il-r12-accept:active{background:#bde9d4;box-shadow:inset 0 2px 5px rgba(12,75,57,.14)}',
  '#vplan .il-r12-confirm-slot.il-r12-empty .il-r12-accept,#vplan .il-r12-confirm-slot.il-r12-accepted .il-r12-accept{display:none}',
  '#vplan .il-r12-tick{display:none;width:46px;height:44px;min-width:44px;min-height:44px;padding:0;border:0;align-items:center;justify-content:center;border-radius:10px;background:transparent;color:#168c4b;cursor:pointer;touch-action:manipulation;-webkit-tap-highlight-color:transparent}',
  '#vplan .il-r12-confirm-slot.il-r12-accepted .il-r12-tick{display:flex}',
  '#vplan .il-r12-tick svg{height:37px;width:37px;display:block;overflow:visible}',
  '#vplan .il-r12-tick circle,#vplan .il-r12-tick path{fill:none;stroke:#168c4b;stroke-linecap:round;stroke-linejoin:round}',
  '#vplan .plfield.il-r12-confirmed{border-color:#68bf8c!important;background:#f3fcf6!important;box-shadow:0 0 0 2px rgba(22,133,73,.10)!important}',
  '#vplan .il-r12-direct-title{font-size:16px;font-weight:850;color:#185b52;margin:15px 0 8px;padding:9px 12px;border-left:5px solid #198c7a;border-radius:5px 11px 11px 5px;background:#eaf7f3}',
  '#vplan #ilTransferResults{margin-top:20px;border-top:2px solid #badbd2;padding-top:12px}',
  '#vplan #ilTransferResults .sectitle{font-size:16px;font-weight:850;color:#185b52;margin-bottom:7px;padding:9px 12px;border-left:5px solid #245e91;border-radius:5px 11px 11px 5px;background:#eef5fb}',
  '#vplan .il-r10-actions{display:none!important}',
  '/* R13.1: SVG line progress is set in JS via requestAnimationFrame (Android WebView-friendly). */',
  '#vplan .il-r13-circle{stroke-width:2.5;transform:rotate(-90deg);transform-origin:16px 16px}',
  '#vplan .il-r13-short{stroke-width:3.5}',
  '#vplan .il-r13-long{stroke-width:3.5}',
  '#vplan .il-r132-version{display:inline-block;vertical-align:middle;margin-left:9px;font-size:10px;font-weight:700;color:#168c4b;border:1px solid #c9e8d4;border-radius:20px;padding:2px 7px;background:#f2faf4;letter-spacing:.02em}',
  '#vplan .il-r13-circle,#vplan .il-r13-short,#vplan .il-r13-long{stroke-dashoffset:100;stroke-dasharray:100 100;transition:none!important;animation:none!important}',
  '#vplan .il-r13-complete .il-r12-tick{filter:drop-shadow(0 1px 2px rgba(22,140,75,.14))}',
  '@media(max-width:350px){#vplan .il-r12-confirm-slot{flex-basis:64px;width:64px;min-width:64px}#vplan .il-r12-accept{width:62px;font-size:10px}}',
  '@media(prefers-reduced-motion:reduce){#vplan .il-r12-tick{transition:none!important}}',
  '#vplan.il-r15-senior .il-r12-confirm-slot{height:40px}#vplan.il-r15-senior .il-r12-accept{height:38px;font-size:12px}'
 ].join('\n');(d.head||d.documentElement).appendChild(st);
 // The old R10 "Gotowe" button is not needed: confirming the second field closes editing.
 // Keep the detached button reference to invoke R10's existing, well-tested exit routine.
 var oldDone=d.querySelector('.il-r10-done'),oldBar=d.querySelector('.il-r10-actions');
 if(oldBar)oldBar.remove();
 // A requested confirmation has its own default-on setting. Older generic
 // motion/reduced-motion preferences must not skip the first Akceptuj drawing.
 var records=[],confirmationMotionKey='il_confirm_motion_v2',confirmationMotionEnabled=true;
 try{confirmationMotionEnabled=localStorage.getItem(confirmationMotionKey)!=='0'}catch(e){}
 function motionAllowed(){return confirmationMotionEnabled}
 function init(input,other){
  var field=input.closest('.plfield');if(!field)return;
  var slot=d.createElement('span');slot.className='il-r12-confirm-slot il-r12-empty';
  var button=d.createElement('button');button.className='il-r12-accept';button.type='button';button.textContent='Akceptuj';
  button.setAttribute('aria-label',input===f?'Akceptuj miejsce startu':'Akceptuj cel podróży');
  var ok=d.createElement('button');ok.type='button';ok.className='il-r12-tick';
  ok.setAttribute('aria-label',input===f?'Start zaakceptowany. Powtórz animację':'Cel zaakceptowany. Powtórz animację');
  ok.title='Powtórz animację potwierdzenia';
  ok.innerHTML='<svg viewBox="0 0 32 32" aria-hidden="true"><circle class="il-r13-circle" cx="16" cy="16" r="13"/><path class="il-r13-short" d="M8.5 16.5 L13 20.8"/><path class="il-r13-long" d="M13 20.8 L24.2 9.4"/></svg>';
  slot.appendChild(ok);
  // Keep the preexisting R8 clear button on the right, in its original position.
  var clear=field.querySelector('.il-r8-clear');if(clear)field.insertBefore(slot,clear);else field.appendChild(slot);
  var review=d.createElement('div');review.className='il-r15-review';review.id=input.id+'-review';
  var change=d.createElement('button');change.className='il-r15-change';change.type='button';change.textContent='Zmień';
  change.setAttribute('aria-label',input===f?'Zmień miejsce startu':'Zmień cel podróży');
  review.appendChild(button);review.appendChild(change);field.insertAdjacentElement('afterend',review);
  var error=d.createElement('span');error.className='il-r14-field-error';error.id=input.id+'-error';error.setAttribute('role','status');review.insertAdjacentElement('afterend',error);input.setAttribute('aria-describedby',error.id);
  var record={input:input,field:field,slot:slot,button:button,accepted:'',last:'',seq:0,raf:0,interval:0,timeout:0};records.push(record);
  function cancelAnimation(){
   record.seq++;
   if(record.raf)cancelAnimationFrame(record.raf);
   if(record.interval)clearInterval(record.interval);
   if(record.timeout)clearTimeout(record.timeout);
   record.raf=record.interval=record.timeout=0;
   slot.classList.remove('il-r13-animating','il-r13-complete');
   input.style.removeProperty('transition');input.style.removeProperty('transform');input.style.removeProperty('transform-origin');
  }
  function strokes(){
   return [slot.querySelector('.il-r13-circle'),slot.querySelector('.il-r13-short'),slot.querySelector('.il-r13-long')];
  }
  function resetStrokes(){
   // Fixed SVG geometry avoids APIs absent from older Android WebViews.
   var els=strokes(),lengths=[2*Math.PI*13,Math.sqrt(4.5*4.5+4.3*4.3),Math.sqrt(11.2*11.2+11.4*11.4)];
   for(var i=0;i<els.length;i++){
    var length=lengths[i];
    els[i].style.setProperty('stroke-dasharray',length+' '+length,'important');
    els[i].style.setProperty('stroke-dashoffset',length,'important');
   }
   return {els:els,lengths:lengths};
  }
  function syncAfterEdit(){if(input.value.trim()!==record.accepted){cancelAnimation();input.removeAttribute('data-il-stop-key');input.removeAttribute('data-il-stop-label');input.removeAttribute('data-il-stop-ids');input.removeAttribute('aria-invalid');error.textContent=''}sync();}
  function sync(){
   var value=input.value.trim(),confirmed=!!value&&record.accepted===value,chosen=window.IL_STOP_RESOLVE&&window.IL_STOP_RESOLVE(value);
   slot.classList.toggle('il-r12-empty',value.length<2);
   slot.classList.toggle('il-r12-accepted',confirmed);
   field.classList.toggle('il-r12-confirmed',confirmed);
   button.disabled=!chosen||confirmed;
   button.textContent=chosen?'Akceptuj: '+chosen.n:'Wybierz przystanek z listy';
   button.setAttribute('aria-label',chosen?'Akceptuj: '+chosen.n:(input===f?'Wybierz miejsce startu z listy':'Wybierz cel podróży z listy'));
   input.readOnly=confirmed;input.setAttribute('aria-readonly',String(confirmed));input.tabIndex=confirmed?-1:0;
   review.classList.toggle('il-r15-visible',value.length>=2);
   review.classList.toggle('il-r15-confirmed',confirmed);
   review.classList.toggle('il-r15-active',d.activeElement===input||review.contains(d.activeElement));
   change.disabled=!confirmed;
   // A changed value immediately invalidates the prior confirmation.
   if(record.accepted && !confirmed)record.accepted='';
   record.last=value;
  }
  record.sync=sync;
  function confirm(){
   var chosen=window.IL_STOP_RESOLVE&&window.IL_STOP_RESOLVE(input.value);
   if(!chosen){if(input.ilClosePicker)input.ilClosePicker();input.setAttribute('aria-invalid','true');error.textContent='Wybierz pełny przystanek z listy albo wszystkie przystanki miejscowości.';return}
   var value=chosen.n;cancelAnimation();record.accepted=value;input.value=value;input.setAttribute('data-il-stop-key',chosen.key);input.setAttribute('data-il-stop-label',value);input.setAttribute('data-il-stop-ids',JSON.stringify(chosen.ids));input.removeAttribute('aria-invalid');error.textContent='';sync();
   var suggestions=d.getElementById(input===f?'psfrom':'psto');
   if(input.ilClosePicker)input.ilClosePicker();else if(suggestions){suggestions.classList.remove('on');input.setAttribute('aria-expanded','false');}
   var data=resetStrokes(),seq=record.seq;
   slot.classList.add('il-r13-animating');
   // Akceptuj and tick replay intentionally use exactly the same animation.
   var total=790, lastFrame=Date.now(), elapsed=0, finished=false;
   function clamp(x){return Math.max(0,Math.min(1,x));}
   function ease(x){x=clamp(x);return 1-Math.pow(1-x,3);}
   function progress(el,length,t,begin,duration){
    var proportion=ease((t-begin)/duration);
    el.style.setProperty('stroke-dashoffset',String(length*(1-proportion)),'important');
   }
   function finish(){
    if(finished||seq!==record.seq||record.accepted!==input.value.trim())return;
    finished=true;
    if(record.interval)clearInterval(record.interval);record.interval=0;
    if(record.timeout)clearTimeout(record.timeout);record.timeout=0;
    for(var i=0;i<data.els.length;i++)data.els[i].style.setProperty('stroke-dashoffset','0','important');
    input.style.removeProperty('transform');input.style.removeProperty('transform-origin');
    slot.classList.remove('il-r13-animating');slot.classList.add('il-r13-complete');
    var otherRecord=records.filter(function(x){return x.input!==input})[0];
    if(otherRecord&&(!otherRecord.accepted||otherRecord.accepted!==otherRecord.input.value.trim())){
     if(d.activeElement===input||d.activeElement===d.body)otherRecord.input.focus();
    }else if(oldDone){oldDone.click();}
    else{input.blur();}
   }
   function frame(){
    if(finished||seq!==record.seq||record.accepted!==input.value.trim())return;
    if(!motionAllowed()||d.hidden){finish();return}
    // A stalled WebView must still paint the stages rather than skip to ✓.
    var now=Date.now();elapsed+=Math.max(0,Math.min(45,now-lastFrame));lastFrame=now;
    var t=elapsed;
    // Pulse input by 5% while keeping its parent and the entire layout fixed.
    var wave=t<180?Math.sin(Math.PI*clamp(t/180)):0;
    input.style.setProperty('transform-origin','left center','important');
    input.style.setProperty('transform','scale('+(1+0.05*wave).toFixed(4)+')','important');
    // Stage 1: ring; stage 2: short LEFT arm; stage 3: long RIGHT arm.
    progress(data.els[0],data.lengths[0],t,180,260);
    progress(data.els[1],data.lengths[1],t,470,100);
    progress(data.els[2],data.lengths[2],t,580,180);
    if(t>=total)finish();
   }
   if(!motionAllowed()){finish();return}
   frame();record.interval=setInterval(frame,26);
   record.timeout=setTimeout(function watch(){if(finished||seq!==record.seq)return;frame();if(!finished)record.timeout=setTimeout(watch,1000);},total+1200);

  }
  input.addEventListener('il-stop-selected',syncAfterEdit,false);
  function keepFocus(ev){if(ev.cancelable)ev.preventDefault()}
  button.addEventListener('pointerdown',keepFocus,false);button.addEventListener('mousedown',keepFocus,false);
  button.addEventListener('click',function(ev){ev.preventDefault();ev.stopPropagation();confirm();},false);
  ok.addEventListener('pointerdown',function(ev){ev.preventDefault();},false);
  ok.addEventListener('click',function(ev){ev.preventDefault();ev.stopPropagation();confirm();},false);
  change.addEventListener('pointerdown',keepFocus,false);change.addEventListener('mousedown',keepFocus,false);
  change.addEventListener('click',function(ev){
   ev.preventDefault();ev.stopPropagation();cancelAnimation();record.accepted='';syncAfterEdit();
   try{input.focus({preventScroll:true})}catch(e){input.focus()}
   if(input.ilShowPicker)input.ilShowPicker();
   try{input.setSelectionRange(0,input.value.length)}catch(e){}
  },false);
  input.addEventListener('focus',function(ev){
   if(record.accepted&&record.accepted===input.value.trim()){input.blur();ev.stopImmediatePropagation();return}
   records.forEach(function(x){x.sync()});
  },true);
  input.addEventListener('blur',function(){setTimeout(function(){records.forEach(function(x){x.sync()})},0)},false);
  input.addEventListener('input',syncAfterEdit,false);
  input.addEventListener('change',syncAfterEdit,false);
  // The previous suggestion picker sets input.value programmatically.
  var box=d.getElementById(input===f?'psfrom':'psto');
  if(box){box.addEventListener('click',function(){setTimeout(syncAfterEdit,0);},false);box.addEventListener('touchend',function(){setTimeout(syncAfterEdit,0);},{passive:true});}
  // Selecting or typing is provisional. Only the Akceptuj button confirms.
  // Reset status on programmatic swap, destination chips, and near-stop alternatives.
  var form=v.querySelector('.plcard');if(form)form.addEventListener('click',function(){setTimeout(syncAfterEdit,0);},false);
  sync();
  return record;
 }
 init(f,t);init(t,f);
 function syncAll(){records.forEach(function(x){x.sync()});}
 ['pswap','phome','pchips','ilR7UseStop'].forEach(function(id){var el=d.getElementById(id);if(el)el.addEventListener('click',function(){setTimeout(syncAll,0);},false);});
 var sort=d.createElement('label');sort.className='il-r14-sort';sort.innerHTML='Sortuj wyniki <select id="ilPlannerSort"><option value="departure">Najbliższy odjazd</option><option value="arrival">Najszybszy przyjazd</option><option value="wait">Najmniej czekania</option></select>';v.querySelector('.plcard').appendChild(sort);
 d.getElementById('ilPlannerSort').addEventListener('change',function(){d.getElementById('pgo').click()});
 var marker=d.createElement('p');marker.id='ilR14Marker';marker.className='il-r14-version';marker.textContent='Wybierz przystanki i naciśnij Akceptuj.';v.insertBefore(marker,v.firstChild);
 var about=d.createElement('p');about.id='ilR14About';about.className='hint';about.textContent='Ispina Lokalnie • wersja '+window.IL_BUILD.versionName+' • wydanie stabilne';var settings=d.getElementById('vset');if(settings){settings.appendChild(about);var animation=d.createElement('label');animation.className='set';animation.innerHTML='<span><b>Animacja potwierdzenia przystanku</b><br><small>Po „Akceptuj”, niezależnie od animacji ekranów</small></span><input type="checkbox" class="sw" id="ilConfirmMotion">';settings.appendChild(animation);var toggle=d.getElementById('ilConfirmMotion');toggle.checked=motionAllowed();toggle.onchange=function(){confirmationMotionEnabled=this.checked;try{localStorage.setItem(confirmationMotionKey,this.checked?'1':'0')}catch(e){}}}
 var css=d.createElement('style');css.id='il-r14-style';css.textContent='.il-r14-version{font-size:12px;color:#17683b;font-weight:800;margin:4px 0 10px}.il-r14-field-error{display:block;color:#9b2920;font-size:13px;line-height:1.4;margin:2px 4px 6px}.il-r14-field-error:empty{display:none}.plsug[aria-selected=true]{outline:3px solid #16766b;background:#e8f5ef}.il-r14-day{font-size:14px;margin:18px 0 8px;color:#285b52}.il-r14-sort{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-top:12px}.il-r14-sort select{font-size:16px;min-height:44px;max-width:100%}.il-r14-more{display:block;min-height:44px;width:100%;margin:12px 0;padding:12px;border:2px solid #247a70;border-radius:12px;background:#e7f4f1;color:#125b51;font-weight:800;font-size:14px}.il-r14-invalid,.il-r14-refresh-note{border:2px solid #a34d13;background:#fff3df;padding:10px;border-radius:12px}.prtime small,.prarr small,.il-transfer-leg small{font-size:11px;font-weight:600}.prtop{flex-wrap:wrap}.il-r12-accept{min-height:44px!important;width:70px!important;font-size:11px!important}.il-r12-confirm-slot{height:46px!important;flex-basis:72px!important;min-width:72px!important}.il-r8-clear{min-height:44px!important;min-width:36px!important}body.senior .il-r14-more,body.senior .il-r14-sort select{font-size:18px}body.noanim .il-r12-tick{transition:none!important}@media(max-width:350px){#vplan .plfield input{min-width:0!important;font-size:16px!important}#vplan .il-r12-confirm-slot{flex-basis:70px!important;min-width:70px!important}}#iaP.il-r10-editing #vplan .plcard{max-height:var(--ilr14-panelmax,calc(100vh - 90px))!important;overflow-y:auto!important;overscroll-behavior:contain}#iaP.il-r10-editing #vplan .plcard .plsugs{position:relative!important;top:auto!important;left:0!important;right:0!important;width:100%!important;margin-top:8px}#iaP.il-r10-editing #vplan .plcard > .il-r14-field-error:not(:empty){display:block!important}#vplan .plfield,#iaP.il-r10-editing #vplan .plfield{height:58px!important;min-height:58px!important}#vplan.il-r15-senior .plfield, #iaP.il-r10-editing #vplan.il-r15-senior .plfield{height:66px!important;min-height:66px!important}body.noanim #iaP.il-r10-editing #vplan .plcard,body.noanim #iaP.il-r10-editing #vplan .plsugs.on{animation:none!important;transition:none!important}';d.head.appendChild(css);
 var safeStyle=d.createElement('style');safeStyle.id='il-r15-style';safeStyle.textContent=[
  'html[data-il-r15-editing] #ilDayBottom,html[data-il-r15-editing] #ilSeniorBottom{display:none!important}html[data-il-r15-editing] #iaToast{pointer-events:none!important}',
  '#vplan .il-r15-review{display:none!important;align-items:center;justify-content:flex-end;margin:6px 0 8px;box-sizing:border-box}',
  '#vplan .il-r15-review.il-r15-visible,#iaP.il-r10-editing #vplan .plcard > .il-r15-review.il-r15-visible{display:flex!important}',
  '#iaP.il-r10-editing #vplan .plcard > .il-r15-review:not(.il-r15-active){display:none!important}',
  '#vplan .il-r15-review .il-r12-accept{display:block!important;width:100%!important;height:auto!important;min-height:54px!important;padding:11px 14px!important;font-size:15px!important;line-height:1.35!important;white-space:normal!important;overflow-wrap:anywhere;text-align:center;border-radius:13px}',
  '#vplan .il-r15-review .il-r12-accept:disabled{opacity:.65;cursor:default}',
  '#vplan .il-r15-change{display:none;min-height:44px;padding:8px 18px;border:1.5px solid #75ad98;border-radius:11px;background:#eef7f2;color:#155841;font-size:14px;font-weight:750;touch-action:manipulation;cursor:pointer}',
  '#vplan .il-r15-review.il-r15-confirmed .il-r12-accept{display:none!important}',
  '#vplan .il-r15-review.il-r15-confirmed .il-r15-change{display:block}',
  '#vplan .il-r12-confirm-slot:not(.il-r12-accepted){display:none!important}',
  '#vplan .plfield.il-r12-confirmed .il-r8-clear{display:none!important}',
  '#vplan .plfield input[readonly]{cursor:default}',
  '#vplan .plcard .plsugs,#iaP.il-r10-editing #vplan .plcard .plsugs{max-height:var(--ilr10-listmax,320px)!important;padding:8px!important;touch-action:pan-y!important;scroll-behavior:auto!important}',
  '#vplan .plcard .plsug,#iaP.il-r10-editing #vplan .plcard .plsug{display:flex!important;flex-direction:column;align-items:flex-start!important;justify-content:center;gap:4px;min-height:66px!important;padding:12px 14px!important;margin:0 0 8px!important;border:1px solid #d4e6de!important;border-radius:12px!important;background:#f7fbf9!important;white-space:normal!important;text-align:left;touch-action:pan-y!important;transition:none!important;animation:none!important}',
  '#vplan .plcard .plsug:last-child{margin-bottom:0!important}',
  '#vplan .plcard .plsug > span:first-child{font-size:16px;line-height:1.35;font-weight:750;color:#183e32;overflow-wrap:anywhere}',
  '#vplan .plcard .plsug .plsrc{font-size:11px;line-height:1.3;color:#47685b}',
  '#vplan .plcard .plsug[aria-selected=true],#iaP.il-r10-editing #vplan .plcard .plsug[aria-selected=true]{border:2px solid #16766b!important;outline:none!important;background:#dff3e9!important}',
  '#vplan .plcard .plsug.il-r15-chosen > span:first-child::before{content:"Wybrano: ";color:#12604a;font-weight:800}',
  '#iaP.il-r10-editing #vplan .plcard .plsugs.on{animation:none!important;transition:none!important}',
  '#vplan.il-r15-senior .plcard .plsug, #iaP.il-r10-editing #vplan.il-r15-senior .plcard .plsug{min-height:78px!important}#vplan.il-r15-senior .plcard .plsug > span:first-child, #iaP.il-r10-editing #vplan.il-r15-senior .plcard .plsug > span:first-child,#vplan.il-r15-senior .il-r15-review .il-r12-accept, #iaP.il-r10-editing #vplan.il-r15-senior .il-r15-review .il-r12-accept{font-size:20px!important}#vplan.il-r15-senior .il-r15-change{font-size:18px}',
  '#vplan.il-r15-senior .plfield,#iaP.il-r10-editing #vplan.il-r15-senior .plfield{height:66px!important;min-height:66px!important}#vplan.il-r15-senior #pfrom,#vplan.il-r15-senior #pto,#iaP.il-r10-editing #vplan.il-r15-senior #pfrom,#iaP.il-r10-editing #vplan.il-r15-senior #pto{font-size:20px!important;height:48px!important;min-height:48px!important}',
 ].join('\n');d.head.appendChild(safeStyle);
 function syncSenior(){v.classList.toggle('il-r15-senior',!!d.body&&d.body.classList.contains('senior'))}
 syncSenior();if(window.MutationObserver&&d.body)new MutationObserver(syncSenior).observe(d.body,{attributes:true,attributeFilter:['class']});
 window.IL_PLANNER_UI_VERSION='R15-SAFE-STOP-PICKER-20261010';
})();
