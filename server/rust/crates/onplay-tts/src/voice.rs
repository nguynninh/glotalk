//! Voice style embeddings (speaker identity) from `voice_styles/*.json`.
//!
//! Each file holds `style_ttl` (1, 50, 256) for the text encoder and vector estimator and
//! `style_dp` (1, 8, 16) for the duration predictor, as `{"data": nested-or-flat, "dims": [...]}`.

use std::path::Path;

use serde::Deserialize;
use serde_json::Value;

use crate::assets::read_json;
use crate::error::{Error, Result};
use crate::tensor::Tensor;

#[derive(Debug, Clone, PartialEq)]
pub struct VoiceStyle {
    pub name: String,
    pub ttl: Tensor,
    pub dp: Tensor,
}

impl VoiceStyle {
    pub fn load(path: &Path) -> Result<Self> {
        let raw: Raw = read_json(path)?;
        let invalid = |reason: String| Error::InvalidAsset { path: path.into(), reason };
        let ttl = tensor(raw.style_ttl).map_err(|reason| invalid(format!("style_ttl: {reason}")))?;
        let dp = tensor(raw.style_dp).map_err(|reason| invalid(format!("style_dp: {reason}")))?;
        if ttl.dims.len() != 3 || ttl.dims[0] != 1 || dp.dims.len() != 3 || dp.dims[0] != 1 {
            return Err(invalid("style tensors must be rank 3 with batch 1".into()));
        }
        let name = path.file_stem().and_then(|stem| stem.to_str()).unwrap_or_default().to_owned();
        Ok(Self { name, ttl, dp })
    }
}

#[derive(Deserialize)]
struct Raw {
    style_ttl: RawTensor,
    style_dp: RawTensor,
}

#[derive(Deserialize)]
struct RawTensor {
    dims: Vec<usize>,
    data: Value,
}

fn tensor(raw: RawTensor) -> Result<Tensor, String> {
    let mut data = Vec::with_capacity(raw.dims.iter().product());
    flatten(&raw.data, &mut data)?;
    if data.len() != raw.dims.iter().product::<usize>() {
        return Err(format!("{} values for dims {:?}", data.len(), raw.dims));
    }
    Ok(Tensor { dims: raw.dims, data })
}

/// Accepts nested `[1][R][C]` or flat number arrays, like `np.asarray(...).reshape(dims)`.
fn flatten(value: &Value, out: &mut Vec<f32>) -> Result<(), String> {
    match value {
        Value::Array(items) => items.iter().try_for_each(|item| flatten(item, out)),
        Value::Number(number) => {
            out.push(number.as_f64().ok_or("non-finite number")? as f32);
            Ok(())
        }
        _ => Err("expected numbers".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nested_and_flat_data_match() {
        let nested = tensor(RawTensor { dims: vec![1, 2, 2], data: serde_json::json!([[[1, 2], [3, 4]]]) });
        let flat = tensor(RawTensor { dims: vec![1, 2, 2], data: serde_json::json!([1, 2, 3, 4]) });
        assert_eq!(nested, flat);
    }

    #[test]
    fn rejects_wrong_element_count() {
        assert!(tensor(RawTensor { dims: vec![1, 2, 2], data: serde_json::json!([1, 2, 3]) }).is_err());
    }
}
