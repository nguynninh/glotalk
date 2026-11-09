import { ChromaKey } from "./timeline";

/** Một lớp hình cần vẽ, từ dưới lên trên. */
export interface Layer {
  source: HTMLVideoElement | HTMLImageElement;
  /** Kích thước gốc của nguồn */
  w: number;
  h: number;
  opacity: number;
  scale: number;
  /** Lệch tâm theo tỉ lệ khung hình sequence */
  x: number;
  y: number;
  key?: ChromaKey;
}

/** Hình chữ nhật nguồn sau khi vừa khung + scale + lệch, theo pixel sequence. */
export function layerRect(l: Pick<Layer, "w" | "h" | "scale" | "x" | "y">, W: number, H: number) {
  const fit = Math.min(W / l.w, H / l.h) * l.scale;
  const w = l.w * fit;
  const h = l.h * fit;
  return { x: (W - w) / 2 + l.x * W, y: (H - h) / 2 + l.y * H, w, h };
}

const VS = `
attribute vec2 a_pos;
uniform vec4 u_rect;   // x, y, w, h trong toạ độ clip 0..1 (gốc trên-trái)
varying vec2 v_uv;
void main() {
  v_uv = a_pos;
  vec2 p = u_rect.xy + a_pos * u_rect.zw;
  gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
}`;

// Khoá màu theo độ lệch sắc độ (UV của YUV), cách làm của chroma key trong OBS: không phụ thuộc độ sáng
// nên vùng phông bị đổ bóng vẫn bị xoá; spill kéo về xám phần viền còn ám màu khoá.
const FS = `
precision mediump float;
uniform sampler2D u_tex;
uniform float u_opacity;
uniform bool u_key;
uniform vec2 u_keyUV;
uniform float u_similarity;
uniform float u_smoothness;
uniform float u_spill;
varying vec2 v_uv;
vec2 toUV(vec3 c) {
  return vec2(-0.1146 * c.r - 0.3854 * c.g + 0.5 * c.b, 0.5 * c.r - 0.4542 * c.g - 0.0458 * c.b);
}
void main() {
  vec4 c = texture2D(u_tex, v_uv);
  float a = c.a;
  vec3 rgb = c.rgb;
  if (u_key) {
    float d = distance(toUV(rgb), u_keyUV);
    float base = d - u_similarity * 0.25;
    a *= pow(clamp(base / max(u_smoothness * 0.25, 0.0001), 0.0, 1.0), 1.5);
    float spill = pow(clamp(base / max(u_spill * 0.25, 0.0001), 0.0, 1.0), 1.5);
    float gray = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(vec3(gray), rgb, spill);
  }
  a *= u_opacity;
  gl_FragColor = vec4(rgb * a, a);
}`;

const hexRgb = (hex: string) => {
  const n = parseInt(hex.replace("#", ""), 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
};
const keyUV = (hex: string): [number, number] => {
  const [r, g, b] = hexRgb(hex);
  return [-0.1146 * r - 0.3854 * g + 0.5 * b, 0.5 * r - 0.4542 * g - 0.0458 * b];
};

/**
 * Ghép các lớp video/ảnh lên canvas bằng WebGL (có phông xanh). Nếu không tải được nguồn vào texture
 * (WebGL không có, hoặc nguồn bị coi là khác origin) thì chuyển sang Canvas 2D: vẫn ghép lớp, độ mờ,
 * vị trí, nhưng không khoá màu được.
 */
export class Compositor {
  private gl: WebGLRenderingContext | null = null;
  private ctx2d: CanvasRenderingContext2D | null = null;
  private textures = new WeakMap<object, WebGLTexture>();
  private loc: Record<string, WebGLUniformLocation | null> = {};
  /** Lỗi kỹ thuật khiến phải rơi về Canvas 2D (panel hiển thị qua i18n). */
  fallbackReason = "";

  private canvas: HTMLCanvasElement;

  /** Tự tạo canvas bên trong `host` (React không quản lý phần tử này nên thay canvas khi rơi về 2D là an toàn). */
  constructor(host: HTMLElement) {
    const canvas = document.createElement("canvas");
    host.appendChild(canvas);
    this.canvas = canvas;
    const gl = canvas.getContext("webgl", { premultipliedAlpha: true, alpha: false, preserveDrawingBuffer: true });
    if (!gl) {
      this.useCanvas2d("WebGL unavailable");
      return;
    }
    this.gl = gl;
    const sh = (type: number, src: string) => {
      const s = gl.createShader(type)!;
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? "shader");
      return s;
    };
    const prog = gl.createProgram()!;
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, VS));
    gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, FS));
    gl.linkProgram(prog);
    gl.useProgram(prog);
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1]), gl.STATIC_DRAW);
    const ap = gl.getAttribLocation(prog, "a_pos");
    gl.enableVertexAttribArray(ap);
    gl.vertexAttribPointer(ap, 2, gl.FLOAT, false, 0, 0);
    for (const n of ["u_rect", "u_tex", "u_opacity", "u_key", "u_keyUV", "u_similarity", "u_smoothness", "u_spill"])
      this.loc[n] = gl.getUniformLocation(prog, n);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
  }

  private useCanvas2d(reason: string) {
    this.fallbackReason = reason;
    this.gl = null;
    // Canvas đã có context WebGL thì không lấy được 2D nữa; thay bằng canvas mới.
    const fresh = document.createElement("canvas");
    fresh.width = this.canvas.width;
    fresh.height = this.canvas.height;
    fresh.style.visibility = this.canvas.style.visibility;
    this.canvas.replaceWith(fresh);
    this.canvas = fresh;
    this.ctx2d = fresh.getContext("2d");
  }

  private visible = true;
  /** Ẩn canvas khi Program hiển thị thẳng thẻ <video> (chế độ nhanh). */
  setVisible(v: boolean) {
    if (v === this.visible) return;
    this.visible = v;
    this.canvas.style.visibility = v ? "" : "hidden";
  }

  render(layers: Layer[], W: number, H: number) {
    if (this.canvas.width !== W || this.canvas.height !== H) {
      this.canvas.width = W;
      this.canvas.height = H;
    }
    if (this.gl) {
      try {
        this.renderGl(layers, W, H);
        return;
      } catch (e) {
        this.useCanvas2d(String(e));
      }
    }
    this.render2d(layers, W, H);
  }

  private renderGl(layers: Layer[], W: number, H: number) {
    const gl = this.gl!;
    gl.viewport(0, 0, W, H);
    gl.clearColor(0, 0, 0, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);
    for (const l of layers) {
      let tex = this.textures.get(l.source);
      if (!tex) {
        tex = gl.createTexture()!;
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        this.textures.set(l.source, tex);
      }
      gl.bindTexture(gl.TEXTURE_2D, tex);
      // Ném SecurityError nếu nguồn khác origin không có CORS: render() bắt và chuyển sang 2D.
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, l.source);
      const r = layerRect(l, W, H);
      gl.uniform4f(this.loc.u_rect, r.x / W, r.y / H, r.w / W, r.h / H);
      gl.uniform1i(this.loc.u_tex, 0);
      gl.uniform1f(this.loc.u_opacity, l.opacity);
      const key = l.key?.enabled ? l.key : null;
      gl.uniform1i(this.loc.u_key, key ? 1 : 0);
      if (key) {
        gl.uniform2f(this.loc.u_keyUV, ...keyUV(key.color));
        gl.uniform1f(this.loc.u_similarity, key.similarity);
        gl.uniform1f(this.loc.u_smoothness, key.smoothness);
        gl.uniform1f(this.loc.u_spill, key.spill);
      }
      gl.drawArrays(gl.TRIANGLES, 0, 6);
    }
  }

  private render2d(layers: Layer[], W: number, H: number) {
    const g = this.ctx2d;
    if (!g) return;
    g.globalAlpha = 1;
    g.fillStyle = "#000";
    g.fillRect(0, 0, W, H);
    for (const l of layers) {
      const r = layerRect(l, W, H);
      g.globalAlpha = l.opacity;
      g.drawImage(l.source, r.x, r.y, r.w, r.h);
    }
    g.globalAlpha = 1;
  }

  dispose() {
    this.gl?.getExtension("WEBGL_lose_context")?.loseContext();
    this.canvas.remove();
  }
}

/** Đọc màu nguồn tại một điểm (dùng cho chọn màu khoá). Trả về null nếu nguồn bị chặn đọc. */
export function samplePixel(source: HTMLVideoElement | HTMLImageElement, u: number, v: number): string | null {
  const c = document.createElement("canvas");
  c.width = c.height = 1;
  const g = c.getContext("2d")!;
  const sw = source instanceof HTMLVideoElement ? source.videoWidth : source.naturalWidth;
  const sh = source instanceof HTMLVideoElement ? source.videoHeight : source.naturalHeight;
  try {
    g.drawImage(source, Math.floor(u * sw), Math.floor(v * sh), 1, 1, 0, 0, 1, 1);
    const [r, gr, b] = g.getImageData(0, 0, 1, 1).data;
    return "#" + [r, gr, b].map((n) => n.toString(16).padStart(2, "0")).join("");
  } catch {
    return null;
  }
}
