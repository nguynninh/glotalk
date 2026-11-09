//! Gom cụm phân cấp liên kết trung bình (average linkage) theo cosine.

/// `vectors` đã chuẩn hoá L2. Gộp dần hai cụm giống nhau nhất; dừng khi độ tương đồng trung bình
/// của cặp tốt nhất dưới `threshold`, hoặc khi còn `target` cụm (`target > 0` thì bỏ qua ngưỡng).
/// Trả về chỉ số cụm 0..k của từng vector.
pub fn agglomerate(vectors: &[Vec<f32>], threshold: f32, target: usize) -> Vec<usize> {
    let n = vectors.len();
    if n == 0 {
        return Vec::new();
    }
    let mut sim = vec![0.0f32; n * n];
    for i in 0..n {
        for j in i + 1..n {
            let s = dot(&vectors[i], &vectors[j]);
            sim[i * n + j] = s;
            sim[j * n + i] = s;
        }
    }
    let mut active = vec![true; n];
    let mut size = vec![1usize; n];
    let mut root: Vec<usize> = (0..n).collect();
    let nearest = |sim: &[f32], active: &[bool], i: usize| {
        (0..n).filter(|&j| j != i && active[j]).map(|j| (j, sim[i * n + j])).max_by(|a, b| a.1.total_cmp(&b.1))
    };
    // Láng giềng gần nhất của từng cụm; chỉ tính lại hàng nào trỏ vào cặp vừa gộp, vì tương đồng
    // trung bình với cụm mới không vượt quá giá trị lớn hơn trong hai cụm cũ.
    let mut best: Vec<Option<(usize, f32)>> = (0..n).map(|i| nearest(&sim, &active, i)).collect();
    let mut count = n;
    while count > target.max(1) {
        let Some((i, (j, s))) = (0..n).filter(|&i| active[i]).filter_map(|i| best[i].map(|b| (i, b))).max_by(|a, b| a.1 .1.total_cmp(&b.1 .1)) else {
            break;
        };
        if target == 0 && s < threshold {
            break;
        }
        let (ni, nj) = (size[i] as f32, size[j] as f32);
        for k in (0..n).filter(|&k| active[k] && k != i && k != j) {
            let merged = (ni * sim[i * n + k] + nj * sim[j * n + k]) / (ni + nj);
            sim[i * n + k] = merged;
            sim[k * n + i] = merged;
        }
        active[j] = false;
        size[i] += size[j];
        root.iter_mut().filter(|r| **r == j).for_each(|r| *r = i);
        count -= 1;
        for k in (0..n).filter(|&k| active[k]) {
            if k == i || best[k].is_some_and(|(b, _)| b == i || b == j) {
                best[k] = nearest(&sim, &active, k);
            }
        }
    }
    let mut ids: Vec<usize> = (0..n).filter(|&i| active[i]).collect();
    ids.sort_unstable();
    root.iter().map(|r| ids.binary_search(r).expect("gốc là cụm còn hoạt động")).collect()
}

pub fn dot(a: &[f32], b: &[f32]) -> f32 {
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}

pub fn normalize(v: &mut [f32]) {
    let norm = dot(v, v).sqrt();
    if norm > 0.0 {
        v.iter_mut().for_each(|x| *x /= norm);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn unit(v: &[f32]) -> Vec<f32> {
        let mut v = v.to_vec();
        normalize(&mut v);
        v
    }

    #[test]
    fn separates_and_targets() {
        let v = vec![unit(&[1.0, 0.1, 0.0]), unit(&[0.0, 1.0, 0.1]), unit(&[0.9, 0.2, 0.0]), unit(&[0.1, 1.0, 0.0]), unit(&[0.0, 0.0, 1.0])];
        let labels = agglomerate(&v, 0.8, 0);
        assert_eq!(labels[0], labels[2]);
        assert_eq!(labels[1], labels[3]);
        assert_ne!(labels[0], labels[1]);
        assert_ne!(labels[4], labels[0]);
        assert_ne!(labels[4], labels[1]);
        let two = agglomerate(&v, 0.8, 2);
        assert_eq!(two.iter().max(), Some(&1));
        assert_eq!(agglomerate(&v, 0.8, 1), vec![0; 5]);
    }
}
