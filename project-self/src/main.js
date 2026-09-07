import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/dist/index.css";
import "element-plus/theme-chalk/dark/css-vars.css";
import "../../shared/design-tokens.css";
import "./styles/base.css";
import "./styles/liquid-glass.css";
import App from "./App.vue";
import router from "./router";
const theme =
  localStorage.getItem("campus-loop.theme") ||
  (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
document.documentElement.dataset.theme = theme;
document.documentElement.classList.toggle("dark", theme === "dark");
document.documentElement.classList.toggle("cl-motion-reduced", localStorage.getItem("campus-loop.motion-reduced") === "true");
if (
  (navigator.hardwareConcurrency && navigator.hardwareConcurrency <= 4) ||
  navigator.connection?.saveData ||
  (navigator.deviceMemory && navigator.deviceMemory <= 4)
)
  document.documentElement.dataset.glass = "reduced";
createApp(App)
  .use(createPinia())
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount("#app");
