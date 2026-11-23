const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const flows=require('../node-red/flows.json');
const source=flows.find(n=>n.name==='Check and clean').func;
function clean(payload){
  const stats={};
  return vm.runInNewContext('(function(){'+source+'})()',{
    msg:{source:{siteId:'virtual',sensorId:'V01',protocol:'mqtt'},payload},Buffer,
    env:{get:()=>undefined},flow:{get:k=>stats[k],set:(k,v)=>stats[k]=v}
  });
}
test('gateway preserves identified delayed sensor time and optional readings',()=>{
  const ts='2026-01-01T00:00:00.000Z';
  const [accepted,rejected]=clean({v:1,msgId:'boot-1',seq:1,ts,door:true});
  assert.equal(rejected,null);assert.equal(accepted.payload.ts,ts);
  assert.equal(accepted.payload.msgId,'boot-1');assert.equal(accepted.payload.seq,1);
  assert.equal(accepted.payload.temperature,undefined);assert.equal(accepted.payload.doorOpen,true);
});
test('gateway labels legacy identity and missing clock fallback',()=>{
  const [accepted]=clean({temperature:2.5});
  assert.equal(accepted.payload.tsSource,'gateway');
  assert.equal(accepted.payload.identitySource,'legacy_timestamp');
});
test('gateway rejects bad version, future clock, physical range and sequence',()=>{
  for(const payload of [{v:99,temperature:2},{ts:Date.now()+3600000,temperature:2},
    {temperature:90},{temperature:2,seq:-1},{temperature:2,msgId:'bad id'}]){
    const [accepted,rejected]=clean(payload);assert.equal(accepted,null);assert(rejected.reason);
  }
});
