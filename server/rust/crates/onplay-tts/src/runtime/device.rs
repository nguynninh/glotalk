//! Execution provider selection.

use ort::execution_providers::coreml::{CoreMLComputeUnits, CoreMLModelFormat};
use ort::execution_providers::{CoreMLExecutionProvider, ExecutionProviderDispatch};

use crate::error::{Error, Result};

/// Where the graphs run. CoreML falls back to the CPU for nodes it cannot place.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Device {
    #[default]
    Cpu,
    /// CoreML with every compute unit (CPU, GPU, Neural Engine).
    CoreMl,
    CoreMlGpu,
    CoreMlNeuralEngine,
}

impl Device {
    pub fn parse(name: &str) -> Result<Self> {
        match name {
            "cpu" => Ok(Self::Cpu),
            "coreml" => Ok(Self::CoreMl),
            "coreml-gpu" => Ok(Self::CoreMlGpu),
            "coreml-ane" => Ok(Self::CoreMlNeuralEngine),
            _ => Err(Error::InvalidArgument(format!("unknown device '{name}' (cpu, coreml, coreml-gpu, coreml-ane)"))),
        }
    }

    pub(super) fn providers(self) -> Vec<ExecutionProviderDispatch> {
        let units = match self {
            Self::Cpu => return Vec::new(),
            Self::CoreMl => CoreMLComputeUnits::All,
            Self::CoreMlGpu => CoreMLComputeUnits::CPUAndGPU,
            Self::CoreMlNeuralEngine => CoreMLComputeUnits::CPUAndNeuralEngine,
        };
        let provider = CoreMLExecutionProvider::default()
            .with_compute_units(units)
            .with_model_format(CoreMLModelFormat::NeuralNetwork)
            .build()
            .error_on_failure();
        vec![provider]
    }
}
