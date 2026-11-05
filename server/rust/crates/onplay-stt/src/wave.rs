//! Incremental mono PCM16 16 kHz WAV reading; memory use is independent of length.
//! Accepts plain PCM and WAVE_FORMAT_EXTENSIBLE PCM16, like `wave_reader.hpp`.

use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom};
use std::path::Path;

use crate::error::{Error, Result};
use crate::pcm::pcm16_to_f32;

const PCM_GUID: [u8; 16] = [1, 0, 0, 0, 0, 0, 16, 0, 128, 0, 0, 170, 0, 56, 155, 113];

pub struct WaveReader {
    file: BufReader<File>,
    remaining: u64,
    samples: u64,
    bytes: Vec<u8>,
}

impl WaveReader {
    pub fn open(path: &Path) -> Result<Self> {
        let file = File::open(path).map_err(|_| Error::runtime("Cannot open WAV"))?;
        let length = file.metadata().map_err(|_| Error::runtime("Cannot open WAV"))?.len();
        let mut file = BufReader::new(file);
        let mut header = [0u8; 12];
        read(&mut file, &mut header)?;
        if &header[..4] != b"RIFF" || &header[8..] != b"WAVE" {
            return Err(Error::runtime("Expected RIFF WAV"));
        }
        let (mut format, mut data) = (false, None::<(u64, u64)>);
        let mut position = 12u64;
        while !format || data.is_none() {
            let mut chunk = [0u8; 8];
            read(&mut file, &mut chunk)?;
            let size = u64::from(u32::from_le_bytes([chunk[4], chunk[5], chunk[6], chunk[7]]));
            let start = position + 8;
            if start > length || size > length - start {
                return Err(Error::runtime("Truncated WAV chunk"));
            }
            match &chunk[..4] {
                b"fmt " => {
                    if size < 16 {
                        return Err(Error::runtime("Invalid WAV format"));
                    }
                    let mut fmt = [0u8; 40];
                    read(&mut file, &mut fmt[..size.min(40) as usize])?;
                    check_format(&fmt, size)?;
                    format = true;
                }
                b"data" => {
                    if size == 0 || size % 2 != 0 {
                        return Err(Error::runtime("Invalid WAV data size"));
                    }
                    data = Some((start, size));
                }
                _ => {}
            }
            position = start + size + (size & 1);
            file.seek(SeekFrom::Start(position)).map_err(|_| Error::runtime("Truncated WAV"))?;
        }
        let (offset, size) = data.expect("loop ends with data");
        file.seek(SeekFrom::Start(offset)).map_err(|_| Error::runtime("Truncated WAV"))?;
        Ok(Self { file, remaining: size, samples: size / 2, bytes: Vec::new() })
    }

    pub fn sample_count(&self) -> u64 {
        self.samples
    }

    /// Fills `out` from the current position; returns the number of samples read (0 at end).
    pub fn next(&mut self, out: &mut [f32]) -> Result<usize> {
        let count = (out.len() as u64).min(self.remaining / 2) as usize;
        self.bytes.resize(count * 2, 0);
        read(&mut self.file, &mut self.bytes)?;
        pcm16_to_f32(&self.bytes, out);
        self.remaining -= count as u64 * 2;
        Ok(count)
    }
}

fn check_format(fmt: &[u8; 40], size: u64) -> Result<()> {
    let u16_at = |at: usize| u16::from_le_bytes([fmt[at], fmt[at + 1]]);
    let u32_at = |at: usize| u32::from_le_bytes([fmt[at], fmt[at + 1], fmt[at + 2], fmt[at + 3]]);
    let pcm = u16_at(0) == 1
        || (u16_at(0) == 65534 && size >= 40 && u16_at(16) >= 22 && u16_at(18) == 16 && fmt[24..40] == PCM_GUID);
    if !pcm || u16_at(2) != 1 || u32_at(4) != 16_000 || u16_at(12) != 2 || u16_at(14) != 16 {
        return Err(Error::runtime("Expected mono PCM16 WAV at 16000 Hz"));
    }
    Ok(())
}

fn read(file: &mut impl Read, out: &mut [u8]) -> Result<()> {
    file.read_exact(out).map_err(|_| Error::runtime("Truncated WAV"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wav(fmt: &[u8], samples: &[i16]) -> Vec<u8> {
        let data: Vec<u8> = samples.iter().flat_map(|s| s.to_le_bytes()).collect();
        let mut out = b"RIFF".to_vec();
        out.extend_from_slice(&((4 + 8 + fmt.len() + 8 + data.len() + 12) as u32).to_le_bytes());
        out.extend_from_slice(b"WAVEfmt ");
        out.extend_from_slice(&(fmt.len() as u32).to_le_bytes());
        out.extend_from_slice(fmt);
        out.extend_from_slice(b"LIST\x04\0\0\0INFO"); // unknown chunk to skip
        out.extend_from_slice(b"data");
        out.extend_from_slice(&(data.len() as u32).to_le_bytes());
        out.extend_from_slice(&data);
        out
    }

    fn pcm_fmt(tag: u16, rate: u32) -> Vec<u8> {
        [&tag.to_le_bytes()[..], &1u16.to_le_bytes(), &rate.to_le_bytes(), &(rate * 2).to_le_bytes(), &2u16.to_le_bytes(), &16u16.to_le_bytes()].concat()
    }

    fn open(bytes: &[u8]) -> Result<WaveReader> {
        let path = std::env::temp_dir().join(format!("onplay-wave-{}-{:p}.wav", std::process::id(), bytes));
        std::fs::write(&path, bytes).unwrap();
        let reader = WaveReader::open(&path);
        std::fs::remove_file(path).unwrap();
        reader
    }

    #[test]
    fn reads_pcm_incrementally() {
        let mut reader = open(&wav(&pcm_fmt(1, 16_000), &[0, -32768, 32767])).unwrap();
        assert_eq!(reader.sample_count(), 3);
        let mut out = [9.0; 2];
        assert_eq!(reader.next(&mut out).unwrap(), 2);
        assert_eq!(out, [0.0, -1.0]);
        assert_eq!(reader.next(&mut out).unwrap(), 1);
        assert_eq!(reader.next(&mut out).unwrap(), 0);
    }

    #[test]
    fn accepts_extensible_pcm_and_rejects_other_formats() {
        let mut extensible = pcm_fmt(65534, 16_000);
        extensible.extend_from_slice(&22u16.to_le_bytes());
        extensible.extend_from_slice(&16u16.to_le_bytes());
        extensible.extend_from_slice(&4u32.to_le_bytes());
        extensible.extend_from_slice(&PCM_GUID);
        assert_eq!(open(&wav(&extensible, &[1, 2])).unwrap().sample_count(), 2);
        let error = open(&wav(&pcm_fmt(1, 44_100), &[1])).err().unwrap();
        assert_eq!(error.to_string(), "Expected mono PCM16 WAV at 16000 Hz");
        assert_eq!(open(b"RIFX0000WAVE").err().unwrap().to_string(), "Expected RIFF WAV");
    }

    /// Ported from tests/wave_reader.py: an 80 MB sparse file reads without loading it whole,
    /// and the same header with the data cut off is rejected.
    #[test]
    fn reads_large_files_and_rejects_truncation() {
        let path = std::env::temp_dir().join(format!("onplay-wave-large-{}.wav", std::process::id()));
        let mut header = wav(&pcm_fmt(1, 16_000), &[0, -32768, 32767]);
        header.drain(36..48); // drop the LIST chunk: plain 44-byte header
        header[4..8].copy_from_slice(&80_000_036u32.to_le_bytes());
        header[40..44].copy_from_slice(&80_000_000u32.to_le_bytes());
        std::fs::write(&path, &header).unwrap();
        std::fs::OpenOptions::new().write(true).open(&path).unwrap().set_len(80_000_044).unwrap();
        let mut reader = WaveReader::open(&path).unwrap();
        assert_eq!(reader.sample_count(), 40_000_000);
        let mut out = [0.0; 3];
        assert_eq!(reader.next(&mut out).unwrap(), 3);
        assert_eq!(out[..2], [0.0, -1.0]);
        assert!((out[2] - 32767.0 / 32768.0).abs() < 1e-6);
        std::fs::OpenOptions::new().write(true).open(&path).unwrap().set_len(44).unwrap();
        assert_eq!(WaveReader::open(&path).err().unwrap().to_string(), "Truncated WAV chunk");
        std::fs::remove_file(path).unwrap();
    }
}
