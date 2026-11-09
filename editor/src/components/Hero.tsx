import { useState } from "react";

const HERO_VIDEO = "https://website-assets-cdn.vtvlive.vn/vtvlive/common/videos/vtvlive.mp4";

/** Nửa phải màn hình đăng nhập/bắt đầu: video giới thiệu lặp lại; không tải được (vd. mất mạng) thì về logo và khẩu hiệu. */
export function Hero({ tagline }: { tagline: string }) {
  const [failed, setFailed] = useState(false);
  return (
    <section className="welcome-hero" data-tauri-drag-region>
      {failed ? (
        <>
          <img src="/logo.png" alt="" draggable={false} />
          <p>{tagline}</p>
        </>
      ) : (
        <video className="hero-video" src={HERO_VIDEO} autoPlay muted loop playsInline preload="auto" onError={() => setFailed(true)} />
      )}
    </section>
  );
}
