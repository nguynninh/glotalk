// Exercise the real inline UI handlers without audio hardware or a network service.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const elements = new Map();
const element = id => {
  if (!elements.has(id)) elements.set(id, {
    value:'', textContent:'', checked:false, selectedOptions:[{textContent:'Tiếng Anh'}],
    classList:{toggle(){}},addEventListener(){},querySelector(){return {textContent:''};},pause(){},removeAttribute(name){delete this[name];},
  });
  return elements.get(id);
};
let calls=0, fail=false, stream=null;
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const context=vm.createContext({
  document:{getElementById:element}, window:{addEventListener(){}},
  attachSpectrogram:()=>Object.assign(()=>{}, {startLive(){},appendPCM(){}}),
  LiveRecognizer:class {setAudible(){} async start({onEvent,target_language,translation_mode}){
    assert.equal(translation_mode,'standard');
    assert.equal(target_language,element('target').value);
    if(stream)return stream(onEvent);
    onEvent({type:'final',text:'Xin chào'});
    onEvent({type:'speakers',segments:[{start:0,end:1,speaker:1,text:'Xin chào'}]});
    if(target_language)onEvent({type:'translation',id:0,revision:'refined',translation:{text:'Hello'}});
    onEvent({type:'done',metrics:{audio_s:1,processing_s:1,rtf:1}});
  }},
  AbortController,DOMException,setTimeout,clearTimeout,
  URL:{createObjectURL:()=>"blob:speech",revokeObjectURL(){}},
  fetch:async (url,options)=>{
    calls++;
    if(url==='/api/tts'){assert.equal(JSON.parse(options.body).language,'en');return {ok:!fail,json:async()=>({error:'Speech unavailable'}),blob:async()=>({})};}
    assert.equal(url,'/api/translate');
    assert.equal(JSON.parse(options.body).target,'en');
    return {ok:!fail,json:async()=>fail?{error:'Provider unavailable'}:{text:'Hello'}};
  },
});
const html=readFileSync(new URL('../ui/index.html',import.meta.url),'utf8');
const script=html.split('<script type="module">')[1].split('</script>')[0].replace(/^import .*;$/gm,'');
vm.runInContext(script,context);
element('text').value='Xin chào';element('target').value='en';
await element('translate').onclick();
assert.equal(element('translation').value,'Hello');
assert.equal(element('text').value,'Xin chào');
fail=true;
await element('translate').onclick();
assert.equal(element('translation').value,'');
assert.equal(element('text').value,'Xin chào');
assert.equal(element('error').textContent,'Provider unavailable');
assert.equal(element('translate').disabled,false);
fail=false;
vm.runInContext('selected={};',context);
await element('run').onclick();
assert.equal(element('translation').value,'Hello');
assert.match(element('text').value,/Người 1: Xin chào/);
assert.equal(calls,2);
element('target').value='';
await element('run').onclick();
assert.equal(calls,2,'Translation disabled should make no request');
// Live UI renders socket translations without making HTTP translation calls.
element('target').value='en';
let emit,finish;
stream=onEvent=>{emit=onEvent;return new Promise(resolve=>{finish=()=>{onEvent({type:'done',metrics:{audio_s:2,processing_s:1,rtf:0.5}});resolve();};});};
const running=element('run').onclick();
emit({type:'final',id:0,text:'First'});
emit({type:'translation',id:0,revision:'live',translation:{text:'Translated first'}});
assert.equal(element('translation').value,'Translated first');
assert.equal(element('run').disabled,true);
emit({type:'final',id:1,text:'Second'});
emit({type:'translation',id:1,revision:'live',translation:{text:'Translated second'}});
assert.equal(element('translation').value,'Translated first\nTranslated second');
emit({type:'translation_error',text:'Offline'});
assert.equal(element('translation').value,'Translated first\nTranslated second');
assert.equal(element('error').textContent,'Offline');
emit({type:'speakers',segments:[{id:0,start:0,end:2,speaker:1,text:'Refined'}]});
emit({type:'translation',id:2,revision:'live',translation:{text:'Outdated'}});
assert.equal(element('translation').value,'Translated first\nTranslated second','Keep previous translations until refined results arrive; ignore obsolete live events');
emit({type:'translation',id:0,revision:'refined',translation:{text:'Refined translation'}});
assert.equal(element('translation').value,'Refined translation');
finish();await running;
assert.equal(calls,2,'Live translation must use only the socket');
console.log('PASS: manual HTTP translation, live socket rendering, refinement and errors; no live HTTP translation calls');

await element('speak-translation').onclick();
assert.equal(element('speech-player').src,'blob:speech');
assert.equal(element('speech-download').href,'blob:speech');
assert.equal(element('speech-player').hidden,false);
element('target').onchange();
assert.equal(element('speech-player').hidden,true);
assert.equal(element('speech-download').href,undefined);
element('translation').value='Hello';fail=true;
await element('speak-translation').onclick();
assert.equal(element('error').textContent,'Speech unavailable');
assert.equal(element('translation').value,'Hello');
assert.equal(element('speak-translation').disabled,false);
console.log('PASS: speech generation, download, stale audio cleanup and failure preserving translation');

fail=false;
element('target').value='en';element('listen-translation').checked=true;
const played=[];
element('speech-player').play=async()=>{played.push(element('speech-player').src);};
const speechRun=element('run').onclick();
const beforeSpeech=calls;
emit({type:'translation',id:0,revision:'live',translation:{text:'One'}});
emit({type:'translation',id:1,revision:'live',translation:{text:'Two'}});
emit({type:'translation',id:1,revision:'live',translation:{text:'Two'}});
await tick();
assert.equal(played.length,1);
assert.equal(calls,beforeSpeech+1,'Only one TTS request until first playback ends');
element('speech-player').onended();await tick();
assert.equal(played.length,2);
assert.equal(calls,beforeSpeech+2,'Next segment plays sequentially; duplicate ignored');
emit({type:'speakers',segments:[{id:0,start:0,end:1,speaker:1,text:'Refined'}]});
emit({type:'translation',id:0,revision:'refined',translation:{text:'Refined'}});
assert.equal(calls,beforeSpeech+2,'Refinement does not repeat spoken translation');
element('listen-translation').checked=false;element('listen-translation').onchange();await tick();
assert.equal(element('speech-player').hidden,true,'Unchecking stops current playback');
assert.equal(element('speech-player').onended,null);
finish();await speechRun;
console.log('PASS: automatic translated audio queue, duplicates, refinement and cancellation');
