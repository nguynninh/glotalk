import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { useEditor } from "./store/editor";
import { api } from "./lib/api";

// Webview vừa tải (mở app hoặc tải lại): job OCR còn sót từ trước không còn ai nhận kết quả, dừng cho đỡ tốn CPU.
api.ocrCancel().catch(() => {});

// Chỉ ở chế độ dev: mở store ra window để debug trong devtools.
if (import.meta.env.DEV) (window as unknown as { __glotalk: typeof useEditor }).__glotalk = useEditor;

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
