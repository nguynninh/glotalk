// Hann-windowed radix-2 FFT. Output is amplitude dBFS, not calibrated sound pressure.
export function spectrumDb(samples) {
  const n = samples.length;
  if (n < 2 || (n & (n - 1))) throw new Error('FFT size must be a power of two');
  const re = new Float64Array(n), im = new Float64Array(n);
  let windowSum = 0;
  for (let i = 0; i < n; i++) {
    const window = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
    re[i] = samples[i] * window;
    windowSum += window;
  }
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) [re[i], re[j]] = [re[j], re[i]];
  }
  for (let size = 2; size <= n; size *= 2) {
    const half = size / 2;
    for (let base = 0; base < n; base += size) {
      for (let j = 0; j < half; j++) {
        const angle = -2 * Math.PI * j / size;
        const real = re[base + j + half] * Math.cos(angle) - im[base + j + half] * Math.sin(angle);
        const imag = re[base + j + half] * Math.sin(angle) + im[base + j + half] * Math.cos(angle);
        re[base + j + half] = re[base + j] - real;
        im[base + j + half] = im[base + j] - imag;
        re[base + j] += real;
        im[base + j] += imag;
      }
    }
  }
  return Float64Array.from({length: n / 2 + 1}, (_, k) => {
    const scale = k === 0 || k === n / 2 ? 1 : 2;
    return 20 * Math.log10(Math.max(1e-6, scale * Math.hypot(re[k], im[k]) / windowSum));
  });
}

export function attachSpectrogram(player) {
  const canvas = document.getElementById('spectrogram');
  const cursor = document.getElementById('playhead');
  const message = document.getElementById('spectrum-status');
  const seek = document.getElementById('spectrum-seek');
  const clock = document.getElementById('spectrum-time');
  let generation = 0, duration = 0, frame = 0, live = false;
  function position() {
    const time = Math.min(player.currentTime || 0, duration);
    cursor.style.left = (live ? 100 : duration ? time / duration * 100 : 0) + '%';
    seek.value = time;
    clock.textContent = time.toFixed(2) + ' / ' + duration.toFixed(2) + ' s';
  }
  function animate() { position(); if (!player.paused) frame = requestAnimationFrame(animate); }
  player.addEventListener('play', () => { cancelAnimationFrame(frame); animate(); });
  for (const event of ['timeupdate', 'seeked', 'pause', 'ended']) player.addEventListener(event, position);
  seek.oninput = () => { if (duration) player.currentTime = Number(seek.value); position(); };
  canvas.onclick = event => {
    if (!live && duration) player.currentTime = Math.max(0, Math.min(1, (event.clientX - canvas.getBoundingClientRect().left) / canvas.getBoundingClientRect().width)) * duration;
    position();
  };
  const render = async file => {
    live = false;
    const current = ++generation;
    duration = 0; seek.disabled = true; cancelAnimationFrame(frame); position();
    canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
    message.textContent = 'Đang phân tích phổ âm thanh…';
    // Avoid decoding an entire multi-GB video in browser memory. Recognition is unaffected.
    if (file.size > 128 * 1024 * 1024) {
      message.textContent = 'File lớn: không nạp phổ tổng quan vào RAM trình duyệt. Nhận dạng vẫn hoạt động.';
      return;
    }
    let context;
    try {
      context = new OfflineAudioContext(1, 1, 16000);
      const audio = await context.decodeAudioData(await file.arrayBuffer());
      if (current !== generation) return;
      const width = 600, height = 192, n = 1024;
      const image = canvas.getContext('2d').createImageData(width, height);
      const channels = Array.from({length: audio.numberOfChannels}, (_, c) => audio.getChannelData(c));
      const samples = new Float64Array(n);
      const maximum = Math.min(8000, audio.sampleRate / 2);
      for (let x = 0; x < width; x++) {
        const center = Math.floor(x / (width - 1) * (audio.length - 1));
        for (let i = 0; i < n; i++) {
          const index = center + i - n / 2;
          samples[i] = index < 0 || index >= audio.length ? 0 : channels.reduce((sum, channel) => sum + channel[index], 0) / channels.length;
        }
        const db = spectrumDb(samples);
        for (let y = 0; y < height; y++) {
          const low = Math.floor((height - 1 - y) / height * maximum * n / audio.sampleRate);
          const high = Math.min(n / 2, Math.ceil((height - y) / height * maximum * n / audio.sampleRate));
          let peak = -120;
          for (let k = low; k <= high; k++) peak = Math.max(peak, db[k]);
          const intensity = Math.max(0, Math.min(1, (peak + 80) / 80));
          const offset = (y * width + x) * 4;
          const stops = [[12, 29, 29], [31, 112, 96], [157, 196, 102], [255, 220, 119]];
          const level = Math.min(2, Math.floor(intensity * 3)), mix = intensity * 3 - level;
          for (let c = 0; c < 3; c++) image.data[offset + c] = stops[level][c] * (1 - mix) + stops[level + 1][c] * mix;
          image.data[offset + 3] = 255;
        }
        if (x % 32 === 0) {
          await new Promise(resolve => setTimeout(resolve, 0));
          if (current !== generation) return;
        }
      }
      canvas.width = width; canvas.height = height;
      canvas.getContext('2d').putImageData(image, 0, 0);
      duration = audio.duration; seek.max = duration; seek.disabled = false; position();
      message.textContent = 'Phổ tần · 0–' + (maximum / 1000).toFixed(0) + ' kHz · bấm vào phổ để tua';
      document.getElementById('spectrum-max').textContent = (maximum / 1000).toFixed(0) + ' kHz';
      canvas.setAttribute('aria-label', 'Phổ tần audio dài ' + duration.toFixed(2) + ' giây, từ 0 đến ' + maximum + ' Hz. Màu sáng biểu thị biên độ lớn hơn.');
    } catch (error) {
      if (current === generation) message.textContent = 'Không tạo được phổ: ' + error.message + ' Bạn vẫn có thể thử nhận dạng.';
    }
  };
  const recent = new Float64Array(1024);
  render.startLive = chunkMs => {
    generation++;live=true;duration=Number.isFinite(player.duration)?player.duration:0;
    seek.max=duration;seek.disabled=true;recent.fill(0);
    const ctx=canvas.getContext('2d');canvas.width=600;canvas.height=192;
    ctx.fillStyle='#0c1d1d';ctx.fillRect(0,0,600,192);
    message.textContent='Phổ trực tiếp · '+(600*chunkMs/1000)+' giây gần nhất · 0–8 kHz';
    canvas.setAttribute('aria-label','Phổ âm thanh cập nhật từ từng chunk audio đang phát, không giải mã trước cả file.');
    document.getElementById('spectrum-max').textContent='8 kHz';position();
  };
  render.appendPCM = pcm => {
    if(!live)return;
    if(pcm.length>=1024){for(let i=0;i<1024;i++)recent[i]=pcm[pcm.length-1024+i]/32768;}
    else {recent.copyWithin(0,pcm.length);for(let i=0;i<pcm.length;i++)recent[1024-pcm.length+i]=pcm[i]/32768;}
    const db=spectrumDb(recent),ctx=canvas.getContext('2d'),image=ctx.createImageData(1,192);
    for(let y=0;y<192;y++){
      const low=Math.floor((191-y)/192*512),high=Math.ceil((192-y)/192*512);
      let peak=-120;for(let k=low;k<=high;k++)peak=Math.max(peak,db[k]);
      const intensity=Math.max(0,Math.min(1,(peak+80)/80));
      const stops=[[12,29,29],[31,112,96],[157,196,102],[255,220,119]];
      const level=Math.min(2,Math.floor(intensity*3)),mix=intensity*3-level;
      for(let c=0;c<3;c++)image.data[y*4+c]=stops[level][c]*(1-mix)+stops[level+1][c]*mix;
      image.data[y*4+3]=255;
    }
    ctx.drawImage(canvas,-1,0);ctx.putImageData(image,599,0);position();
  };
  return render;

}
