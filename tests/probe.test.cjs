const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const code=fs.readFileSync('app/src/main/assets/pocket_transpose_probe.js','utf8');
function setup(){
 let media=[], created=0;const intervals=[],logs=[];
 class Node { constructor(){this.gain={value:1};this.connections=0;} connect(){this.connections++;} disconnect(){this.connections=0;} }
 class Context {constructor(){this.state='suspended';this.destination={};} resume(){this.state='running';return Promise.resolve();}createGain(){return new Node();}createMediaElementSource(m){if(m.fail)throw new Error('Blocked https://example.test/private?token=secret');if(m.wrapped)throw Error('already wrapped');m.wrapped=true;created++;return new Node();}}
 const win={addEventListener(){},AudioContext:Context};win.top=win;
 const context={window:win,document:{documentElement:{},querySelectorAll:()=>media,addEventListener(){}},console:{log:(...args)=>logs.push(args)},history:{pushState(){return 42;},replaceState(){}},MutationObserver:class{observe(){}},setInterval:f=>intervals.push(f),setTimeout:f=>f()};
 vm.runInNewContext(code,context);
 return {api:win.PocketTranspose,scan:()=>intervals[0](),setMedia:m=>{media=m;},created:()=>created,rerun:()=>vm.runInNewContext(code,context),logs};
}
const media=()=>({isConnected:true,tagName:'VIDEO',playbackRate:1});
test('settings before media are applied, clamped and finite',()=>{const h=setup();h.api.setGain(8);h.api.setPlaybackRate(.5);const m=media();h.setMedia([m]);h.scan();assert.equal(m.playbackRate,.5);assert.equal(h.api.getStatus().gain,2);assert.throws(()=>h.api.setGain(NaN));});
test('remove and reattach reuses source without muting it',()=>{const h=setup(),m=media();h.setMedia([m]);h.scan();m.isConnected=false;h.setMedia([]);h.scan();assert.equal(h.api.getStatus().mediaCount,0);m.isConnected=true;h.setMedia([m]);h.scan();assert.equal(h.created(),1);assert.equal(h.api.getStatus().connected,true);});
test('replacement and reinjection preserve settings with one source per element',()=>{const h=setup(),a=media(),b=media();h.setMedia([a]);h.scan();h.api.setPlaybackRate(1.5);a.isConnected=false;h.setMedia([b]);h.scan();h.rerun();assert.equal(h.created(),2);assert.equal(b.playbackRate,1.5);assert.equal(h.api.getStatus().mediaCount,1);assert.equal(h.api.getStatus().independentPitchSupported,false);});
test('multiple media stay connected and status does not disclose URLs',()=>{const h=setup();h.setMedia([media(),media()]);h.scan();assert.equal(h.api.getStatus().mediaCount,2);assert.equal('href' in h.api.getStatus(),false);h.api.setPreservesPitch(false);assert.equal(h.api.getStatus().preservesPitch,false);});

test('failed attachment is redacted and reinjection can retry',()=>{const h=setup(),m=media();m.fail=true;h.setMedia([m]);h.scan();const count=h.logs.length;h.scan();assert.equal(h.logs.length,count);assert.equal(JSON.stringify(h.api.getStatus()).includes('secret'),false);m.fail=false;h.rerun();assert.equal(h.api.getStatus().connected,true);});
