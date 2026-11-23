// Isolated real MQTT/MongoDB/WildFly tests. Never points to the normal Compose project.
const fs = require('node:fs'), path = require('node:path'), https = require('node:https');
const crypto = require('node:crypto'), cp = require('node:child_process'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../..');
const configPath = process.env.PHASE_A_CONFIG;
assert(configPath, 'PHASE_A_CONFIG must point to temporary test configuration');
const config = JSON.parse(fs.readFileSync(configPath, 'utf8').replace(/^\uFEFF/, ''));
assert(config.certDir.includes('coldtrace-phase-a-'), 'Use certificates generated solely for this isolated fixture');
const env = { ...process.env, HOSTING_CERTS_DIR: config.certDir, HOSTING_TLS_PASSWORD: config.tlsPassword,
  HOSTING_HTTPS_PORT: String(config.port), PHASE_A_PASSWORD: config.password };
const project=config.project || 'coldtrace-phase-a-check';
assert(/^coldtrace-phase-a-check(?:-[a-z0-9]+)?$/.test(project));
const composeArgs = ['compose', '-p', project, '-f', path.join(__dirname,'hosting.compose.yml'), '-f',path.join(__dirname,'phase-a.compose.yml')];
const output = path.join(root,'middleware/api/target/phase-a-evidence'); fs.mkdirSync(output,{recursive:true});
const evidence = [];
const run = (...args) => cp.execFileSync('docker',[...composeArgs,...args], {cwd:root,env,encoding:'utf8',maxBuffer:8*1024*1024});
const delay = ms => new Promise(r=>setTimeout(r,ms));
async function wait(fn, description, timeout=90000) {
 const end=Date.now()+timeout; let last;
 while(Date.now()<end){try{const value=await fn();if(value)return value;}catch(e){last=e.message;}await delay(700);}
 throw new Error('Timeout: '+description+(last?' ('+last+')':''));
}
async function scenario(number, description, fn) {
 const start=Date.now();
 try{const detail=await fn(); evidence.push({number,description,status:'PASS',detail:detail||null,ms:Date.now()-start});console.log('PASS '+number+': '+description);}
 catch(e){evidence.push({number,description,status:'FAIL',error:e.message,ms:Date.now()-start});throw e;}
 finally{fs.writeFileSync(path.join(output,'scenarios.json'),JSON.stringify(evidence,null,2));}
}
function request(route, token, method='GET', body) {
 const host=route.startsWith('/auth')?'iam.coldtrace.test':'api.coldtrace.test';
 const payload=body===undefined?undefined:JSON.stringify(body);
 return new Promise((resolve,reject)=>{
  const req=https.request({hostname:'127.0.0.1',port:config.port,servername:host,path:route,method,
   ca:fs.readFileSync(path.join(config.certDir,'ca.crt')),headers:{Host:host,...token?{Authorization:'Bearer '+token}:{},
    ...payload?{'Content-Type':'application/json','Content-Length':Buffer.byteLength(payload)}:{}}},res=>{
    let content='';res.on('data',b=>content+=b);res.on('end',()=>{let data;try{data=JSON.parse(content);}catch{data=content;}resolve({status:res.statusCode,data});});
  });req.setTimeout(10000,()=>req.destroy(new Error('HTTP timeout')));req.on('error',reject);req.end(payload);
 });
}
let admin,operator,quality;
const api=async(route,method='GET',body,token=admin)=>{const r=await request(route,token,method,body);assert(r.status>=200&&r.status<300,route+' -> '+r.status+' '+JSON.stringify(r.data));return r.data;};
const mongo=js=>JSON.parse(run('exec','-T','mongo','mongosh','mongodb://mongo/coldtrace','--quiet','--eval','print(JSON.stringify('+js+'))').trim().split('\n').at(-1));
function publish(id, body, sensor='S01',site='site01'){
 const payload=typeof body==='string'?body:JSON.stringify({v:1,msgId:id,ts:new Date().toISOString(),temperature:2.5,...body});
 run('exec','-T','cloud-broker','mosquitto_pub','-h','localhost','-p','8883','--cafile','/mosquitto/certs/ca.crt',
  '-u',site==='site04'?'site04':'site01','-P','integration-only-pass','-q','1','-t',`coldtrace/${site}/${sensor}/telemetry`,'-m',payload);
}
const count=id=>mongo('db.measurements.countDocuments({msgId:'+JSON.stringify(id)+'})');
async function login(){
 for(const name of ['admin','operateur','qualite']){
  const r=await request('/auth/login',null,'POST',{username:name,password:config.password});assert.equal(r.status,200,'login '+name);
  if(name==='admin')admin=r.data.accessToken;else if(name==='operateur')operator=r.data.accessToken;else quality=r.data.accessToken;
 }
}

async function browserCompatibility(){
 const {chromium}=require('../node_modules/playwright');
 const certificate=new crypto.X509Certificate(fs.readFileSync(path.join(config.certDir,'web.crt')));
 const spki=crypto.createHash('sha256').update(certificate.publicKey.export({format:'der',type:'spki'})).digest('base64');
 const browser=await chromium.launch({headless:true,...process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{},
  args:['--no-proxy-server','--host-resolver-rules=MAP *.coldtrace.test 127.0.0.1','--ignore-certificate-errors-spki-list='+spki]});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:900}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  page.on('requestfailed',r=>console.log('Browser request failed:',r.url(),r.failure()?.errorText));
  page.on('console',m=>{if(m.type()==='error')console.log('Browser:',m.text());});
  const front='https://www.coldtrace.test:'+config.port;
  await page.goto(front,{waitUntil:'domcontentloaded'});
  await page.locator('#login-user').fill('operateur');await page.locator('#login-password').fill(config.password);await page.locator('#login-submit').click();
  await page.waitForFunction(()=>document.querySelectorAll('.tile').length===10&&document.querySelector('#chip-ws.ok')&&document.querySelector('#chip-api.ok'),{}, {timeout:60000});
  assert.equal(await page.locator('.site').count(),3);
  const tile=page.locator('.tile').filter({has:page.locator('.tile-id',{hasText:/^S04$/})});
  await tile.locator('[data-fault="door_open"]').click();
  const row=page.locator('#open-incidents .incident.m-door').filter({has:page.locator('.who',{hasText:/^S04$/})});
  await row.waitFor({state:'visible',timeout:20000});
  await row.locator('.btn-ack').click();
  await row.locator('textarea').fill('Phase A isolated browser validation');
  await row.getByRole('button',{name:'Confirmer',exact:true}).click();
  await page.waitForFunction(()=>[...document.querySelectorAll('.incident.m-door')].some(r=>r.querySelector('.who').textContent==='S04'&&r.classList.contains('acknowledged')),{}, {timeout:20000});
  await tile.locator('[data-fault="none"]').click();
  await row.waitFor({state:'detached',timeout:25000});
  await page.screenshot({path:path.join(output,'pwa.png'),fullPage:true});
  assert.deepEqual(errors,[]);
  return 'Real Chromium login, 10 sensors/3 sites, live WebSocket, controls, signed acknowledgement and recovery';
 }finally{await browser.close();}
}

(async()=>{
 const compose=JSON.parse(run('config','--format','json'));
 assert.equal(compose.name,project);assert.equal(compose.services.wildfly.image,'coldtrace-phase-a-wildfly');
 await wait(async()=> (await request('/api/health')).status===200,'WildFly deployment',180000);
 await wait(async()=>{try{await login();return true;}catch{return false;}},'IAM login',120000);
 await wait(async()=> (await api('/api/sensors')).every(s=>s.latest),'ten live sensors');

 await scenario(1,'Normal real MQTT ingestion and REST exposure',async()=>{
  publish('phase-normal',{});await wait(()=>count('phase-normal')===1,'normal event persisted');
  const row=mongo('db.measurements.findOne({msgId:"phase-normal"})');assert(row.ingestedAt&&row.ts&&row.ingestionStatus==='complete');
  assert.equal((await api('/api/sensors')).length,10);return {event:'phase-normal',ingestedAt:row.ingestedAt};
 });
 await scenario(13,'Existing authenticated PWA and WebSocket compatibility',browserCompatibility);
 await scenario(12,'IAM: readers cannot mutate business configuration',async()=>{
  for(const token of [operator,quality])for(const [route,method,body]of [
   ['/api/sites','POST',{id:'forbidden',name:'x'}],['/api/sites/site01','PUT',{name:'x'}],['/api/sites/site01','DELETE'],
   ['/api/sensors','POST',{}],['/api/sensors/S01','PUT',{active:false}],['/api/sensors/S01','DELETE'],['/api/thresholds/cold','PUT',{temperatureMax:9}]])assert.equal((await request(route,token,method,body)).status,403,route);
  assert.equal((await request('/api/sites',null,'POST',{})).status,401);
  assert.equal((await request('/api/sites',quality)).status,200);
  assert.equal((await request('/api/ingestion',quality)).status,403);
  return 'All mutations tested with operator/quality (403), anonymous (401), admin used by CRUD scenarios';
 });
 await scenario(3,'Duplicate message identity persists one logical event',async()=>{
  const event={ts:new Date().toISOString(),temperature:2.6};for(let i=0;i<4;i++)publish('phase-duplicate',event);
  await wait(()=>count('phase-duplicate')===1,'duplicate deduplication');await delay(1500);assert.equal(count('phase-duplicate'),1);
 });
 await scenario(2,'MongoDB outage: fsynced pending events survive and recover',async()=>{
  run('stop','mongo');
  try{
   for(let i=0;i<12;i++)publish('phase-outage-'+i,{ts:new Date(Date.now()-120000).toISOString()});
   await wait(async()=> (await api('/api/ingestion')).pending>=12,'observable pending queue');
   const pending=(await api('/api/ingestion')).pending;
   run('restart','wildfly');
   const onDisk=Number(run('exec','-T','wildfly','sh','-c','find /ingestion -maxdepth 1 -name "*.evt" | wc -l').trim());
   assert(onDisk>=12,'durable queue across restart');
   run('start','mongo');
   await wait(async()=>{await login();return (await api('/api/ingestion')).pending===0;},'queue recovery after MongoDB returns',180000);
   for(let i=0;i<12;i++)assert.equal(count('phase-outage-'+i),1);
   assert.equal(mongo('db.measurements.countDocuments({msgId:/^phase-outage-/,liveEligible:true})'),0);
   return {pendingBeforeRestart:pending,onDiskAfterRestart:onDisk,recoveredUnique:12};
  }finally{run('start','mongo');}
 });
 await scenario(6,'Backend restart during normal ingestion recovers subscription',async()=>{
  run('stop','wildfly');publish('phase-backend-restart',{});run('start','wildfly');
  await wait(async()=>{await login();return count('phase-backend-restart')===1;},'backend restart delivery',180000);
  return 'Broker durable subscription + backend disk queue; exactly one record observed';
 });
 await scenario(9,'Dynamic site and sensor registration through admin REST',async()=>{
  assert.equal((await request('/api/sites',admin,'POST',{id:'site04',name:'Virtual second gateway',location:'Test only',defaultProfile:'phase'})).status,404,'unknown referenced profile is rejected');
  await api('/api/thresholds/phase','PUT',{temperatureMin:0,temperatureMax:4,humidityMin:20,humidityMax:90});
  await api('/api/sites','POST',{id:'site04',name:'Virtual second gateway',location:'Test only',defaultProfile:'phase'});
  await api('/api/sensors','POST',{id:'V01',siteId:'site04',name:'Dynamically registered',type:'temperature'});
  assert.equal((await request('/api/sites',admin,'POST',{id:'site04',name:'duplicate'})).status,409);
  const event={ts:Date.now(),msgId:'phase-dynamic',seq:1,temperature:2.5};
  run('exec','-T','site-broker-two','mosquitto_pub','-h','localhost','-q','1','-t','coldtrace/site04/V01/telemetry','-m',JSON.stringify(event));
  await wait(async()=> (await api('/api/sensors/V01')).latest?.temperature===2.5,'second gateway dynamic sensor');
  assert.equal((await api('/api/sensors?siteId=site04')).length,1);
  assert.equal(count('phase-dynamic'),1);
  return 'REST-only registration; input traversed second real broker and independent Node-RED gateway';
 });
 await scenario(4,'Out-of-order history cannot replace live state or close incidents',async()=>{
  const newer=new Date().toISOString();publish('phase-newer',{ts:newer,temperature:5},'V01','site04');
  await wait(async()=> (await api('/api/incidents?status=open&limit=200')).some(i=>i.sensorId==='V01'&&i.metric==='temperature'),'temperature incident');
  publish('phase-older',{ts:new Date(Date.now()-60000).toISOString(),temperature:2},'V01','site04');
  await wait(()=>count('phase-older')===1,'old event stored');
  assert.equal((await api('/api/sensors/V01')).lastMeasurement.temperature,5);
  assert((await api('/api/incidents?status=open&limit=200')).some(i=>i.sensorId==='V01'&&i.metric==='temperature'));
  assert.equal(mongo('db.measurements.findOne({msgId:"phase-older"}).liveEligible'),false);
 });
 await scenario(5,'Replayed history remains historical even when just received',async()=>{
  for(let i=0;i<3;i++)publish('phase-replayed-'+i,{ts:new Date().toISOString(),temperature:2,replayed:true},'V01','site04');
  await wait(()=>count('phase-replayed-2')===1,'replayed history');
  assert((await api('/api/incidents?status=open&limit=200')).some(i=>i.sensorId==='V01'&&i.metric==='temperature'));
  assert.equal(mongo('db.measurements.countDocuments({msgId:/^phase-replayed-/,liveEligible:true})'),0);
 });
 await scenario(10,'Configurable thresholds apply to subsequent live events only',async()=>{
  const old=(await api('/api/incidents?status=open&limit=200')).find(i=>i.sensorId==='V01'&&i.metric==='temperature');
  await api('/api/thresholds/phase','PUT',{temperatureMax:6});
  assert.equal((await api('/api/incidents?status=open&limit=200')).find(i=>i.id===old.id).thresholdMax,4);
  for(let i=0;i<3;i++){publish('phase-recover-'+i,{temperature:2.5},'V01','site04');await delay(100);}
  await wait(async()=>!(await api('/api/incidents?status=open&limit=200')).some(i=>i.id===old.id),'three normal readings close once');
  await api('/api/thresholds/phase','PUT',{temperatureMax:2});publish('phase-threshold',{temperature:2.5},'V01','site04');
  await wait(async()=> (await api('/api/incidents?status=open&limit=200')).some(i=>i.sensorId==='V01'&&i.metric==='temperature'&&i.thresholdMax===2),'new configured threshold');
  assert.equal((await request('/api/thresholds/phase',admin,'PUT',{temperatureMin:10,temperatureMax:2})).status,400);
 });
 await scenario(11,'Freshness reconstructs from event time; disabled sensors stay disabled',async()=>{
  await wait(async()=> (await api('/api/sensors/V01')).status==='offline','offline after event-time deadline',30000);
  const before=await api('/api/sensors/V01');assert(before.latest == null);
  publish('phase-delayed',{ts:new Date(Date.now()-300000).toISOString(),temperature:1},'V01','site04');
  await wait(()=>count('phase-delayed')===1,'historical backfill');
  assert.equal((await api('/api/sensors/V01')).status,'offline');
  await api('/api/sensors/V01','PUT',{active:false});publish('phase-disabled',{temperature:1},'V01','site04');
  await wait(()=>count('phase-disabled')===1,'disabled sensor history');
  assert.equal((await api('/api/sensors/V01')).status,'disabled');
  return {freshSeconds:6,offlineSeconds:12};
 });
 await scenario(8,'Malformed, future, unsupported and unknown events are quarantined',async()=>{
  const before=(await api('/api/ingestion')).quarantined;
  publish('bad-json','not-json');publish('phase-version',{v:99});publish('phase-unknown',{},'UNKNOWN');
  publish('phase-future',{ts:new Date(Date.now()+3600000).toISOString()});publish('phase-range',{temperature:200});
  await wait(async()=> (await api('/api/ingestion')).quarantined>=before+5,'quarantine counter');
  const reasons=(await api('/api/ingestion/quarantine')).map(r=>r.reason);
  assert(reasons.includes('unknown_sensor')&&reasons.includes('future_timestamp')&&reasons.includes('invalid_json'));
  assert.equal(count('phase-unknown'),0);return {retainedQuarantineReasons:reasons};
 });
 await scenario(7,'Gateway restart and cloud outage retain/replay buffered messages',async()=>{
  run('stop','cloud-broker');await delay(2500);
  for(let i=0;i<5;i++)run('exec','-T','site-broker-two','mosquitto_pub','-h','localhost','-q','1','-t','coldtrace/site04/V01/telemetry',
   '-m',JSON.stringify({msgId:'phase-gateway-'+i,ts:Date.now(),temperature:1}));
  await delay(6500);run('restart','node-red-two');await delay(2500);run('start','cloud-broker');
  await wait(()=>mongo('db.measurements.countDocuments({msgId:/^phase-gateway-/})')===5,'gateway disk replay',90000);
  assert.equal(mongo('db.measurements.countDocuments({msgId:/^phase-gateway-/,liveEligible:true})'),0);
  await wait(async()=> (await api('/api/gateways')).some(g=>g.siteId==='site04'&&g.status==='online'),'second gateway reconnected');
  return {replayedUnique:5,secondGateway:'gw-site04'};
 });
 // Serving tests use its real FastAPI endpoint/feature code and a deterministic test predictor,
 // not the user's MLflow registry or model artifacts.
 await scenario(14,'MLOps prediction contract and shared feature regression suite',async()=>{
  const result=run('run','--rm','--no-deps','--build','model-tests');
  fs.writeFileSync(path.join(output,'mlops-tests.txt'),result);assert(/passed/.test(result));return result.trim().split('\n').at(-1);
 });
 const logs=run('logs','--no-color','wildfly','node-red-two');fs.writeFileSync(path.join(output,'stack.log'),logs);
 console.log('All 14 Phase A scenarios passed. Evidence: '+output);
})().catch(error=>{
 try{fs.writeFileSync(path.join(output,'stack-failure.log'),run('logs','--no-color','wildfly','node-red-two','cloud-broker'));}catch{}
 console.error(error.stack);process.exitCode=1;
});
