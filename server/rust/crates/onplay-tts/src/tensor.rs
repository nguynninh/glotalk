//! Plain float32 buffers exchanged between the pipeline stages and the ONNX graphs.

/// A row-major float32 tensor with its shape.
#[derive(Debug, Clone, PartialEq)]
pub struct Tensor {
    pub dims: Vec<usize>,
    pub data: Vec<f32>,
}

/// A (1, channels, frames) latent with its (1, 1, frames) mask.
#[derive(Debug, Clone, PartialEq)]
pub struct Latent {
    pub channels: usize,
    pub frames: usize,
    pub data: Vec<f32>,
    pub mask: Vec<f32>,
}
