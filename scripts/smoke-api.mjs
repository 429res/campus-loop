// Intentionally creates one synthetic demonstration item in this project's development DB.
// Credentials are read locally and never printed. Do not run against nonlocal services.
import assert from 'node:assert/strict'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { localEnv, root } from './env.mjs'
const env=localEnv()
const base=`http://127.0.0.1:${env.SERVER_PORT || 8088}`
if(env.DB_NAME!=='campus_loop_dev' && !/^campus_loop_.*_test$/.test(env.DB_NAME)) throw new Error('Smoke test restricted to named Campus Loop development/test database')
const checks=[]
async function request(path,{method='GET',body,token,status=200}={}) {
  const response=await fetch(base+path,{method,headers:{...(body?{'Content-Type':'application/json'}:{}),...(token?{Authorization:`Bearer ${token}`}:{})},body:body?JSON.stringify(body):undefined})
  assert.equal(response.status,status,`${method} ${path}: HTTP status`)
  const payload=await response.json();assert.equal(payload.code,status,`${path}: envelope`)
  return payload.data
}
const check=(name)=>checks.push(name)
try {
  await request('/api/health');check('health')
  await request('/api/auth/me',{status:401});check('anonymous denied')
  const student=await request('/api/auth/login',{method:'POST',body:{username:env.CAMPUS_USER_USERNAME,password:env.CAMPUS_USER_PASSWORD}})
  const admin=await request('/api/auth/login',{method:'POST',body:{username:env.CAMPUS_ADMIN_USERNAME,password:env.CAMPUS_ADMIN_PASSWORD}})
  assert.equal(student.user.role,'USER');assert.equal(admin.user.role,'ADMIN');check('student and admin login')
  await request('/api/admin/items',{token:student.token,status:403});check('student admin access denied')
  await request('/api/items',{method:'POST',token:student.token,body:{title:''},status:400});check('publish validation')
  const categories=await request('/api/categories');assert.ok(categories.length>=3)
  const before=await request('/api/matches');assert.ok(before.some(x=>x.length===2));assert.ok(before.some(x=>x.length===3));check('fictional 2 and 3 cycles')
  const ids=new Set(before.map(x=>x.id));assert.equal(ids.size,before.length)
  for(const match of before){assert.equal(new Set(match.participants.map(x=>x.userId)).size,match.length);assert.equal(match.flows.length,match.length);assert.ok(match.flows.every(x=>x.reason))}
  check('unique users cycles and explained flow')
  const form=new FormData();form.append('file',new Blob([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=','base64')],{type:'image/png'}),'synthetic.png')
  const uploadResponse=await fetch(base+'/api/uploads',{method:'POST',headers:{Authorization:`Bearer ${student.token}`},body:form})
  assert.equal(uploadResponse.status,200);const uploaded=(await uploadResponse.json()).data.url
  assert.equal((await fetch(base+uploaded)).status,200);check('authenticated local image upload and readback')
  const title=`联调示例 · 校园循环 ${new Date().toISOString().slice(0,19)}`
  const item=await request('/api/items',{method:'POST',token:student.token,body:{title,description:'这是自动验收创建的虚构物品，用于验证发布、刷新和后台读取，不是真实商品。',categoryId:categories[0].id,conditionLevel:4,tags:['演示'],wantedCategoryId:categories[1].id,wantedTags:['校园'],imageUrl:uploaded}})
  assert.ok(item.id);assert.equal(item.ownerId,student.user.id);check('publish persisted item')
  const detail=await request(`/api/items/${item.id}`);assert.equal(detail.title,title);assert.equal(detail.imageUrl,uploaded);check('fresh detail readback')
  const list=await request('/api/items?keyword='+encodeURIComponent(title));assert.ok(list.records.some(x=>x.id===item.id));check('public search list readback')
  const adminList=await request('/api/admin/items?keyword='+encodeURIComponent(title),{token:admin.token});assert.ok(adminList.records.some(x=>x.id===item.id));check('same record visible to administrator')
  await request('/api/exchanges',{method:'POST',token:student.token,body:{},status:501});check('pending operation honest 501')
  await request('/api/auth/logout',{method:'POST',token:student.token});await request('/api/auth/me',{token:student.token,status:401});check('logout revokes server session')
  await request('/api/auth/logout',{method:'POST',token:admin.token})
  mkdirSync(resolve(root,'.local'),{recursive:true})
  writeFileSync(resolve(root,'.local/api-smoke.json'),JSON.stringify({time:new Date().toISOString(),checks,itemId:item.id,title,retained:'Synthetic demonstration record intentionally retained for refresh/admin inspection.'},null,2))
  console.log(JSON.stringify({passed:checks.length,itemId:item.id,checks},null,2))
} catch(error){console.error(error.message);process.exitCode=1}
