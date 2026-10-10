import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";
import { defineConfig } from "vite";

const out = resolve(__dirname, "../app/src/main/assets/skipsilence");
const alias = {
  "#imports": resolve(__dirname, "android/imports.ts"),
  "@": resolve(__dirname, "upstream"),
  "~": resolve(__dirname, "upstream"),
};

// TARGET=popup | content | processor (see build.sh)
const target = process.env.TARGET ?? "popup";

const script = (entry: string, fileName: string) =>
  defineConfig({
    resolve: { alias },
    build: {
      outDir: out,
      emptyOutDir: false,
      lib: { entry: resolve(__dirname, entry), formats: ["iife"], name: "AASkipSilence", fileName: () => fileName },
    },
  });

export default target === "content"
  ? script("android/content-entry.ts", "content.js")
  : target === "processor"
    ? script("android/processor-entry.ts", "volume-processor.js")
    : defineConfig({
        root: resolve(__dirname, "upstream/entrypoints/popup"),
        base: "./",
        plugins: [react(), tailwindcss()],
        resolve: { alias },
        build: { outDir: resolve(out, "popup"), emptyOutDir: true },
      });
