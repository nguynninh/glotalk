import { CSSProperties, ReactNode } from "react";

export function Check({ checked, onChange, children }: { checked: boolean; onChange: (v: boolean) => void; children: ReactNode }) {
  return (
    <label className="inline">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      {children}
    </label>
  );
}

export function Num({ value, onChange, step = 0.1, min, max }: {
  value: number; onChange: (v: number) => void; step?: number; min?: number; max?: number;
}) {
  return <input className="num" type="number" value={value} step={step} min={min} max={max} onChange={(e) => onChange(Number(e.target.value))} />;
}

export function ColorBtn({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <span className="color-btn">
      <input type="color" value={value} onChange={(e) => onChange(e.target.value)} />
      <code>{value.toUpperCase()}</code>
    </span>
  );
}

/** Một dòng thuộc tính kiểu Effect Controls: nhãn bên trái, control bên phải. */
export function Prop({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="prop">
      <span>{label}</span>
      <div>{children}</div>
    </div>
  );
}

/** Nhóm thuộc tính gập/mở, có thể có công tắc bật/tắt (fx). */
export function PropGroup({ title, enabled, onToggle, children, defaultOpen = true }: {
  title: string; enabled?: boolean; onToggle?: (v: boolean) => void; children: ReactNode; defaultOpen?: boolean;
}) {
  return (
    <details className="prop-group" open={defaultOpen}>
      <summary>
        {onToggle && (
          <input type="checkbox" checked={!!enabled} onClick={(e) => e.stopPropagation()} onChange={(e) => onToggle(e.target.checked)} />
        )}
        <span>{title}</span>
      </summary>
      <div className={`prop-body ${onToggle && !enabled ? "dim" : ""}`}>{children}</div>
    </details>
  );
}

/** Thanh trượt kiểu Lumetri: giá trị canh phải, nhấp đúp để về mặc định, `track` tô nền dải trượt. */
export function Slider({ value, onChange, min, max, step = 1, def, format, track, title }: {
  value: number; onChange: (v: number) => void; min: number; max: number; step?: number; def: number;
  format?: (v: number) => string; track?: string; title?: string;
}) {
  return (
    <div className="slider-row" title={title}>
      <input type="range" min={min} max={max} step={step} value={value}
        style={{ "--track": track ?? "var(--tl-tick)" } as CSSProperties}
        onChange={(e) => onChange(Number(e.target.value))} onDoubleClick={() => onChange(def)} />
      <span className="slider-val">{format ? format(value) : value}</span>
    </div>
  );
}
