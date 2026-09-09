import {canVisit,firstAllowed,routeScope} from "@/common/permissions";
import { createRouter, createWebHistory } from "vue-router";
import { useAuth } from "@/stores/auth";
const developmentRoutes = import.meta.env.DEV
  ? [
      {
        path: "/fixtures/item-review",
        component: () => import("@/views/Items.vue"),
        meta: { public: true, title: "物品审核组件夹具" },
      },
      {
        path: "/fixtures/categories",
        component: () => import("@/views/Categories.vue"),
        meta: { public: true, title: "分类维护组件夹具" },
      },
      {
        path: "/fixtures/disputes",
        component: () => import("@/views/Disputes.vue"),
        meta: { public: true, title: "交换争议组件夹具" },
      },
      {
        path: "/fixtures/reports",
        component: () => import("@/views/Reports.vue"),
        meta: { public: true, title: "举报队列组件夹具" },
      },
      {
        path: "/fixtures/stats",
        component: () => import("@/views/StatsFixture.vue"),
        meta: { public: true, title: "业务统计展示夹具" },
      },
    ]
  : [];
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {path:"/community",component:()=>import("@/views/Community.vue"),meta:{title:"校园动态"}},
    {path:"/connection",component:()=>import("@/views/Connection.vue"),meta:{public:true,title:"连接中断"}},
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
      meta: { title: "物品审核" },
    },
    {
      path: "/categories",
      component: () => import("@/views/Categories.vue"),
      meta: { title: "分类维护" },
    },
    {
      path: "/users",
      component: () => import("@/views/Users.vue"),
      meta: { title: "账号管理" },
    },
    {
      path: "/matches",
      component: () => import("@/views/Matches.vue"),
      meta: { title: "交换推荐" },
    },
    {
      path: "/disputes",
      component: () => import("@/views/Disputes.vue"),
      meta: { title: "交换争议" },
    },
    {
      path: "/reports",
      component: () => import("@/views/Reports.vue"),
      meta: { title: "举报队列" },
    },
    {
      path: "/exchanges",
      component: () => import("@/views/Exchanges.vue"),
      meta: { title: "交换记录" },
    },
    {
      path: "/controls",
      component: () => import("@/views/Controls.vue"),
      meta: { public: true, title: "控件实验室" },
    },
    {
      path: "/planned",
      component: () => import("@/views/Planned.vue"),
      meta: { title: "运营中心" },
    },
    {path:"/history-verifications",component:()=>import("@/views/HistoryVerifications.vue"),meta:{title:"履历核验"}},
    ...developmentRoutes,
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
  document.title = `${to.meta.title} · Campus Loop`;
  const auth = useAuth();
  const loginRoute={path:'/login',query:{redirect:to.fullPath},replace:true};
  if(to.meta.public && to.path!=='/login')return true;
  if(!auth.token)return to.path==='/login'?true:loginRoute;
  if(!auth.user){
    const token=auth.token;
    try {await auth.refresh();}
    catch(error){
      if(!auth.token)return to.path==='/login'?true:loginRoute;
      if(auth.token!==token)return false;
      return {path:'/connection',query:{redirect:to.path==='/login'?(to.query.redirect||'/'):to.fullPath},replace:true};
    }
    if(auth.token!==token)return auth.token?false:loginRoute;
  }
  if(to.path==='/login'){
    const next=typeof to.query.redirect==='string'?to.query.redirect:'/';
    return {path:Object.hasOwn(routeScope,next)&&canVisit(auth.user,next)?next:firstAllowed(auth.user),replace:true};
  }
  return canVisit(auth.user,to.path)?true:firstAllowed(auth.user);
});
export default router;
