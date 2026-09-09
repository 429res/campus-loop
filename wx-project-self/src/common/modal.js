import { nextTick } from 'vue'
import { acquireOverlayLock } from '../composables/useOverlayLock'
// The native modal is the top interaction layer, including when opened from a sheet.
let pending = null
let modalOpen = false
export const isAppModalOpen = () => modalOpen
export const showAppModal = ({ danger = false, ...options } = {}) => {
  if(pending) return pending
  modalOpen = true
  let resolveResult, settled = false, cleanup = () => {}
  const result = new Promise(resolve => {resolveResult=resolve})
  pending = result
  const settle = value => {if(!settled) {settled=true;resolveResult(value)}}
  // #ifdef H5
  const previousFocus = document.activeElement
  const previousSheet = previousFocus?.closest?.('.sheet-panel') || document.querySelector('.sheet-root.open .sheet-panel')
  const releaseLock = acquireOverlayLock()
  let frame, finished = false, removeKeyboard = () => {}
  cleanup = () => {
    finished=true;cancelAnimationFrame(frame);removeKeyboard();releaseLock()
    nextTick(() => {
      if(modalOpen) return
      const usable = previousFocus?.isConnected && previousFocus !== document.body && !previousFocus.closest('[inert]')
        && previousFocus.getAttribute('aria-disabled')!=='true' && !previousFocus.disabled
      if(usable) previousFocus.focus?.()
      else if(previousSheet?.isConnected && previousSheet.closest('.sheet-root.open')) previousSheet.focus?.()
    })
  }
  nextTick(() => {
    if(finished)return
    frame=requestAnimationFrame(() => {
      if(finished)return
      const modal = document.querySelector('uni-modal .uni-modal')
      if(!modal)return
      modal.setAttribute('role','dialog');modal.setAttribute('aria-modal','true');modal.setAttribute('aria-label',options.title || '提示')
      const buttons = [...modal.querySelectorAll('.uni-modal__btn')]
      buttons.forEach(button => {button.setAttribute('role','button');button.setAttribute('tabindex','0')})
      const fields=[...modal.querySelectorAll('input,textarea')]
      const targets=[...fields,...buttons]
      const keyboard = event => {
        if(!modalOpen || !['Escape','Enter',' ','Tab'].includes(event.key))return
        if(event.key==='Tab') {
          event.preventDefault();event.stopImmediatePropagation()
          const current=targets.indexOf(document.activeElement)
          const next=current<0?(event.shiftKey?targets.length-1:0):(current+(event.shiftKey?targets.length-1:1))%targets.length
          targets[next]?.focus();return
        }
        if(event.key==='Escape') {
          event.preventDefault();event.stopImmediatePropagation()
          // An informational modal with no Cancel must not confirm merely because Escape was pressed.
          if(!event.repeat && options.showCancel!==false) buttons[0]?.click()
          return
        }
        if(buttons.includes(document.activeElement)) {
          event.preventDefault();event.stopImmediatePropagation()
          if(!event.repeat && !event.isComposing) document.activeElement.click()
        }
      }
      document.addEventListener('keydown',keyboard,true)
      removeKeyboard=()=>document.removeEventListener('keydown',keyboard,true)
      targets[0]?.focus()
    })
  })
  // #endif
  const complete = value => {modalOpen=false;cleanup();pending=null;settle({confirm:false,cancel:true,...value});options.complete?.(value)}
  try {
    uni.showModal({
      confirmText:'确定',cancelText:'取消',confirmColor:danger ? '#c13748' : '#ca376f',cancelColor:'#596578',...options,
      success(value) {options.success?.(value);settle(value)},
      fail(value) {options.fail?.(value);settle({confirm:false,cancel:true})},
      complete,
    })
  } catch(error) {complete({confirm:false,cancel:true});throw error}
  return result
}
