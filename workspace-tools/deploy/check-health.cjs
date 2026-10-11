// One monitoring sample. A scheduler and a real alert receiver must be configured by the operator.
const health=process.env.BOOST_HEALTH_URL,alert=process.env.BOOST_ALERT_URL;
(async()=>{
 if(!health)throw Error('BOOST_HEALTH_URL required');
 const target=new URL(health);if(target.username||target.password||target.search||target.hash)throw Error('Health URL must not contain credentials or query data');
 let healthy=false;try{const r=await fetch(target,{signal:AbortSignal.timeout(5000)});healthy=r.ok&&(await r.json()).status==='UP';}catch{}
 if(healthy){console.log(JSON.stringify({status:'UP',alertSent:false}));return;}
 if(!alert){console.log(JSON.stringify({status:'DOWN',alertSent:false,reason:'Alert receiver is not configured'}));process.exitCode=2;return;}
 const receiver=new URL(alert);if(receiver.username||receiver.password)throw Error('Alert authentication belongs in BOOST_ALERT_TOKEN');
 const payload={type:'boost-readiness-down',checkedAt:new Date().toISOString(),origin:target.origin};
 const r=await fetch(receiver,{method:'POST',headers:{'Content-Type':'application/json',...(process.env.BOOST_ALERT_TOKEN?{Authorization:'Bearer '+process.env.BOOST_ALERT_TOKEN}:{})},body:JSON.stringify(payload),signal:AbortSignal.timeout(5000)});
 if(!r.ok)throw Error('Alert receiver rejected event');
 console.log(JSON.stringify({status:'DOWN',alertSent:true}));process.exitCode=1;
})().catch(e=>{console.error(e.message);process.exitCode=2;});
