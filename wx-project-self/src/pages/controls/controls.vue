<script setup>
import LoopSwitch from '../../components/LoopSwitch.vue'
import LoopSlider from '../../components/LoopSlider.vue'
import { useTheme } from '../../composables/useTheme'
const { reducedMotion, toggleMotion } = useTheme()
const sliderValue = ref(40)
import LoopPicker from '../../components/LoopPicker.vue'
import LoopButton from '../../components/LoopButton.vue'
import { ref } from 'vue'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopSegment from '../../components/LoopSegment.vue'
import LoopSheet from '../../components/LoopSheet.vue'
import { showAppModal } from '../../common/modal'
const segment = ref(0), text = ref(''), date = ref(''), condition = ref(1), checked = ref(true), page = ref(1), sheet = ref(false), image = ref(''), busy = ref(false)
const conditions = ['有使用痕迹','成色良好','几乎全新']
const toast = () => uni.showToast({title:'这是一条提示示例',icon:'none'})
const modal = () => showAppModal({title:'控件演示',content:'使用共享材质与动效。微信端保留系统原生弹窗。',confirmText:'知道了'})
const menu = () => uni.showActionSheet({itemList:['操作菜单示例 A','操作菜单示例 B'],success:res => uni.showToast({title:`选择了示例 ${res.tapIndex + 1}`,icon:'none'})})
const photo = () => uni.chooseImage({count:1,success:res => {image.value = res.tempFilePaths[0]}})
const loadingExample = async () => {
  if (busy.value) return
  busy.value = true
  // Explicit UI demo only, not a business write. User closes a native acknowledgement to stop it.
  await showAppModal({title:'加载状态展示',content:'页面按钮当前处于加载与禁用状态。关闭后恢复。',showCancel:false})
  busy.value = false
}
</script>
<template>
  <LoopLayout><view class="cl-page-heading"><text class="cl-title">控件实验室</text><text class="cl-subtitle">同一套颜色、材质与连续动效，连接 H5 与微信原生能力。</text></view><view class="cl-notice">这是开发演示入口。示例选择不会写入业务数据库；正式图片上传请使用「发布」页。材质是 Web / 小程序近似，不是 Apple 原生光学折射。</view><view class="gallery-grid"><view class="cl-panel cl-stack"><text class="cl-section-title">按钮与状态</text><view class="gallery-row"><LoopButton class="cl-btn cl-btn--primary" @click="toast">主要行动</LoopButton><LoopButton class="cl-btn" @click="toast">次要行动</LoopButton><LoopButton class="cl-icon-btn" aria-label="信息提示" @click="toast">ⓘ</LoopButton></view><view class="gallery-row"><LoopButton class="cl-btn cl-btn--blue" :loading="busy" :disabled="busy" @click="loadingExample">{{ busy ? '加载中…' : '展示加载状态' }}</LoopButton><LoopButton class="cl-btn" disabled>禁用状态</LoopButton></view><text class="cl-hint">悬停、按压、键盘焦点、加载与禁用由公共样式继承。</text><text class="cl-section-title">连续选中</text><LoopSegment v-model="segment" :options="['发现','想要','拥有']"/><text class="cl-hint">当前：{{ ['发现','想要','拥有'][segment] }}。快速反向切换时，选中块会从当前位置继续移动。</text></view><view class="cl-panel cl-form"><text class="cl-section-title">输入与选择</text><view class="cl-field"><text class="cl-field-title">搜索 / 输入</text><input v-model="text" class="cl-input" placeholder="试试键盘 Tab 聚焦" aria-label="输入示例"/></view><view class="cl-field"><text class="cl-field-title">错误状态示例</text><input class="cl-input" aria-label="错误输入示例" aria-invalid="true" placeholder="必填字段"/><text class="cl-error">请填写这项内容（视觉示例）</text></view><view class="cl-grid-2"><LoopPicker :range="conditions" :value="condition" @change="condition=Number($event.detail.value)" aria-label="选择器示例"><view class="cl-picker" aria-label="选择器示例">{{ conditions[condition] }} ⌄</view></LoopPicker><LoopPicker mode="date" :value="date" @change="date=$event.detail.value" aria-label="日期选择器"><view class="cl-picker" aria-label="日期选择器">{{ date || '选择日期' }} ⌄</view></LoopPicker></view><view class="gallery-row"><LoopSwitch :checked="checked" color="#d63f78" aria-label="开关示例" @change="checked=$event.detail.value"/><text class="cl-label">{{ checked ? '开关已开启' : '开关已关闭' }}</text></view><LoopSlider v-model="sliderValue" aria-label="滑块示例"/></view><view class="cl-panel cl-stack"><text class="cl-section-title">浮层与反馈</text><view class="gallery-row"><LoopButton class="cl-btn" @click="menu">下拉 / 操作菜单</LoopButton><LoopButton class="cl-btn" @click="toast">提示与通知</LoopButton><LoopButton class="cl-btn" @click="modal">打开弹窗</LoopButton><LoopButton class="cl-btn" @click="sheet=true">打开抽屉</LoopButton></view><text class="cl-hint">H5 弹层统一外壳，正文没有重复玻璃层。抽屉支持 Escape、焦点圈定与关闭后返回触发按钮。</text><text class="cl-section-title">分页</text><view class="gallery-row"><LoopButton class="cl-btn" :disabled="page===1" @click="page--">上一页</LoopButton><text class="cl-label">{{ page }} / 3</text><LoopButton class="cl-btn" :disabled="page===3" @click="page++">下一页</LoopButton></view></view><view class="cl-panel cl-stack"><text class="cl-section-title">图片选择 / 上传控件</text><LoopButton class="gallery-upload cl-btn" @click="photo"><image v-if="image" :src="image" mode="aspectFit"/><text v-else>＋ 选择一张本地图片</text></LoopButton><LoopButton v-if="image" class="cl-btn" @click="image=''">移除预览</LoopButton><text class="cl-hint">这里仅预览，不上传。选择相册与拍摄保持平台原生流程。</text><text class="cl-section-title">降级与可访问性</text><view class="gallery-row"><LoopSwitch :checked="reducedMotion" color="#d63f78" aria-label="减少动态效果" @change="toggleMotion"/><text class="cl-label">减少动态效果 {{ reducedMotion ? '已开启' : '跟随系统' }}</text></view><text class="cl-hint">开启系统“减少动态效果”后所有自定义过渡停用。低性能 H5 自动使用实色材质；微信控件默认实色。深色模式在右上角切换。</text></view></view><LoopSheet v-model="sheet" title="可中断的抽屉"><view class="cl-stack"><text class="cl-subtitle">快速打开和关闭不会遗留遮罩。退出键可以关闭，Tab 只在抽屉内移动。</text><input class="cl-input" aria-label="抽屉输入示例" placeholder="聚焦状态使用共同规范"/><LoopButton class="cl-btn cl-btn--primary" @click="sheet=false">关闭抽屉</LoopButton></view></LoopSheet></LoopLayout>
</template>
<style scoped>
.gallery-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px;margin-top:24px;align-items:start}.gallery-row{display:flex;flex-wrap:wrap;align-items:center;gap:10px}.gallery-upload{min-height:145px;border-style:dashed;color:var(--cl-muted)}.gallery-upload image{width:100%;height:180px}.gallery-grid .cl-section-title{font-size:18px}.gallery-grid .cl-grid-2{gap:12px}@media(max-width:760px){.gallery-grid{grid-template-columns:1fr}}
</style>
