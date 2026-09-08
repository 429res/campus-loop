import { defineStore } from "pinia";
import http from "@/http";
export const SESSION_KEY = "campus-loop.admin.token";
export const useAuth = defineStore("auth", {
  state: () => ({
    token: sessionStorage.getItem(SESSION_KEY) || "",
    user: null,
  }),
  actions: {
    async login(payload) {
      const { data } = await http.post("/api/auth/login", payload, {
        skipAuth: true,
      });
      if (data.user.role !== "ADMIN")
        throw new Error("此入口仅供管理员使用，请通过用户端登录。");
      this.token = data.token;
      this.user = data.user;
      sessionStorage.setItem(SESSION_KEY, data.token);
    },
    async refresh() {
      const token = this.token;
      const { data } = await http.get("/api/auth/me");
      if (this.token !== token) return;
      this.user = data;
      if (data.role !== "ADMIN") {
        this.clear();
        throw new Error("需要管理员权限");
      }
    },
    clear() {
      this.token = "";
      this.user = null;
      sessionStorage.removeItem(SESSION_KEY);
    },
  },
});
