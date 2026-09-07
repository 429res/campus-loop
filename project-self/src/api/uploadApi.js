import http from "@/http";
export const uploadImageApi = (file, signal) => {
  const body = new FormData();
  body.append("file", file);
  return http.post("/api/uploads", body, { signal });
};
export const imageUrl = (url) => {
  if (!url || typeof url !== "string") return "";
  if (/^https?:\/\//i.test(url)) return url;
  if (!url.startsWith("/uploads/") && !url.startsWith("/demo/")) return "";
  return url.startsWith("/demo/") || !http.defaults.baseURL
    ? url
    : new URL(url, http.defaults.baseURL).href;
};
