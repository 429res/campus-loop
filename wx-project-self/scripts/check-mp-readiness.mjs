import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { loadEnv } from 'vite'

const root = new URL('../', import.meta.url)
const output = new URL('dist/build/mp-weixin/', root)

const read = file => readFileSync(new URL(file, output), 'utf8')
const readJson = file => JSON.parse(read(file))
const hasText = (files, pattern) => files.some(file => pattern.test(read(file)))

assert.ok(existsSync(output), '微信构建产物不存在，请先运行 npm run build:mp-weixin')

const project = readJson('project.config.json')
const app = readJson('app.json')
const expectedPages = [
  'pages/home/home',
  'pages/matches/matches',
  'pages/publish/publish',
  'pages/profile/profile',
  'pages/login/login',
  'pages/favorites/favorites',
  'pages/demands/demands',
  'pages/exchanges/exchanges',
  'pages/my-items/my-items',
  'pages/detail/detail',
  'pages/controls/controls',
]

assert.equal(project.compileType, 'miniprogram')
assert.equal(project.setting?.urlCheck, true, '必须保留微信合法域名校验')
assert.deepEqual(app.pages, expectedPages, '微信页面清单与预期不一致')
assert.deepEqual(
  app.tabBar?.list?.map(item => item.pagePath),
  ['pages/home/home', 'pages/matches/matches', 'pages/publish/publish', 'pages/profile/profile'],
  '微信原生 TabBar 路由与预期不一致',
)

const publishFiles = ['pages/publish/publish.js', 'common/http.js']
assert.ok(hasText(publishFiles, /chooseImage/), '发布产物缺少原生图片选择接线')
assert.ok(hasText(publishFiles, /uploadFile/), '发布产物缺少原生上传接线')
assert.ok(hasText(publishFiles, /\.abort/), '发布产物缺少上传/请求取消接线')
assert.match(read('components/LoopPicker.wxml'), /<picker\b/, 'LoopPicker 未保留微信原生 picker')
assert.ok(hasText(['common/modal.js', 'common/vendor.js'], /showModal/), '微信产物缺少原生 modal 接线')
assert.ok(hasText(['composables/useTheme.js'], /setNavigationBarColor/), '微信产物缺少原生导航栏主题接线')
assert.ok(hasText(['composables/useTheme.js'], /setTabBarStyle/), '微信产物缺少原生 TabBar 主题接线')
assert.doesNotMatch(read('pages/controls/controls.js'), /\bdocument\.|\bwindow\./, '微信页面混入 H5 DOM 调用')
assert.match(read('app.wxss'), /backdrop-filter:none/, '微信材质实色降级未生成')
assert.match(read('app.wxss'), /cl-reduce-motion/, '微信减少动态效果覆盖未生成')

const env = loadEnv('production', fileURLToPath(root), 'VITE_')
const configuredAppId = Boolean(env.VITE_WECHAT_APP_ID?.trim())
const configuredApi = env.VITE_MINI_API_BASE_URL?.trim() || ''
const classifyApi = value => {
  if (!value) return 'unset (build default: loopback)'
  try {
    const url = new URL(value)
    if (['localhost', '127.0.0.1', '::1', '[::1]'].includes(url.hostname)) return 'loopback'
    return url.protocol === 'https:' ? 'https' : 'other'
  } catch {
    return 'other'
  }
}

const appIdKind = project.appid === 'touristappid' ? 'tourist' : 'configured'
console.log('WeChat mini-program artifact checks: PASS')
console.log(`- AppID environment: ${configuredAppId ? 'configured' : 'unset'}`)
console.log(`- Built AppID mode: ${appIdKind}`)
console.log(`- Mini API environment: ${configuredApi ? 'configured' : 'unset'}`)
console.log(`- Mini API address class: ${classifyApi(configuredApi)}`)
console.log('- URL validation: enabled')
console.log('- Pages/navigation/upload/native controls/platform fallbacks: verified in build output')
console.log('This is an artifact check only; it does not claim DevTools, device, network, API, or database verification.')
