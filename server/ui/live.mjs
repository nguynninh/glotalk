// Native media decoding reads the local Blob incrementally, including MP4 moov at EOF.
// Only mono PCM chunks cross the loopback socket; the original video is never uploaded.
export class LiveRecognizer {
  constructor(player) { this.player = player; this.context = null; this.source = null; this.node = null; this.socket = null; this.audible = true; }
  setAudible(value){this.audible=value;if(this.gain)this.gain.gain.value=value?1:0;}
  async prepare(chunkMs) {
    this.player.pause();
    this.player.currentTime = 0;
    if (!this.context) {
      this.context = new AudioContext({sampleRate:16000, latencyHint:'interactive'});
      if (this.context.sampleRate !== 16000) throw Error('Trình duyệt không hỗ trợ audio 16 kHz. Hãy thử Chrome.');
      this.source = this.context.createMediaElementSource(this.player);
      this.gain = this.context.createGain();this.gain.connect(this.context.destination);
      await this.context.audioWorklet.addModule('/pcm-worklet.js');
    }
    await this.context.resume();
    if (this.node) { this.source.disconnect(this.node); this.node.disconnect(); }
    this.node = new AudioWorkletNode(this.context, 'pcm-stream', {processorOptions:{chunkMs}});
    this.source.connect(this.node); this.node.connect(this.gain);this.gain.gain.value=this.audible?1:0;
  }
  async start({threads, chunk, speakers = false, target_language = '', translation_mode = 'standard', onEvent, onPCM}) {
    await this.prepare(chunk);
    return new Promise((resolve, reject) => {
      const socket = this.socket = new WebSocket(location.protocol === 'https:' ? `wss://${location.host}/ws` : 'ws://127.0.0.1:8766');
      let done = false, sent = 0, failed = false;
      const fail = error => { if(failed)return;failed=true;this.player.pause();socket.close();reject(error); };
      this.node.port.onmessage = ({data}) => {
        if (data.type === 'flushed') { if(socket.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:'end'}));return; }
        if (data.type !== 'pcm' || socket.readyState !== WebSocket.OPEN) return;
        // Never silently drop audio if recognition can't keep up.
        if(socket.bufferedAmount>1024*1024){ fail(Error('Nhận dạng không theo kịp audio. Đã dừng để tránh mất đoạn.'));return; }
        socket.send(data.pcm);sent+=data.pcm.byteLength/2;
        onPCM?.(new Int16Array(data.pcm), sent / 16000);
      };
      const paused = () => this.node.port.postMessage('pause');
      const playing = () => this.node.port.postMessage('resume');
      this.player.addEventListener('pause', paused);this.player.addEventListener('playing', playing);
      const ended = () => this.node.port.postMessage('flush');
      const mediaError = () => fail(Error('Trình duyệt không giải mã được audio của file này. Hãy thử Chrome hoặc file MP4 H.264/AAC.'));
      this.player.addEventListener('ended', ended, {once:true});
      this.player.addEventListener('error', mediaError, {once:true});
      socket.onopen = () => socket.send(JSON.stringify({threads:Number(threads),chunk:Number(chunk),speakers,target_language,translation_mode}));
      socket.onmessage = async ({data}) => {
        if(failed)return;
        try {
          const event=JSON.parse(data);onEvent(event);
          if(event.type==='error')throw Error(event.text);
          if(event.type==='ready') { this.node.port.postMessage('start');await this.player.play(); }
          if(event.type==='done') { done=true;resolve();socket.close(); }
        }catch(error){fail(error);}
      };
      socket.onerror = () => fail(Error('Không kết nối được engine streaming local.'));
      socket.onclose = () => {
        this.node.port.postMessage('flush');
        this.player.removeEventListener('ended', ended);this.player.removeEventListener('error',mediaError);this.player.removeEventListener('pause',paused);this.player.removeEventListener('playing',playing);
        if(!done&&!failed)fail(Error('Phiên streaming đã đóng trước khi hoàn tất.'));
      };
      this.cancel = () => fail(new DOMException('Đã dừng nhận dạng.','AbortError'));
    });
  }
}
