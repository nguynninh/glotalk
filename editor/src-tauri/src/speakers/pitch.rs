//! Cao độ giọng (F0) bằng thuật toán YIN, để đoán giọng nam/nữ của một nhân vật.
//!
//! YIN thay cho tự tương quan thuần: với giọng có hoạ âm bậc 2 mạnh, tự tương quan hay chọn nửa chu
//! kỳ và đoán cao lên một quãng tám (giọng nam ra ~240 Hz trên file mẫu bốn người nói).

/// Hạ xuống 8 kHz (trung bình từng cặp mẫu) cho nhanh; F0 giọng nói dưới 400 Hz nên không mất gì.
const RATE: f32 = 8_000.0;
const FRAME: usize = 320;
const HOP: usize = 320;
const MIN_LAG: usize = 20; // 400 Hz
const MAX_LAG: usize = 134; // ~60 Hz
/// Ngưỡng hiệu chuẩn hoá tích luỹ của YIN; khung không có độ trễ nào dưới mức này là vô thanh.
const YIN_THRESHOLD: f32 = 0.15;

/// F0 của các khung hữu thanh trong `samples` (16 kHz), theo Hz.
pub fn voiced_f0(samples: &[f32]) -> Vec<f32> {
    let x: Vec<f32> = samples.chunks_exact(2).map(|c| (c[0] + c[1]) * 0.5).collect();
    let rms = |f: &[f32]| (f.iter().map(|v| v * v).sum::<f32>() / f.len().max(1) as f32).sqrt();
    let loud = {
        let mut levels: Vec<f32> = x.chunks(FRAME).map(rms).collect();
        levels.sort_by(|a, b| a.total_cmp(b));
        levels.get(levels.len() * 3 / 4).copied().unwrap_or(0.0) * 0.3
    };
    let mut out = Vec::new();
    let mut d = vec![0.0f32; MAX_LAG + 1];
    for start in (0..x.len().saturating_sub(FRAME + MAX_LAG)).step_by(HOP) {
        let frame = &x[start..start + FRAME];
        if rms(frame) < loud.max(1e-3) {
            continue;
        }
        // Hàm hiệu d(τ), rồi chuẩn hoá theo trung bình tích luỹ: d'(τ) = d(τ)·τ / Σ_{1..τ} d.
        let mut running = 0.0f32;
        for tau in 1..=MAX_LAG {
            let diff: f32 = frame.iter().zip(&x[start + tau..start + tau + FRAME]).map(|(a, b)| (a - b) * (a - b)).sum();
            running += diff;
            d[tau] = if running > 0.0 { diff * tau as f32 / running } else { 1.0 };
        }
        let Some(mut tau) = (MIN_LAG..=MAX_LAG).find(|&t| d[t] < YIN_THRESHOLD) else { continue };
        while tau < MAX_LAG && d[tau + 1] < d[tau] {
            tau += 1;
        }
        out.push(RATE / tau as f32);
    }
    out
}

pub fn median(mut values: Vec<f32>) -> Option<f32> {
    if values.is_empty() {
        return None;
    }
    values.sort_by(|a, b| a.total_cmp(b));
    Some(values[values.len() / 2])
}

/// Ngưỡng theo F0 trung vị: nam thường 85–155 Hz, nữ 165–255 Hz; khoảng giữa không đoán.
pub fn gender(f0: f32) -> Option<&'static str> {
    if f0 < 150.0 {
        Some("male")
    } else if f0 > 170.0 {
        Some("female")
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn voice(hz: f32) -> Vec<f32> {
        // Âm có hoạ âm như giọng người, không phải sin thuần.
        (0..16_000)
            .map(|i| {
                let t = i as f32 / 16_000.0;
                (1..6).map(|h| (2.0 * std::f32::consts::PI * hz * h as f32 * t).sin() / h as f32).sum::<f32>() * 0.3
            })
            .collect()
    }

    #[test]
    fn estimates_pitch() {
        let low = median(voiced_f0(&voice(120.0))).unwrap();
        let high = median(voiced_f0(&voice(220.0))).unwrap();
        assert!((low - 120.0).abs() < 6.0, "{low}");
        assert!((high - 220.0).abs() < 10.0, "{high}");
        assert_eq!(gender(low), Some("male"));
        assert_eq!(gender(high), Some("female"));
        assert!(voiced_f0(&vec![0.0; 16_000]).is_empty());
    }
}
