// Creates synthetic records only in an explicitly named, disposable local test database.
// Credentials are read locally and never printed; sessions are revoked even when a check fails.
import assert from 'node:assert/strict'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { localEnv, root } from './env.mjs'
const env=localEnv(false)
const base=`http://127.0.0.1:${env.SERVER_PORT || 8088}`
if(!['127.0.0.1','localhost','::1'].includes(env.DB_HOST) || !/^campus_loop_[a-z0-9_]+_test$/.test(env.DB_NAME || ''))
  throw new Error('Smoke test requires an explicitly named local campus_loop_*_test database')
const checks=[]
const sessions=[]
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
  sessions.push(student.token)
  const admin=await request('/api/auth/login',{method:'POST',body:{username:env.CAMPUS_ADMIN_USERNAME,password:env.CAMPUS_ADMIN_PASSWORD}})
  sessions.push(admin.token)
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
  assert.equal(item.status,'PENDING_REVIEW')
  await request(`/api/items/${item.id}`,{status:404})
  const mine=await request(`/api/items/mine/${item.id}`,{token:student.token})
  assert.equal(mine.title,title);check('pending item private owner readback')
  const demand=await request('/api/demands',{method:'POST',token:student.token,body:{categoryId:categories[1].id,description:'隔离验收需求',preferredTags:[],offeredItemIds:[]}})
  assert.equal(demand.offeredItems.length,0);check('independent demand without offer')
  const reviewed=await request(`/api/admin/items/${item.id}/review`,{method:'POST',token:admin.token,body:{decision:'APPROVE',reason:'隔离验收虚构物品，内容已核对。',version:item.version}})
  assert.equal(reviewed.status,'AVAILABLE');assert.equal(reviewed.version,item.version+1);check('admin approval persists before public listing')
  const detail=await request(`/api/items/${item.id}`);assert.equal(detail.title,title);assert.equal(detail.imageUrl,uploaded);check('fresh detail readback')
  const list=await request('/api/items?keyword='+encodeURIComponent(title));assert.ok(list.records.some(x=>x.id===item.id));check('public search list readback')
  const adminList=await request('/api/admin/items?keyword='+encodeURIComponent(title),{token:admin.token});assert.ok(adminList.records.some(x=>x.id===item.id));check('same record visible to administrator')
  await request(`/api/demands/${demand.id}`,{method:'PATCH',token:student.token,body:{version:demand.version,offeredItemIds:[item.id]}})
  const recommendations=await request('/api/matches/independent',{token:student.token})
  assert.ok(Array.isArray(recommendations.recommendations));check('independent recommendation read after approved offer association')
  await request(`/api/items/${item.id}/favorite`,{method:'PUT',token:student.token})
  await request(`/api/items/${item.id}/favorite`,{method:'PUT',token:student.token})
  const second=await request('/api/auth/login',{method:'POST',body:{username:env.CAMPUS_USER_USERNAME,password:env.CAMPUS_USER_PASSWORD}})
  sessions.push(second.token)
  const favorites=await request('/api/favorites',{token:second.token})
  assert.equal(favorites.records.filter(row=>row.itemId===item.id).length,1);check('idempotent favorite persists across sessions')
  await request(`/api/items/${item.id}/favorite`,{method:'DELETE',token:second.token})
  const savedDemand=await request(`/api/demands/${demand.id}`,{token:second.token})
  assert.equal(savedDemand.offeredItems[0].itemId,item.id);check('demand association persists across sessions')
  await request('/api/exchanges',{method:'POST',token:student.token,body:{},status:400});check('formal exchange rejects incomplete creation command')
  await request('/api/auth/logout',{method:'POST',token:student.token});await request('/api/auth/me',{token:student.token,status:401});check('logout revokes server session')
  await request('/api/auth/logout',{method:'POST',token:admin.token})
  mkdirSync(resolve(root,'.local'),{recursive:true})
  writeFileSync(resolve(root,env.CAMPUS_SMOKE_REPORT || '.local/api-smoke.json'),JSON.stringify({time:new Date().toISOString(),checks,itemId:item.id,title,retained:'Synthetic demonstration record intentionally retained for refresh/admin inspection.'},null,2))
  console.log(JSON.stringify({passed:checks.length,itemId:item.id,checks},null,2))
} catch(error){console.error(error.message);process.exitCode=1}
finally {
  for(const token of sessions) {
    try { await fetch(base+'/api/auth/logout',{method:'POST',headers:{Authorization:`Bearer ${token}`}}) } catch {}
  }
}
