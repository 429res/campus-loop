import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";
import { syncBrandAssets } from "../shared/brand.mjs";
syncBrandAssets(new URL("./public/", import.meta.url));
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  return {
    plugins: [vue()],
    resolve: {
      alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
    },
    server: {
      port: 5174,
      strictPort: true,
      proxy: {
        "/api": {
          target: env.VITE_API_PROXY || "http://127.0.0.1:8088",
          changeOrigin: true,
        },
        "/uploads": {
          target: env.VITE_API_PROXY || "http://127.0.0.1:8088",
          changeOrigin: true,
        },
      },
    },
    preview: { port: 4174, strictPort: true },
  };
});
