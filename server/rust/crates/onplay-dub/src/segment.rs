/// One diarized utterance, in seconds from the start of the video.
#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    pub start: f64,
    pub end: f64,
    /// 1-based speaker label from `onplay-speakers`, consistent inside one audio piece.
    pub speaker: u32,
    pub text: String,
    pub translation: String,
    /// False when the source is already in the target language: keep the original audio.
    pub dub: bool,
}
