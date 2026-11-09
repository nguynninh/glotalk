import { core } from "./core";
import { panels } from "./panels";

/** Mọi khoá dịch; tách riêng để zh.ts/ja.ts dùng mà không import vòng qua index.ts. */
export type TKey = keyof typeof core.vi | keyof typeof panels.vi;
