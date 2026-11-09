//! Gộp kết quả OCR từng frame thành các câu phụ đề có thời gian.
use serde::Serialize;
use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct Sample {
    pub t: u64,
    pub text: String,
    pub conf: f32,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct OcrSegment {
    pub start: u64,
    pub end: u64,
    pub text: String,
    pub conf: f32,
}

#[derive(Debug, Clone, Copy)]
pub struct MergeOptions {
    /// Khoảng cách giữa 2 mẫu (ms)
    pub interval: u64,
    /// Độ giống tối thiểu để coi là cùng một câu
    pub min_similarity: f64,
    /// Số mẫu trống liên tiếp tối đa vẫn gộp (OCR đọc sót)
    pub max_gap: usize,
    /// Bỏ câu ngắn hơn (ms)
    pub min_duration: u64,
    pub min_conf: f32,
}

impl MergeOptions {
    pub fn for_fps(fps: f64) -> Self {
        let interval = (1000.0 / fps).round() as u64;
        // 200ms cố định: ở 10 mẫu/giây câu chỉ thấy 1 mẫu (nháy) bị bỏ, câu thấy 2 mẫu vẫn giữ;
        // ở tốc độ quét thấp (vd 3/giây) câu 1 mẫu vẫn được giữ.
        Self { interval, min_similarity: 0.7, max_gap: 1, min_duration: 200, min_conf: 0.6 }
    }
}

pub fn normalize(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Độ giống 0..1 theo khoảng cách Levenshtein trên ký tự.
pub fn similarity(a: &str, b: &str) -> f64 {
    let a: Vec<char> = a.chars().collect();
    let b: Vec<char> = b.chars().collect();
    if a.is_empty() && b.is_empty() {
        return 1.0;
    }
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    let mut cur = vec![0; b.len() + 1];
    for i in 1..=a.len() {
        cur[0] = i;
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            cur[j] = (prev[j] + 1).min(cur[j - 1] + 1).min(prev[j - 1] + cost);
        }
        std::mem::swap(&mut prev, &mut cur);
    }
    1.0 - prev[b.len()] as f64 / a.len().max(b.len()) as f64
}

struct Group {
    first: u64,
    last: u64,
    variants: HashMap<String, (usize, f32)>,
}

impl Group {
    fn new(s: &Sample) -> Self {
        let mut variants = HashMap::new();
        variants.insert(s.text.clone(), (1, s.conf));
        Self { first: s.t, last: s.t, variants }
    }
    fn add(&mut self, s: &Sample) {
        let e = self.variants.entry(s.text.clone()).or_insert((0, 0.0));
        e.0 += 1;
        e.1 += s.conf;
        self.last = s.t;
    }
    /// Bản đọc xuất hiện nhiều nhất (hoà thì lấy bản có tổng độ tin cậy cao hơn).
    fn best(&self) -> (&str, f32) {
        let (text, (n, sum)) = self
            .variants
            .iter()
            .max_by(|a, b| a.1 .0.cmp(&b.1 .0).then(a.1 .1.total_cmp(&b.1 .1)))
            .expect("nhóm không rỗng");
        (text, sum / *n as f32)
    }
}

pub fn merge(samples: &[Sample], o: MergeOptions) -> Vec<OcrSegment> {
    let half = o.interval / 2;
    let mut out: Vec<OcrSegment> = Vec::new();
    let mut group: Option<Group> = None;
    let mut gap = 0usize;

    let close = |g: Group, out: &mut Vec<OcrSegment>| {
        let (text, conf) = g.best();
        // Mốc thật nằm giữa 2 mẫu nên lùi/tiến nửa khoảng để sai số tối đa chỉ còn nửa khoảng.
        let start = g.first.saturating_sub(half);
        let mut end = g.last + o.interval - half;
        if end - start < o.min_duration {
            return;
        }
        if let Some(prev) = out.last_mut() {
            if prev.end > start {
                // Hai câu nối liền: chia đôi phần chồng lấn.
                let mid = (prev.end + start) / 2;
                prev.end = mid;
                end = end.max(mid + 1);
                out.push(OcrSegment { start: mid, end, text: text.to_string(), conf });
                return;
            }
        }
        out.push(OcrSegment { start, end, text: text.to_string(), conf });
    };

    for s in samples {
        let text = normalize(&s.text);
        let valid = !text.is_empty() && s.conf >= o.min_conf;
        match (&mut group, valid) {
            (Some(g), true) if similarity(&text, g.best().0) >= o.min_similarity => {
                g.add(&Sample { text, ..*s });
                gap = 0;
            }
            (_, true) => {
                if let Some(g) = group.take() {
                    close(g, &mut out);
                }
                group = Some(Group::new(&Sample { text, ..*s }));
                gap = 0;
            }
            (Some(_), false) => {
                gap += 1;
                if gap > o.max_gap {
                    close(group.take().expect("có nhóm"), &mut out);
                    gap = 0;
                }
            }
            (None, false) => {}
        }
    }
    if let Some(g) = group {
        close(g, &mut out);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn samples(fps: f64, seq: &[&str]) -> Vec<Sample> {
        seq.iter()
            .enumerate()
            .map(|(i, t)| Sample { t: (i as f64 * 1000.0 / fps) as u64, text: t.to_string(), conf: 0.95 })
            .collect()
    }

    #[test]
    fn similarity_basics() {
        assert_eq!(similarity("abc", "abc"), 1.0);
        assert!(similarity("我只是想保护你", "我只是想保护") > 0.8);
        assert!(similarity("别再骗我了", "跟我走吧") < 0.3);
    }

    #[test]
    fn merges_runs_tolerates_ocr_noise_and_gaps() {
        // 10 mẫu/giây: câu A (có 1 frame đọc sai + 1 frame trống), rồi trống, rồi câu B nối liền câu C.
        let seq = ["", "AAAAAA", "AAAAAA", "AAAAAB", "", "AAAAAA", "", "", "BBBBBB", "BBBBBB", "CCCCCC", "CCCCCC", ""];
        let segs = merge(&samples(10.0, &seq), MergeOptions::for_fps(10.0));
        let texts: Vec<_> = segs.iter().map(|s| s.text.as_str()).collect();
        assert_eq!(texts, ["AAAAAA", "BBBBBB", "CCCCCC"]);
        assert_eq!((segs[0].start, segs[0].end), (50, 550));
        assert_eq!(segs[1].end, segs[2].start, "câu nối liền không chồng nhau");
    }

    #[test]
    fn drops_low_confidence_and_too_short() {
        let mut s = samples(10.0, &["", "XXXX", "", "", "YYYY", "YYYY", "YYYY"]);
        s[4].conf = 0.2;
        let segs = merge(&s, MergeOptions::for_fps(10.0));
        assert_eq!(segs.len(), 1);
        assert_eq!(segs[0].text, "YYYY");
    }
}
