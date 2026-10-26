import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      strategies: "injectManifest",
      srcDir: "src",
      filename: "sw.ts",
      registerType: "autoUpdate",
      includeAssets: ["icon-192.png", "icon-512.png", "icon-512-maskable.png"],
      includeManifestIcons: false,
      manifest: {
        name: "낙상제로 보호자",
        short_name: "낙상제로",
        description: "어르신 낙상 알림을 받는 보호자 화면",
        lang: "ko",
        start_url: "/",
        scope: "/",
        display: "standalone",
        background_color: "#F5F2EA",
        theme_color: "#35634A",
        icons: [
          { src: "icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "icon-512-maskable.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      injectManifest: {
        globPatterns: ["**/*.{js,css,html,svg,png,otf,ico,webmanifest}"],
      },
      devOptions: {
        enabled: true,
        type: "module",
      },
    }),
  ],
  server: {
    host: "0.0.0.0",
    port: 5173,
    proxy: {
      "/api": "http://127.0.0.1:3000",
    },
  },
  preview: {
    host: "0.0.0.0",
    port: 4173,
  },
});
