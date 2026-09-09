// This journal stores requests awaiting a server answer, never exchange records or authority.
export const EXCHANGE_STATUSES = ['', 'AWAITING_CONFIRMATION', 'READY', 'COMPLETED', 'CANCELLED', 'EXPIRED', 'DISPUTED']
export const STATUS_LABELS = ['全部状态', '等待确认', '准备交接', '交换完成', '已取消', '已到期', '争议处理中']
export const ACTION_LABELS = { CONFIRM:'确认参加', CANCEL:'取消交换', HANDED_OFF:'我已交出', RECEIVED:'我已收到', DISPUTE:'登记争议' }
const clone = value => JSON.parse(JSON.stringify(value))
const integer = (value, minimum = 0) => Number.isSafeInteger(value) && value >= minimum
const journalChanged = () => Object.assign(new Error('保留的请求已在其他页面更新，请重新核对当前记录。'),{code:'JOURNAL_CHANGED'})
export function creationSnapshot(match) {
  const direct = match?.ruleVersion === 'direct-v1'
  if ((!direct && match?.ruleVersion !== 'independent-v2') || (direct && match?.participants?.length !== 2) || ![2,3].includes(match?.participants?.length) || match?.flows?.length !== match.participants.length)
    throw new Error('推荐缺少正式创建依据，请重新读取推荐。')
  const people = new Map(match.participants.map(p => [p.userId,p]))
  const flows = new Map(match.flows.map(f => [f.fromUserId,f]))
  if (people.size !== match.participants.length || flows.size !== people.size) throw new Error('推荐流向不完整，请重新读取。')
  const start = match.flows.reduce((first,f) => f.itemId < first.itemId ? f : first)
  const result = [], seen = new Set()
  let current = start
  for(let index = 0; index < people.size; index++) {
    const person = people.get(current?.fromUserId)
    if (!person || seen.has(person.userId) || person.itemId !== current.itemId || !integer(current.itemId,1) || !integer(person.itemVersion)
      || person.itemVersion > 2147483647 || (direct ? current.demandId!==0 || current.demandVersion!==0 : !integer(current.demandId,1)) || !integer(current.demandVersion) || current.demandVersion > 2147483647)
      throw new Error('推荐缺少精确物品或需求版本，请刷新，不能使用默认版本发起。')
    seen.add(person.userId)
    result.push({itemId:current.itemId,itemVersion:person.itemVersion,demandId:current.demandId,demandVersion:current.demandVersion})
    current = flows.get(current.toUserId)
  }
  if (current !== start || new Set(result.map(f => f.itemId)).size !== result.length) throw new Error('推荐不是完整的双方或三方流向。')
  return {ruleVersion:match.ruleVersion,flows:result}
}
export function newExchangeKey() {
  // No platform crypto dependency; keys are identity scoped, not secrets or access tokens.
  return `exchange_${Date.now().toString(36)}_${Math.random().toString(36).slice(2,14)}_${Math.random().toString(36).slice(2,14)}`
}
export function createExchangeJournal(storage,userId) {
  if (!integer(userId,1)) throw new Error('请重新登录以确认当前账号。')
  const key = `campus-loop-exchange-requests-v1:${userId}`
  const read = () => {
    const value = storage.getStorageSync(key)
    return value?.userId === userId && value?.schema === 1 ? clone(value) : {schema:1,userId,creation:null,actions:{}}
  }
  const save = value => { storage.setStorageSync(key,clone(value)); return value }
  const requireCreation = (data,expectedKey) => {
    if (!expectedKey || data.creation?.body.idempotencyKey !== expectedKey) throw journalChanged()
    return data.creation
  }
  const requireAction = (data,id,expectedRevision) => {
    if ((data.actions[id]?.journalRevision ?? null) !== (expectedRevision ?? null)) throw journalChanged()
  }
  return {
    creation: () => read().creation,
    requireCreation: expectedKey => requireCreation(read(),expectedKey),
    prepare(match) {
      const snapshot = creationSnapshot(match), data = read(), previous = data.creation
      if (previous && JSON.stringify({ruleVersion:previous.body.ruleVersion,flows:previous.body.flows}) === JSON.stringify(snapshot)) return previous
      if (previous && !['confirmed','rejected'].includes(previous.phase)) throw new Error('还有一次发起的结果需要核对，请先处理已保留的请求。')
      data.creation = {body:{...snapshot,idempotencyKey:newExchangeKey()},phase:'prepared',preview:clone(match),savedAt:new Date().toISOString()}
      return save(data).creation
    },
    updateCreation(expectedKey,update) { const data=read();Object.assign(requireCreation(data,expectedKey),update);return save(data).creation },
    clearCreation(expectedKey) { const data=read();requireCreation(data,expectedKey);if(!['confirmed','rejected','prepared'].includes(data.creation.phase)) throw new Error('结果尚不确定，请先重试原请求。');data.creation=null;save(data) },
    action: id => read().actions[id] || null,
    saveAction(id,value) {const data=read();requireAction(data,id,value.journalRevision);data.actions[id]={...clone(value),journalRevision:newExchangeKey()};save(data);return data.actions[id]},
    clearAction(id,expectedRevision) {const data=read();requireAction(data,id,expectedRevision);delete data.actions[id];save(data)},
  }
}
export function actionRequest(action,version,{reason='',acknowledged=false}={}) {
  if (!integer(version) || version > 2147483647) throw new Error('请先读取交换的当前版本。')
  const note = reason.trim()
  if (note.length > 1000) throw new Error('说明最多 1000 字。')
  if (action === 'CONFIRM') return {path:'confirm',body:{version}}
  if (action === 'CANCEL' || action === 'DISPUTE') {
    if (!note) throw new Error('请填写原因。')
    return {path:action === 'CANCEL'?'cancel':'dispute',body:{version,reason:note}}
  }
  if (action === 'HANDED_OFF' || action === 'RECEIVED') {
    if (!acknowledged) throw new Error('请明确勾选实物交接声明。')
    return {path:'handoff',body:{version,kind:action,acknowledged:true,note}}
  }
  throw new Error('当前操作不可用，请重新读取交换。')
}
export function expiryText(exchange,now=Date.now()) {
  if (!['AWAITING_CONFIRMATION','READY'].includes(exchange?.status)) return ''
  if (exchange.participants?.some(p => p.handedOffAt || p.receivedAt)) return '交接已开始，请继续核对实物；如有异常可登记争议。'
  const remaining = Date.parse(exchange.expiresAt)-now
  if (!Number.isFinite(remaining)) return '请以服务器返回的操作资格为准。'
  if (remaining <= 0) return '本机显示已到截止，请刷新服务器状态。'
  const minutes = Math.ceil(remaining/60000)
  return `距原确认截止约 ${Math.floor(minutes/60)} 小时 ${minutes%60} 分钟（本机估算）`
}
