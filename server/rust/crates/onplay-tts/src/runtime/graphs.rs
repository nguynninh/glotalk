//! Typed wrappers over the four ONNX sessions; the rest of the crate never touches `ort`.
//!
//! Input and output names and shapes come from the shipped graphs:
//! - duration_predictor: text_ids (1,T) i64, style_dp (1,8,16), text_mask (1,1,T) -> duration (1)
//! - text_encoder: text_ids, style_ttl (1,R,256), text_mask -> text_emb (1,256,T)
//! - vector_estimator: noisy_latent (1,C,L), text_emb, style_ttl, latent_mask (1,1,L),
//!   text_mask, current_step (1), total_step (1) -> denoised_latent (1,C,L)
//! - vocoder: latent (1,C,L) -> wav_tts (1,N)

use ort::session::{Session, SessionOutputs};
use ort::value::Tensor as OrtTensor;

use super::Device;
use crate::assets::{Assets, DURATION_PREDICTOR, TEXT_ENCODER, VECTOR_ESTIMATOR, VOCODER};
use crate::error::{Error, Result};
use crate::tensor::{Latent, Tensor};
use crate::voice::VoiceStyle;

pub struct Graphs {
    duration_predictor: Session,
    text_encoder: Session,
    vector_estimator: Session,
    vocoder: Session,
}

impl Graphs {
    pub fn load(assets: &Assets, device: Device, threads: Option<usize>) -> Result<Self> {
        let open = |name: &str| -> Result<Session> {
            let mut builder = Session::builder()?.with_execution_providers(device.providers())?;
            if let Some(threads) = threads {
                builder = builder.with_intra_threads(threads)?;
            }
            Ok(builder.commit_from_file(assets.onnx(name))?)
        };
        Ok(Self {
            duration_predictor: open(DURATION_PREDICTOR)?,
            text_encoder: open(TEXT_ENCODER)?,
            vector_estimator: open(VECTOR_ESTIMATOR)?,
            vocoder: open(VOCODER)?,
        })
    }

    /// Predicted utterance duration in seconds, before the speed factor.
    pub fn duration(&mut self, ids: &[i64], mask: &[f32], voice: &VoiceStyle) -> Result<f32> {
        let outputs = self.duration_predictor.run(ort::inputs![
            "text_ids" => ids_tensor(ids)?,
            "style_dp" => style_tensor(&voice.dp)?,
            "text_mask" => mask_tensor(mask)?,
        ])?;
        let (_, data) = extract(&outputs, "duration", Some(1))?;
        Ok(data[0])
    }

    /// Text embedding (1, 256, T) as a flat buffer with its shape.
    pub fn encode_text(&mut self, ids: &[i64], mask: &[f32], voice: &VoiceStyle) -> Result<Tensor> {
        let outputs = self.text_encoder.run(ort::inputs![
            "text_ids" => ids_tensor(ids)?,
            "style_ttl" => style_tensor(&voice.ttl)?,
            "text_mask" => mask_tensor(mask)?,
        ])?;
        let (dims, data) = extract(&outputs, "text_emb", None)?;
        Ok(Tensor { dims, data: data.to_vec() })
    }

    /// One flow-matching step; returns the updated latent values.
    #[allow(clippy::too_many_arguments)]
    pub fn estimate(
        &mut self,
        latent: &Latent,
        text_emb: &Tensor,
        voice: &VoiceStyle,
        text_mask: &[f32],
        step: usize,
        total_steps: usize,
    ) -> Result<Vec<f32>> {
        let outputs = self.vector_estimator.run(ort::inputs![
            "noisy_latent" => latent_tensor(latent)?,
            "text_emb" => OrtTensor::from_array((to_i64(&text_emb.dims), text_emb.data.clone()))?,
            "style_ttl" => style_tensor(&voice.ttl)?,
            "latent_mask" => OrtTensor::from_array(([1i64, 1, latent.frames as i64], latent.mask.clone()))?,
            "text_mask" => mask_tensor(text_mask)?,
            "current_step" => OrtTensor::from_array(([1i64], vec![step as f32]))?,
            "total_step" => OrtTensor::from_array(([1i64], vec![total_steps as f32]))?,
        ])?;
        let (_, data) = extract(&outputs, "denoised_latent", Some(latent.data.len()))?;
        Ok(data.to_vec())
    }

    /// Waveform samples for a latent.
    pub fn vocode(&mut self, latent: &Latent) -> Result<Vec<f32>> {
        let outputs = self.vocoder.run(ort::inputs!["latent" => latent_tensor(latent)?])?;
        let (_, data) = extract(&outputs, "wav_tts", None)?;
        Ok(data.to_vec())
    }
}

fn ids_tensor(ids: &[i64]) -> Result<OrtTensor<i64>> {
    Ok(OrtTensor::from_array(([1i64, ids.len() as i64], ids.to_vec()))?)
}

fn mask_tensor(mask: &[f32]) -> Result<OrtTensor<f32>> {
    Ok(OrtTensor::from_array(([1i64, 1, mask.len() as i64], mask.to_vec()))?)
}

fn style_tensor(style: &Tensor) -> Result<OrtTensor<f32>> {
    Ok(OrtTensor::from_array((to_i64(&style.dims), style.data.clone()))?)
}

fn latent_tensor(latent: &Latent) -> Result<OrtTensor<f32>> {
    Ok(OrtTensor::from_array(([1i64, latent.channels as i64, latent.frames as i64], latent.data.clone()))?)
}

fn to_i64(dims: &[usize]) -> Vec<i64> {
    dims.iter().map(|d| *d as i64).collect()
}

fn extract<'a>(outputs: &'a SessionOutputs, name: &str, len: Option<usize>) -> Result<(Vec<usize>, &'a [f32])> {
    let value = outputs.get(name).ok_or_else(|| Error::ModelOutput(format!("missing {name}")))?;
    let (shape, data) = value.try_extract_tensor::<f32>()?;
    if len.is_some_and(|len| len != data.len()) || data.is_empty() {
        return Err(Error::ModelOutput(format!("{name} has {} values", data.len())));
    }
    Ok((shape.iter().map(|d| *d as usize).collect(), data))
}
