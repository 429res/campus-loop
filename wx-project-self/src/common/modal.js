import { nextTick } from 'vue'
// Retains the reference project's native-modal boundary and single H5 material shell.
let pending = null
export const showAppModal = ({ danger = false, ...options } = {}) => {
  if(pending) return pending
  let cleanup = () => {}
  pending = new Promise(resolve => {
    uni.showModal({
      confirmText:'确定',cancelText:'取消',confirmColor:danger ? '#c13748' : '#ca376f',cancelColor:'#596578',...options,
      success(result) { options.success?.(result);resolve(result) },
      complete(result) { cleanup();pending=null;options.complete?.(result) },
    })
    // #ifdef H5
    const previousFocus = document.activeElement
    nextTick(() => requestAnimationFrame(() => {
      const modal = document.querySelector('uni-modal .uni-modal')
      if(!modal) return
      modal.setAttribute('role','dialog');modal.setAttribute('aria-modal','true');modal.setAttribute('aria-label',options.title || '提示')
      const buttons = [...modal.querySelectorAll('.uni-modal__btn')]
      buttons.forEach(button => {button.setAttribute('role','button');button.setAttribute('tabindex','0')})
      buttons[0]?.focus()
      const keyboard = event => {
        if(event.key === 'Escape') {event.preventDefault();buttons[0]?.click()}
        if(['Enter',' '].includes(event.key) && buttons.includes(document.activeElement)) {event.preventDefault();document.activeElement.click()}
        if(event.key === 'Tab') {
          const current = buttons.indexOf(document.activeElement)
          event.preventDefault();buttons[(current + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
        }
      }
      document.addEventListener('keydown',keyboard)
      cleanup = () => {document.removeEventListener('keydown',keyboard);previousFocus?.focus?.()}
    }))
    // #endif
  })
  return pending
}
