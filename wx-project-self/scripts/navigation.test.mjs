import {test} from 'node:test'
import assert from 'node:assert/strict'
import {loginDestination,finishLogin,backWithinApp} from '../src/common/navigation.mjs'
test('login retains the intended page and safe record while refusing external redirects',()=>{
 assert.deepEqual(loginDestination({redirect:'community',id:'32'}),{page:'community',url:'/pages/community/community?id=32',tab:false})
 assert.equal(loginDestination({redirect:'exchanges',exchangeId:'44'}).url,'/pages/exchanges/exchanges?id=44')
 for(const bad of ['https://example.com','//example.com','login','register','../admin'])assert.equal(loginDestination({redirect:bad}).page,'profile')
 for(const id of ['0','-2','2&role=ADMIN','9007199254740992'])assert.equal(loginDestination({redirect:'community',id}).url,'/pages/community/community')
})
test('login replaces a detail entry and switches native tabs; back skips old authentication pages',()=>{
 const calls=[],uni={switchTab:o=>calls.push(['tab',o.url]),redirectTo:o=>calls.push(['replace',o.url]),navigateBack:o=>calls.push(['back',o.delta])}
 finishLogin(uni,{redirect:'matches'});finishLogin(uni,{redirect:'detail',id:5})
 backWithinApp(uni,[{route:'pages/login/login'},{route:'pages/detail/detail'}]);backWithinApp(uni,[{route:'pages/home/home'},{route:'pages/detail/detail'}]);backWithinApp(uni,[])
 assert.deepEqual(calls,[['tab','/pages/matches/matches'],['replace','/pages/detail/detail?id=5'],['tab','/pages/home/home'],['back',1],['tab','/pages/home/home']])
})
