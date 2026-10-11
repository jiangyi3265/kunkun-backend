const fs=require('node:fs'),path=require('node:path');
const file=path.resolve(__dirname,'../.runtime/dev-endpoints.json');const saved=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{};
module.exports={api:process.env.BOOST_API_URL||saved.api||'http://127.0.0.1:8080',mini:process.env.MINIAPP_URL||saved.mini||'http://127.0.0.1:5210',admin:process.env.ADMIN_URL||saved.admin||'http://127.0.0.1:5175'};
