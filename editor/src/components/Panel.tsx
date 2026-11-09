import { ReactNode } from "react";

export function Panel<T extends string>({ tabs, active, onTab, actions, children, className = "" }: {
  tabs: { id: T; label: string }[];
  active: T;
  onTab: (id: T) => void;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      <header className="panel-tabs">
        {tabs.map((t) => (
          <button key={t.id} className={t.id === active ? "on" : ""} onClick={() => onTab(t.id)}>{t.label}</button>
        ))}
        <span className="grow" />
        {actions}
      </header>
      <div className="panel-body">{children}</div>
    </section>
  );
}

/** Thanh kéo để đổi kích thước panel. */
export function Splitter({ dir, onDrag }: { dir: "col" | "row"; onDrag: (delta: number) => void }) {
  return (
    <div
      className={`splitter ${dir}`}
      onPointerDown={(e) => {
        const el = e.currentTarget;
        el.setPointerCapture(e.pointerId);
        let last = dir === "col" ? e.clientX : e.clientY;
        const move = (ev: PointerEvent) => {
          const cur = dir === "col" ? ev.clientX : ev.clientY;
          onDrag(cur - last);
          last = cur;
        };
        const up = () => {
          el.removeEventListener("pointermove", move);
          el.removeEventListener("pointerup", up);
        };
        el.addEventListener("pointermove", move);
        el.addEventListener("pointerup", up);
      }}
    />
  );
}
