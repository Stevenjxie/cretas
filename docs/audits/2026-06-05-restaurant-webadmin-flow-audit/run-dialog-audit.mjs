import { createRequire } from 'module';
import fs from 'fs/promises';
import path from 'path';
const require = createRequire('C:/Users/Steve/my-prototype-logistics/web-admin/package.json');
const { chromium } = require('playwright');
const BASE='http://139.196.165.140:8086'; const API=`${BASE}/api/mobile`; const OUT='docs/audits/2026-06-05-restaurant-webadmin-flow-audit/dialog-evidence';
async function auth(context,page){const res=await fetch(`${API}/auth/unified-login`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'qhj_prod',password:'123456'})}); const j=await res.json(); const d=j.data; const token=d.accessToken||d.token; await context.addCookies([{name:'cretas_access_token',value:token,domain:new URL(BASE).hostname,path:'/',httpOnly:true,secure:false,sameSite:'Lax'}]); await page.goto(`${BASE}/login`,{waitUntil:'domcontentloaded'}); await page.evaluate(({d,token})=>{localStorage.setItem('cretas_access_token',token); localStorage.setItem('cretas_user',JSON.stringify({id:d.userId,username:d.username,userType:'factory',factoryUser:{role:d.role,factoryId:d.factoryId,factoryType:d.factoryType,permissions:d.permissions||[]}}));},{d,token});}
const cases=[
 {id:'supplier-delivery-upload', path:'/restaurant/supplier-delivery', button:/上传送货单/},
 {id:'requisition-create', path:'/restaurant/requisitions', button:/新建领料单/},
 {id:'stocktaking-create', path:'/restaurant/stocktaking', button:/新建盘点/},
 {id:'recipe-batch-import', path:'/restaurant/recipes', button:/批量导入/},
 {id:'smartbi-upload', path:'/smart-bi/upload', button:null},
];
await fs.mkdir(OUT,{recursive:true}); const browser=await chromium.launch({headless:false}); const context=await browser.newContext({viewport:{width:1440,height:950},recordVideo:{dir:OUT,size:{width:1440,height:950}}}); const page=await context.newPage(); await auth(context,page); const results=[];
for (const c of cases){await page.goto(`${BASE}${c.path}`,{waitUntil:'domcontentloaded',timeout:45000}); await page.waitForLoadState('networkidle',{timeout:12000}).catch(()=>{}); await page.waitForTimeout(1000); if(c.button){const btn=page.getByRole('button',{name:c.button}).first(); const visible=await btn.isVisible().catch(()=>false); if(visible){await btn.click(); await page.waitForTimeout(1200);} else {results.push({...c, opened:false, reason:'button not visible'}); continue;}} const shot=path.join(OUT,`${c.id}.png`); await page.screenshot({path:shot,fullPage:true}); const text=(await page.locator('body').innerText().catch(()=>'')).replace(/\s+/g,' ').trim(); const buttons=await page.locator('button:visible').evaluateAll(els=>els.map(e=>e.textContent?.trim()).filter(Boolean).slice(-20)).catch(()=>[]); results.push({...c, opened:true, screenshot:shot, text:text.slice(0,1200), buttons});}
await context.close(); await browser.close(); await fs.writeFile(path.join(OUT,'result.json'),JSON.stringify(results,null,2),'utf8'); console.log(JSON.stringify(results.map(r=>({id:r.id,opened:r.opened,buttons:r.buttons})),null,2));
