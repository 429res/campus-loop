import { loadEnv } from 'vite'
import { writeFileSync } from 'node:fs'

const mode = process.argv[2] || 'development'
const env = loadEnv(mode, process.cwd(), 'VITE_')
// UniApp CLI reads this before loading vite.config.js. Generate it in npm's pre hook.
writeFileSync(new URL('../src/manifest.json', import.meta.url), JSON.stringify({
  name:'Campus Loop',appid:'__UNI__CAMPUSLOOP',description:'校园闲置，循环相遇',
  versionName:'0.1.0',versionCode:'1',vueVersion:'3',
  'mp-weixin':{appid:env.VITE_WECHAT_APP_ID || '',setting:{urlCheck:true},usingComponents:true},
  h5:{title:'Campus Loop · 校园闲置循环',router:{mode:'hash'}},
  uniStatistics:{enable:false},
},null,2)+'\n')
