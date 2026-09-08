// Adapted from the reference Axios wrapper: bearer session and central errors.
import axios from "axios";
import { ElMessage } from "element-plus";
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "",
  timeout: 10000,
});
const staleSession = config => config?.sessionToken != null && config.sessionToken !== sessionStorage.getItem("campus-loop.admin.token");
http.interceptors.request.use((config) => {
  const token = sessionStorage.getItem("campus-loop.admin.token");
  config.sessionToken = config.skipAuth ? null : token;
  if (token && !config.skipAuth)
    config.headers.Authorization = `Bearer ${token}`;
  return config;
});
http.interceptors.response.use(
  (response) => {
    if (staleSession(response.config)) return Promise.reject(new axios.CanceledError("会话已切换"));
    if (response.data?.code !== 200) {
      const error = new Error(response.data?.msg || "服务返回异常");
      if (!response.config?.silent) ElMessage.error(error.message);
      return Promise.reject(error);
    }
    return response.data;
  },
  (error) => {
    if (axios.isCancel(error)) return Promise.reject(error);
    if (staleSession(error.config)) return Promise.reject(new axios.CanceledError("会话已切换"));
    if (error.response?.status === 401 && !error.config?.skipAuth) {
      sessionStorage.removeItem("campus-loop.admin.token");
      window.dispatchEvent(new CustomEvent("campus-loop:unauthorized"));
    }
    if (!error.config?.silent) ElMessage.error(
      error.response?.data?.msg ||
        (error.code === "ECONNABORTED"
          ? "请求超时，请重试"
          : "无法连接服务，请检查后端是否启动"),
    );
    return Promise.reject(error);
  },
);
export default http;
