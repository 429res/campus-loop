<script setup>
import { ref } from "vue";
import { ElMessage, ElNotification, ElMessageBox } from "element-plus";
import {
  Search,
  Plus,
  MoreFilled,
} from "@element-plus/icons-vue";
import SysDialog from "@/components/SysDialog.vue";
import UploadImage from "@/components/UploadImage.vue";
import { useAuth } from "@/stores/auth";
import {
  useOverlayLock,
  acquireOverlayLock,
} from "@/composables/useOverlayLock";
const auth = useAuth(),
  tab = ref("发现"),
  segment = ref("全部"),
  query = ref(""),
  choice = ref(""),
  date = ref(""),
  enabled = ref(true),
  check = ref(false),
  radio = ref("分类优先"),
  page = ref(1),
  dialog = ref(false),
  drawer = ref(false),
  image = ref(""),
  busy = ref(false),
  field = ref(""),
  reduced = ref(document.documentElement.dataset.glass === "reduced"),
  motionReduced = ref(localStorage.getItem("campus-loop.motion-reduced") === "true");
const options = ["全部", "双方", "三方"];
useOverlayLock(drawer);
function toggleMaterial() {
  document.documentElement.dataset.glass = reduced.value ? "reduced" : "full";
}
function toggleMotion() {
  document.documentElement.classList.toggle("cl-motion-reduced", motionReduced.value);
  localStorage.setItem("campus-loop.motion-reduced", String(motionReduced.value));
}
function notice() {
  ElNotification({
    title: "控件演示",
    message: "通知仅演示本地反馈，没有修改业务数据。",
    type: "info",
    duration: 3500,
  });
}
function localLoading() {
  if (busy.value) return;
  busy.value = true;
  setTimeout(() => (busy.value = false), 900);
}
async function confirm() {
  const release = acquireOverlayLock();
  try {
    await ElMessageBox.confirm(
      "这是本地交互演示，不会提交业务数据。",
      "确认控件",
      {
        confirmButtonText: "体验确认",
        cancelButtonText: "取消",
        lockScroll: false,
      },
    );
    ElMessage.info("已完成本地控件演示");
  } catch {
  } finally {
    release();
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">ONE SYSTEM, EVERY CONTROL</span>
      <h1>控件实验室 <span class="heading-spark">✳</span></h1>
      <p>统一材质、连贯反馈，让每个交互都有熟悉的手感。</p>
    </div>
    <el-switch
      aria-label="简化玻璃材质"
      v-model="reduced"
      active-text="简化材质"
      inactive-text="玻璃材质"
      @change="toggleMaterial"
    />
  </div>
  <el-alert
    title="这是开发演示入口。演示操作不写入业务数据；已登录管理员的图片上传会调用真实上传接口。"
    type="info"
    :closable="false"
    show-icon
  />
  <div class="control-grid">
    <section class="panel control-card">
      <span class="control-number">01 / NAVIGATION</span>
      <h2>导航与选择</h2>
      <p>胶囊与底部指示器随选项连续移动。</p>
      <el-tabs v-model="tab"
        ><el-tab-pane label="发现" name="发现">为校园发现新的可能</el-tab-pane
        ><el-tab-pane label="收藏" name="收藏">收藏功能待后续接入</el-tab-pane
        ><el-tab-pane label="交换" name="交换"
          >交换确认功能待后续接入</el-tab-pane
        ></el-tabs
      ><el-segmented v-model="segment" :options="options" block />
      <div class="control-row">
        <el-radio-group v-model="radio"
          ><el-radio value="分类优先">分类优先</el-radio
          ><el-radio value="标签优先">标签优先</el-radio></el-radio-group
        >
      </div>
      <div class="control-row"><el-switch v-model="motionReduced" aria-label="减少动态效果" active-text="减少动态效果" @change="toggleMotion"/><small class="muted">系统减少动态效果偏好始终优先</small></div>
    </section>
    <section class="panel control-card">
      <span class="control-number">02 / ACTIONS</span>
      <h2>按钮与反馈</h2>
      <p>轻边缘、短按压，保持禁用与加载语义。</p>
      <div class="control-row">
        <el-button
          type="primary"
          :icon="Plus"
          @click="ElMessage.info('发布入口位于用户端；此处仅演示按钮反馈')"
          >主要操作</el-button
        ><el-button @click="notice">次要操作</el-button
        ><el-button type="primary" text @click="ElMessage.info('文字操作反馈')"
          >文字按钮</el-button
        >
      </div>
      <div class="control-row">
        <el-tooltip content="图标按钮也有清晰名称"
          ><el-button
            :icon="Search"
            circle
            aria-label="图标按钮演示"
            @click="ElMessage.info('图标按钮反馈')" /></el-tooltip
        ><el-button :loading="busy" @click="localLoading">{{
          busy ? "处理中" : "体验加载"
        }}</el-button
        ><el-button disabled>禁用状态</el-button
        ><el-button type="danger" plain @click="confirm">确认演示</el-button>
      </div>
    </section>
    <section class="panel control-card">
      <span class="control-number">03 / FIELDS</span>
      <h2>输入与检索</h2>
      <p>稳定内容底色，焦点边缘和错误状态可辨。</p>
      <el-form label-position="top"
        ><el-form-item label="搜索物品"
          ><el-input
            v-model="query"
            :prefix-icon="Search"
            placeholder="试试「台灯」"
            clearable /></el-form-item
        ><el-form-item
          label="想要的物品"
          :error="field ? '' : '必填演示：填写后错误消失'"
          ><el-input
            v-model="field"
            placeholder="输入内容以体验状态变化" /></el-form-item
        ><el-form-item label="交换说明"
          ><el-input
            type="textarea"
            placeholder="长文本保持清晰、稳定的内容表面"
            :rows="2" /></el-form-item
      ></el-form>
    </section>
    <section class="panel control-card">
      <span class="control-number">04 / PICKERS</span>
      <h2>选择器与状态</h2>
      <p>浮层从触发位置展开，选项支持键盘操作。</p>
      <el-form label-position="top"
        ><el-form-item label="物品分类"
          ><el-select v-model="choice" placeholder="选择分类" clearable
            ><el-option label="图书教材" value="books" /><el-option
              label="电子数码"
              value="digital" /><el-option
              label="生活用品"
              value="daily" /></el-select></el-form-item
        ><el-form-item label="期望交接日期"
          ><el-date-picker
            v-model="date"
            type="date"
            placeholder="选择日期"
            style="width: 100%" /></el-form-item
      ></el-form>
      <div class="control-row">
        <el-switch
          aria-label="接收提醒"
          v-model="enabled"
          active-text="接收提醒"
        /><el-checkbox v-model="check">同意演示约定</el-checkbox
        ><el-switch disabled aria-label="已禁用的开关" />
      </div>
    </section>
    <section class="panel control-card">
      <span class="control-number">05 / OVERLAYS</span>
      <h2>弹层、菜单与提示</h2>
      <p>快速开关、Escape 与焦点返回由公共组件处理。</p>
      <div class="control-row">
        <el-button data-testid="open-dialog" @click="dialog = true"
          >打开弹窗</el-button
        ><el-button data-testid="open-drawer" @click="drawer = true"
          >打开抽屉</el-button
        ><el-dropdown trigger="click" @command="ElMessage.info('下拉菜单演示')"
          ><el-button :icon="MoreFilled">更多操作</el-button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="copy">菜单操作示例</el-dropdown-item
              ><el-dropdown-item disabled
                >待开发操作</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        >
      </div>
      <div class="control-row">
        <el-tooltip content="这里是同一材质层的提示信息"
          ><el-button>悬停查看提示</el-button></el-tooltip
        ><el-button @click="notice">通知</el-button
        ><el-button
          @click="ElMessage.success('控件反馈示例，不代表业务操作成功')"
          >消息</el-button
        ><el-popover
          title="轻浮层"
          content="只有外壳使用玻璃；内部内容不重复模糊。"
          trigger="click"
          ><template #reference
            ><el-button>气泡说明</el-button></template
          ></el-popover
        >
      </div>
    </section>
    <section class="panel control-card">
      <span class="control-number">06 / UPLOAD & PAGES</span>
      <h2>上传与分页</h2>
      <p>复用真实上传组件，未登录时禁用上传。</p>
      <UploadImage v-model="image" :limit="1" :disabled="!auth.token" />
      <p v-if="!auth.token" class="muted">
        登录管理员账号后可测试真实图片上传。
      </p>
      <el-pagination
        v-model:current-page="page"
        background
        :page-size="10"
        :total="70"
        :pager-count="5"
        layout="prev, pager, next"
      /><small class="muted">分页演示 · 当前第 {{ page }} 页</small>
    </section>
  </div>
  <section class="panel accessibility-note">
    <h2>材质有边界，体验要完整。</h2>
    <div>
      <p>
        <b>内容清晰</b>商品图片、表格和长表单使用稳定底色，卡片不持续实时模糊。
      </p>
      <p>
        <b>设备友好</b
        >低核心设备、节省流量和不支持滤镜的浏览器自动降级，也可手动简化材质。
      </p>
      <p>
        <b>尊重偏好</b>系统减少动态效果会关闭明显位移；Tab、Enter、Escape
        和清晰焦点保持可用。
      </p>
    </div>
    <small
      >这是 Web 材质近似，不是 Apple
      原生光学折射。平台原生控件遵循平台能力。</small
    >
  </section>
  <SysDialog
    title="统一弹窗演示"
    :visible="dialog"
    :height="160"
    @on-close="dialog = false"
    @on-confirm="dialog = false"
    ><template #content
      ><el-alert
        title="这是本地控件演示，确定或取消均不保存业务数据。"
        type="info"
        :closable="false" /><el-input
        placeholder="体验聚焦与键盘导航"
        aria-label="弹窗内输入"
        style="margin-top: 20px" /></template></SysDialog
  ><el-drawer
    :lock-scroll="false"
    v-model="drawer"
    title="统一抽屉演示"
    size="min(460px, 100vw)"
    ><p>展开、关闭与连续操作沿用统一动效，正文保持稳定。</p>
    <el-form label-position="top"
      ><el-form-item label="备注"
        ><el-input placeholder="这里只做交互演示" /></el-form-item></el-form
    ><template #footer
      ><el-button @click="drawer = false">关闭抽屉</el-button></template
    ></el-drawer
  >
</template>
