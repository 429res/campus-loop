import { createRouter, createWebHistory } from "vue-router";
import { useAuth } from "@/stores/auth";
const developmentRoutes = import.meta.env.DEV
  ? [
      {
        path: "/fixtures/categories",
        component: () => import("@/views/Categories.vue"),
        meta: { public: true, title: "分类维护组件夹具" },
      },
    ]
  : [];
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: "/login",
      component: () => import("@/views/Login.vue"),
      meta: { public: true, title: "管理员登录" },
    },
    {
      path: "/",
      component: () => import("@/views/Overview.vue"),
      meta: { title: "循环概览" },
    },
    {
      path: "/items",
      component: () => import("@/views/Items.vue"),
      meta: { title: "物品管理" },
    },
    {
      path: "/categories",
      component: () => import("@/views/Categories.vue"),
      meta: { title: "分类维护" },
    },
    {
      path: "/matches",
      component: () => import("@/views/Matches.vue"),
      meta: { title: "交换推荐" },
    },
    {
      path: "/controls",
      component: () => import("@/views/Controls.vue"),
      meta: { public: true, title: "控件实验室" },
    },
    {
      path: "/planned",
      component: () => import("@/views/Planned.vue"),
      meta: { title: "后续业务" },
    },
    ...developmentRoutes,
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
  document.title = `${to.meta.title} · Campus Loop`;
  if (to.meta.public) return true;
  const auth = useAuth();
  if (!auth.token) return { path: "/login", query: { redirect: to.fullPath } };
  if (!auth.user) {
    try {
      await auth.refresh();
    } catch {
      auth.clear();
      return { path: "/login", query: { redirect: to.fullPath } };
    }
  }
  return true;
});
export default router;
