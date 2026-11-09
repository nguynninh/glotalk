import { autosaveProject } from "./actions";
import { useEditor } from "./editor";

/** Các trường được ghi vào file `.glotalk`; đổi trường nào thì tự lưu. */
const SAVED: (keyof ReturnType<typeof useEditor.getState>)[] = ["video", "assets", "tracks", "clips", "segments", "subStyle", "grade", "regions", "uid", "ocrResume"];
const DELAY_MS = 800;

let timer: ReturnType<typeof setTimeout> | undefined;

/**
 * Tự lưu liên tục: mỗi thay đổi nội dung dự án hẹn lưu sau `DELAY_MS` (gộp các thay đổi liên tiếp, vd. kéo clip).
 * Lúc ghi mới đọc file dự án hiện tại, nên chuyển/đóng dự án trong lúc chờ không ghi nhầm sang file khác; chưa có file thì bỏ qua.
 */
useEditor.subscribe((s, prev) => {
  if (!SAVED.some((k) => s[k] !== prev[k])) return;
  clearTimeout(timer);
  timer = setTimeout(autosaveProject, DELAY_MS);
});
