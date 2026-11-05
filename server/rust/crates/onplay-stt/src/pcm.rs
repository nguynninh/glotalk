//! PCM16 little-endian to float conversion, `value / 32768`, as the C++ readers do.

pub fn pcm16_to_f32(bytes: &[u8], out: &mut [f32]) -> usize {
    let count = (bytes.len() / 2).min(out.len());
    for (sample, pair) in out.iter_mut().zip(bytes.chunks_exact(2)).take(count) {
        *sample = f32::from(i16::from_le_bytes([pair[0], pair[1]])) / 32768.0;
    }
    count
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_extremes() {
        let mut out = [0.0; 3];
        assert_eq!(pcm16_to_f32(&[0, 0, 0, 0x80, 0xff, 0x7f], &mut out), 3);
        assert_eq!(out[..2], [0.0, -1.0]);
        assert!((out[2] - 32767.0 / 32768.0).abs() < 1e-6);
    }
}
