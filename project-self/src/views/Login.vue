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
const showDeveloperTools = import.meta.env.DEV;
const rules = {
  username: [
    { required: true, message: "请输入管理员账号", trigger: "blur" },
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
      <div class="login-art" aria-label="书籍、相机与台灯的循环示意">
        <figure v-for="item in [{name:'书籍',image:'book'},{name:'相机',image:'camera'},{name:'台灯',image:'lamp'}]" :key="item.image">
          <img :src="`/demo/${item.image}.svg`" :alt="item.name"/><figcaption>{{ item.name }}</figcaption>
        </figure>
      </div>
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
        <router-link v-if="showDeveloperTools" to="/controls" class="text-link"
          >浏览公开控件实验室 ↗</router-link
        >
      </div>
      <div class="login-bottom">
        让闲置，继续有用 <span>Campus Loop © 2026</span>
      </div>
    </section>
  </main>
</template>
