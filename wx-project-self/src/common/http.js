// Adapted from ProjectStudy-self's uni.request wrapper: timeouts, envelope validation,
// upload parsing, and stale-session protection are retained; auth/storage are Campus Loop's.
let defaultBase = ''
// #ifndef H5
defaultBase = import.meta.env.VITE_MINI_API_BASE_URL || 'http://127.0.0.1:8088'
// #endif
const baseUrl = (import.meta.env.VITE_API_BASE_URL || defaultBase).replace(/\/$/, '')
export const TOKEN_KEY = 'campus-loop-token'
export const USER_KEY = 'campus-loop-user'
export const clearSession = () => { uni.removeStorageSync(TOKEN_KEY); uni.removeStorageSync(USER_KEY) }
const showError = message => uni.showToast({ title: message || '请求失败，请重试', icon: 'none', duration: 2500 })
const unpack = (response, token) => {
  let result = response.data
  if (typeof result === 'string') {
    try { result = JSON.parse(result) } catch { result = null }
  }
  if (response.statusCode >= 200 && response.statusCode < 300 && result?.code === 200) return result.data
  const expired = response.statusCode === 401
  if (expired && token === uni.getStorageSync(TOKEN_KEY)) clearSession()
  const error = new Error(expired ? '登录已过期，请重新登录' : result?.msg || `请求失败（${response.statusCode}）`)
  error.status = response.statusCode
  error.reauth = expired
  throw error
}
const abortedError = () => Object.assign(new Error('请求已取消'), { code: 'ABORTED' })
const request = (method, url, data = {}, options = {}) => {
  const token = uni.getStorageSync(TOKEN_KEY)
  let task
  const promise = new Promise((resolve, reject) => {
    task = uni.request({
      url: `${baseUrl}${url}`, method, data, timeout: options.timeout || 15000,
      header: { 'content-type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      success(response) { try { resolve(unpack(response, token)) } catch (error) { if (!options.silent) showError(error.message); reject(error) } },
      fail(result) {
        const aborted = /abort/i.test(result?.errMsg || '')
        const error = aborted ? abortedError() : new Error('无法连接服务器，请检查后端服务')
        if (!aborted && !options.silent) showError(error.message)
        reject(error)
      },
    })
  })
  promise.abort = () => task?.abort()
  return promise
}
const upload = (filePath, options = {}) => {
  const token = uni.getStorageSync(TOKEN_KEY)
  let task
  const promise = new Promise((resolve, reject) => {
    task = uni.uploadFile({
      url: `${baseUrl}/api/uploads`, filePath, name: 'file', timeout: 30000,
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success(response) { try { resolve(unpack(response, token)) } catch (error) { if (!options.silent) showError(error.message); reject(error) } },
      fail(result) {
        const aborted = /abort/i.test(result?.errMsg || '')
        const error = aborted ? abortedError() : new Error('图片上传失败，请重试')
        if (!aborted && !options.silent) showError(error.message)
        reject(error)
      },
    })
  })
  promise.abort = () => task?.abort()
  return promise
}
export const isAbortError = error => error?.code === 'ABORTED'
export const imageUrl = (path, title = '') => {
  if (path?.startsWith('/uploads/')) return baseUrl + path
  if (path?.startsWith('/demo/')) return path.replace('/demo/', '/static/demo/')
  if (path) return path
  const kind = /相机|拍|摄影/.test(title) ? 'camera' : /灯/.test(title) ? 'lamp' : /车|骑/.test(title) ? 'bike' : 'book'
  return `/static/demo/${kind}.svg`
}
export default { get: (url, data, options) => request('GET', url, data, options), post: (url, data, options) => request('POST', url, data, options), upload, baseUrl }
