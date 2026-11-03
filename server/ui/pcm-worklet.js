class PCMStream extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.buffer = new Int16Array(options.processorOptions.chunkMs * 16);
    this.count = 0;
    this.active = false;
    this.port.onmessage = ({data}) => {
      if (data === 'start') { this.count = 0; this.active = true; }
      if (data === 'pause') this.active = false;
      if (data === 'resume') this.active = true;
      if (data === 'flush') {
        this.active = false;
        this.send();
        this.port.postMessage({type:'flushed'});
      }
    };
  }
  send() {
    if (!this.count) return;
    const pcm = this.buffer.slice(0, this.count).buffer;
    this.port.postMessage({type:'pcm', pcm}, [pcm]);
    this.count = 0;
  }
  process(inputs, outputs) {
    const channels = inputs[0];
    // Always pass audio through, including when the user listens outside recognition.
    if (channels.length) {
      for (let c = 0; c < outputs[0].length; c++) outputs[0][c].set(channels[Math.min(c, channels.length - 1)]);
      if (this.active) for (let i = 0; i < channels[0].length; i++) {
        let value = 0;
        for (const channel of channels) value += channel[i];
        value = Math.max(-1, Math.min(1, value / channels.length));
        this.buffer[this.count++] = Math.round(value * (value < 0 ? 32768 : 32767));
        if (this.count === this.buffer.length) this.send();
      }
    }
    return true;
  }
}
registerProcessor('pcm-stream', PCMStream);
