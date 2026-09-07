<script setup>
import { ref, reactive } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuth } from "@/stores/auth";
import { User, Lock, ArrowRight } from "@element-plus/icons-vue";
import Brand from "@/components/Brand.vue";
const form = reactive({ username: "", password: "" }),
  formRef = ref(),
  busy = ref(false),
  error = ref("");
const auth = useAuth(),
  router = useRouter(),
  route = useRoute();
const rules = {
  username: [
    { required: true, message: "请输入本地初始化的账号", trigger: "blur" },
  ],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
};
async function submit() {
  if (busy.value || !(await formRef.value.validate().catch(() => false)))
    return;
  busy.value = true;
  error.value = "";
  try {
    await auth.login(form);
    form.password = "";
    const redirect =
      typeof route.query.redirect === "string" &&
      route.query.redirect.startsWith("/") &&
      !route.query.redirect.startsWith("//")
        ? route.query.redirect
        : "/";
    await router.replace(redirect);
  } catch (e) {
    error.value = e.response?.data?.msg || e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="login-page">
    <section class="login-story">
      <Brand /><span class="eyebrow">LESS WASTE. MORE POSSIBILITIES.</span>
      <h1>闲置有归处，<br />校园有循环<span>。</span></h1>
      <p>让一本书、一盏灯、一份需求，<br />连接校园里新的可能。</p>
      <div class="login-art">
        <div class="art-circle" />
        <img src="/demo/book.svg" alt="校园书籍占位插画" /><span
          class="floating-label glass-control"
          >书籍 → 摄影 → 阅读 <b>↻</b></span
        >
      </div>
      <small>基于需求匹配与多方置换的校园闲置物品循环管理系统</small>
    </section>
    <section class="login-panel">
      <div class="login-form">
        <span class="eyebrow">CAMPUS LOOP / ADMIN</span>
        <h2>欢迎回到循环工作台</h2>
        <p class="muted">从这里，守护每一次校园交换。</p>
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="submit"
          ><el-form-item label="账号" prop="username"
            ><el-input
              v-model="form.username"
              autocomplete="username"
              placeholder="请输入管理员账号"
              :prefix-icon="User"
              size="large" /></el-form-item
          ><el-form-item label="密码" prop="password"
            ><el-input
              v-model="form.password"
              autocomplete="current-password"
              type="password"
              show-password
              placeholder="请输入密码"
              :prefix-icon="Lock"
              size="large" /></el-form-item
          ><el-alert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
            show-icon /><el-button
            type="primary"
            native-type="submit"
            size="large"
            class="login-submit"
            :loading="busy"
            >进入工作台<el-icon><ArrowRight /></el-icon></el-button
        ></el-form>
        <p class="login-hint">
          首次使用：请先按照 README
          在自己的开发数据库中初始化账号。这里不提供默认口令。
        </p>
        <router-link to="/controls" class="text-link"
          >浏览公开控件实验室 ↗</router-link
        >
      </div>
      <div class="login-bottom">
        让闲置，继续有用 <span>Campus Loop © 2026</span>
      </div>
    </section>
  </main>
</template>
