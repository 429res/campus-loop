import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'

const require = createRequire(import.meta.url)
const Vue = require('vue')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { initPreContext, preJs } = require('@dcloudio/uni-cli-shared/dist/preprocess/index.js')
const buttonFile = fileURLToPath(new URL('../src/components/LoopButton.vue', import.meta.url))

// Compile the real SFC and run its render function in Vue, including listener fallthrough.
initPreContext('h5')
const source = preJs(readFileSync(buttonFile, 'utf8'), buttonFile)
const { descriptor } = parse(source, { filename: buttonFile })
const compiled = compileScript(descriptor, { id: 'loop-button-regression', inlineTemplate: true, genDefaultAs: '__component' })
const bindImports = code => code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"]/g, (_, imports, module) => {
  const names = imports.replace(/\bas\b/g, ':')
  return module === 'vue' ? `const { ${names} } = Vue` : ''
})
const keyboardFile = fileURLToPath(new URL('../src/composables/useKeyboard.js', import.meta.url))
const keyboardCode = bindImports(preJs(readFileSync(keyboardFile, 'utf8'), keyboardFile)).replace('export const useKeyboard', 'const useKeyboard')
const useKeyboard = new Function('Vue', `${keyboardCode}\nreturn useKeyboard`)(Vue)
const Button = new Function('Vue', 'useKeyboard', `${bindImports(compiled.content)}\nreturn __component`)(Vue, useKeyboard)

const node = type => ({
  type, props: {}, children: [], listeners: new Map(),
  addEventListener(name, handler) { this.listeners.set(name, handler) },
  removeEventListener(name) { this.listeners.delete(name) },
  click() {
    const event = { type: 'click', currentTarget: this }
    for (const handler of [this.props.onClick].flat().filter(Boolean)) handler(event)
  },
})
const renderer = Vue.createRenderer({
  createElement: node,
  createText: text => ({ ...node('text'), text }),
  createComment: text => ({ ...node('comment'), text }),
  insert(child, parent) { child.parent = parent; parent.children.push(child) },
  remove(child) { child.parent.children = child.parent.children.filter(item => item !== child) },
  patchProp(element, key, previous, next) { element.props[key] = next },
  setText(element, text) { element.text = text },
  setElementText(element, text) { element.text = text },
  parentNode: element => element.parent,
  nextSibling: () => null,
})
const mount = props => {
  const state = Vue.reactive(props)
  const root = node('root')
  const app = renderer.createApp({ setup: () => () => Vue.h(Button, state, () => 'Action') })
  app.mount(root)
  return { state, element: root.children[0], unmount: () => app.unmount() }
}
const keydown = (element, key, options = {}) => {
  const event = { key, currentTarget: element, preventDefault() { this.defaultPrevented = true }, ...options }
  element.listeners.get('keydown')(event)
  return event
}

test('mouse click reaches its parent once and keeps the original event', () => {
  const events = []
  const control = mount({ onClick: event => events.push(event) })
  control.element.click()
  assert.equal(events.length, 1)
  assert.equal(events[0].currentTarget, control.element)
  control.unmount()
})

test('Enter and Space activate once; held keys and composition do not repeat', () => {
  let calls = 0
  const control = mount({ onClick: () => calls++ })
  for (const key of ['Enter', ' ']) {
    assert.equal(keydown(control.element, key).defaultPrevented, true)
    keydown(control.element, key, { repeat: true })
    keydown(control.element, key, { isComposing: true })
  }
  keydown(control.element, 'ArrowDown')
  assert.equal(calls, 2)
  control.unmount()
})

test('disabled and loading suppress mouse and keyboard activation, then recover', async () => {
  for (const flag of ['disabled', 'loading']) {
    let calls = 0
    const control = mount({ [flag]: true, onClick: () => calls++ })
    control.element.click()
    keydown(control.element, 'Enter')
    keydown(control.element, ' ')
    assert.equal(calls, 0)
    assert.equal(control.element.props.disabled, true)
    assert.equal(control.element.props.tabindex, -1)
    assert.equal(control.element.props['aria-disabled'], true)
    if (flag === 'loading') assert.equal(control.element.props.loading, true)
    control.state[flag] = false
    await Vue.nextTick()
    control.element.click()
    assert.equal(calls, 1)
    assert.equal(control.element.props.disabled, false)
    assert.equal(control.element.props.tabindex, 0)
    control.unmount()
  }
})

test('native form action, CSS and accessible labels reach the inner button', () => {
  for (const formType of ['submit', 'reset']) {
    const control = mount({ formType, class: 'cl-btn cl-btn--primary', 'aria-label': 'Publish', 'aria-pressed': true, 'aria-current': 'page' })
    assert.equal(control.element.props['form-type'], formType)
    assert.equal(control.element.props.class, 'cl-btn cl-btn--primary')
    assert.equal(control.element.props['aria-label'], 'Publish')
    assert.equal(control.element.props['aria-pressed'], true)
    assert.equal(control.element.props['aria-current'], 'page')
    control.unmount()
  }
})

test('unmount removes the keyboard listener', () => {
  const control = mount({})
  assert.equal(control.element.listeners.has('keydown'), true)
  control.unmount()
  assert.equal(control.element.listeners.has('keydown'), false)
})

// Run after build:mp-weixin. This checks emitted wiring, not a WeChat runtime.
if (process.argv.includes('--mp-build')) {
  test('WeChat output preserves click, form and accessibility wiring', () => {
    const output = new URL('../dist/build/mp-weixin/', import.meta.url)
    const read = file => readFileSync(new URL(file, output), 'utf8')
    const buttonJs = read('components/LoopButton.js')
    const buttonWxml = read('components/LoopButton.wxml')
    assert.match(buttonJs, /wx:\/\/form-field-button/, 'cross-component form behavior must be retained')
    assert.match(buttonJs, /emits:\s*\["click"\]/)
    assert.match(buttonWxml, /bindtap="[^"]+"/)
    assert.match(buttonWxml, /form-type="\{\{[^}]+\}\}"/)
    for (const attribute of ['disabled', 'loading', 'aria-label', 'aria-pressed', 'aria-current']) {
      assert.ok(buttonWxml.includes(`${attribute}="{{`), `${attribute} must bind to the native button`)
    }
    for (const page of ['login', 'publish']) {
      const wxml = read(`pages/${page}/${page}.wxml`)
      assert.match(wxml, /<form\b[^>]*bindsubmit=/)
      // UniApp compiles custom-component props into the page JS uP binding.
      assert.match(read(`pages/${page}/${page}.js`), /["']form-type["']:\s*["']submit["']/)
    }
    assert.match(read('components/LoopLayout.wxml'), /<loop-button\b[^>]*bindclick=/)
  })
}
