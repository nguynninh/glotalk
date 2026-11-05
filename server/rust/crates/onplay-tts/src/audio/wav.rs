//! Mono PCM16 WAV output, matching `ui/tts.py` (clip to [-1, 1], scale by 32767).

use std::path::Path;

use crate::error::{Error, Result};

pub fn encode_pcm16_wav(samples: &[f32], sample_rate: u32) -> Result<Vec<u8>> {
    let data_len = u32::try_from(samples.len() * 2)
        .ok()
        .filter(|len| *len <= u32::MAX - 36)
        .ok_or_else(|| Error::InvalidArgument("audio too long for WAV".into()))?;
    let mut out = Vec::with_capacity(44 + data_len as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data_len).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes()); // PCM
    out.extend_from_slice(&1u16.to_le_bytes()); // mono
    out.extend_from_slice(&sample_rate.to_le_bytes());
    out.extend_from_slice(&(sample_rate * 2).to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data_len.to_le_bytes());
    for sample in samples {
        // numpy: clip(samples * 32767, -32768, 32767).astype(int16) truncates toward zero.
        out.extend_from_slice(&((sample * 32767.0).clamp(-32768.0, 32767.0) as i16).to_le_bytes());
    }
    Ok(out)
}

pub fn write_pcm16_wav(path: &Path, samples: &[f32], sample_rate: u32) -> Result<()> {
    let bytes = encode_pcm16_wav(samples, sample_rate)?;
    std::fs::write(path, bytes).map_err(|source| Error::Io { path: path.into(), source })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_header_and_clipped_samples() {
        let wav = encode_pcm16_wav(&[0.0, 1.5, -1.5, 0.5], 44100).unwrap();
        assert_eq!(wav.len(), 44 + 8);
        assert_eq!(&wav[..4], b"RIFF");
        assert_eq!(u32::from_le_bytes(wav[24..28].try_into().unwrap()), 44100);
        let samples: Vec<i16> = wav[44..].chunks(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect();
        assert_eq!(samples, [0, 32767, -32768, 16383]);
    }
}
