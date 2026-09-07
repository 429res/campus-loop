<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuth } from "@/stores/auth";
import {
  DataBoard,
  Box,
  Connection,
  MagicStick,
  Grid,
  Sunny,
  Moon,
  Fold,
  ArrowRight,
  User,
  SwitchButton,
} from "@element-plus/icons-vue";
import Brand from "@/components/Brand.vue";
import http from "@/http";
const route = useRoute(),
  router = useRouter(),
  auth = useAuth();
const menuOpen = ref(false),
  dark = ref(document.documentElement.dataset.theme === "dark");
const links = [
  { path: "/", label: "循环概览", icon: DataBoard },
  { path: "/items", label: "物品管理", icon: Box },
  { path: "/matches", label: "交换推荐", icon: Connection },
  { path: "/planned", label: "后续业务", icon: Grid },
  { path: "/controls", label: "控件实验室", icon: MagicStick },
];
const activeIndex = computed(() =>
  Math.max(
    0,
    links.findIndex((x) => x.path === route.path),
  ),
);
function toggleTheme() {
  dark.value = !dark.value;
  const theme = dark.value ? "dark" : "light";
  document.documentElement.dataset.theme = theme;
  document.documentElement.classList.toggle("dark", dark.value);
  localStorage.setItem("campus-loop.theme", theme);
}
async function logout() {
  if (auth.token) {
    try {
      await http.post("/api/auth/logout");
    } catch {
      return;
    }
  }
  auth.clear();
  router.push("/login");
}
function onKey(event) {
  if (event.key === "Escape" && menuOpen.value) menuOpen.value = false;
}
function unauthorized() {
  auth.clear();
  if (route.path != "/login" && !route.meta.public)
    router.replace({ path: "/login", query: { redirect: route.fullPath } });
}
watch(
  () => route.path,
  () => (menuOpen.value = false),
);
onMounted(() => {
  window.addEventListener("campus-loop:unauthorized", unauthorized);
  window.addEventListener("keydown", onKey);
});
onBeforeUnmount(() => {
  window.removeEventListener("campus-loop:unauthorized", unauthorized);
  window.removeEventListener("keydown", onKey);
});
</script>
<template>
  <router-view v-if="route.path === '/login'" />
  <div v-else class="app-shell">
    <a href="#main-content" class="skip-link">跳转到主要内容</a>
    <aside
      class="sidebar glass-control"
      :class="{ 'is-open': menuOpen }"
      id="main-navigation"
    >
      <router-link to="/" class="brand-link"><Brand caption /></router-link>
      <div class="workspace-label">校园管理工作台 <span>ADMIN</span></div>
      <nav class="side-nav" aria-label="主导航">
        <span
          class="nav-indicator"
          :style="{ transform: `translateY(${activeIndex * 54}px)` }"
        /><router-link
          v-for="link in links"
          :key="link.path"
          :to="link.path"
          :aria-current="route.path === link.path ? 'page' : undefined"
          ><el-icon><component :is="link.icon" /></el-icon>{{ link.label
          }}<span v-if="link.path === '/matches'" class="mini-dot"
        /></router-link>
      </nav>
      <div class="sidebar-note">
        <span class="tiny-label">CIRCULAR CAMPUS</span>
        <p>每一次交换，<br />都是一个新开始。</p>
        <div class="note-loop" aria-hidden="true">↗<span>↙</span></div>
        <small>发现需求 · 连接同学 · 延续价值</small>
      </div>
      <div class="sidebar-foot"><i /> Campus Loop · 起步版本</div>
    </aside>
    <button
      v-if="menuOpen"
      class="mobile-scrim"
      aria-label="关闭导航"
      @click="menuOpen = false"
    />
    <div class="workspace">
      <header class="topbar glass-control">
        <div class="breadcrumb">
          <el-button
            class="mobile-menu"
            :icon="Fold"
            circle
            aria-label="打开导航"
            :aria-expanded="menuOpen"
            aria-controls="main-navigation"
            @click="menuOpen = !menuOpen"
          /><span>工作台</span><el-icon><ArrowRight /></el-icon
          ><strong>{{ route.meta.title }}</strong>
        </div>
        <div class="topbar-actions">
          <span class="environment"><i /> 本地开发</span
          ><el-tooltip :content="dark ? '切换浅色' : '切换深色'"
            ><el-button
              :icon="dark ? Sunny : Moon"
              circle
              :aria-label="dark ? '切换浅色' : '切换深色'"
              @click="toggleTheme" /></el-tooltip
          ><el-dropdown trigger="click" @command="logout"
            ><el-button class="account-button"
              ><span class="avatar">{{
                (auth.user?.displayName || "访客").slice(0, 1)
              }}</span
              ><span
                >{{ auth.user?.displayName || "访客"
                }}<small>{{
                  auth.user ? "校园管理员" : "公开控件预览"
                }}</small></span
              ></el-button
            ><template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item :icon="SwitchButton">{{
                  auth.user ? "退出登录" : "前往登录"
                }}</el-dropdown-item></el-dropdown-menu
              ></template
            ></el-dropdown
          >
        </div>
      </header>
      <main id="main-content" class="main-content"><router-view /></main>
      <footer class="page-footer">
        Campus Loop <span>让校园里的好东西，遇见下一个需要它的人。</span>
      </footer>
    </div>
  </div>
</template>
