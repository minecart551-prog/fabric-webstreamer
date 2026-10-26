use std::collections::{HashMap, VecDeque};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};

use anyhow::{Context, Result};
use ffmpeg_next as ffmpeg;
use ffmpeg_sys_next as sys;

use crate::cache::{CachedPacket, SharedPacketRing};

/// Represents a decoded frame in I420 format.
pub struct DecodedFrame {
    pub width: i32,
    pub height: i32,
    pub pts_us: i64,
    pub data_len: usize,
}

/// Hardware acceleration backend.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum HwBackend {
    None,
    VideoToolbox,
    D3D11VA,
    VAAPI,
    CUDA,
}

impl HwBackend {
    pub fn detect() -> Self {
        let os = std::env::consts::OS;
        match os {
            "macos" => Self::VideoToolbox,
            "windows" => Self::D3D11VA,
            "linux" => Self::VAAPI,
            _ => Self::None,
        }
    }

    fn hw_device_type(self) -> Option<sys::AVHWDeviceType> {
        match self {
            Self::VideoToolbox => Some(sys::AVHWDeviceType::AV_HWDEVICE_TYPE_VIDEOTOOLBOX),
            Self::D3D11VA => Some(sys::AVHWDeviceType::AV_HWDEVICE_TYPE_D3D11VA),
            Self::VAAPI => Some(sys::AVHWDeviceType::AV_HWDEVICE_TYPE_VAAPI),
            Self::CUDA => Some(sys::AVHWDeviceType::AV_HWDEVICE_TYPE_CUDA),
            Self::None => None,
        }
    }

    /// Try to create a hardware device context. Returns the raw AVBufferRef
    /// pointer on success, or None if HW accel isn't available on this system.
    unsafe fn try_create_device(self) -> Option<*mut sys::AVBufferRef> {
        let hw_type = self.hw_device_type()?;
        let mut dev: *mut sys::AVBufferRef = ptr::null_mut();
        let ret = sys::av_hwdevice_ctx_create(&mut dev, hw_type, ptr::null(), ptr::null_mut(), 0);
        if ret >= 0 && !dev.is_null() {
            Some(dev)
        } else {
            if !dev.is_null() {
                sys::av_buffer_unref(&mut dev);
            }
            None
        }
    }
}

/// Global table of decode sessions.
pub struct LavSessions {
    counter: AtomicI64,
    sessions: Mutex<HashMap<i64, Arc<LavSession>>>,
}

impl LavSessions {
    pub fn new() -> Self {
        let _ = ffmpeg::init();
        // Silence FFmpeg's own logging. While streaming progressive MP4 the
        // mov demuxer spams "partial file" (and other INFO/WARNING lines) to
        // stderr from whichever thread is decoding — flooding the game log and
        // stalling that thread on pipe/console writes. Real session errors
        // are surfaced through anyhow -> Java IOExceptions instead.
        ffmpeg::log::set_level(ffmpeg::log::Level::Quiet);
        Self {
            counter: AtomicI64::new(1),
            sessions: Mutex::new(HashMap::new()),
        }
    }

    pub fn open(&self, url: &str) -> Result<i64> {
        let id = self.counter.fetch_add(1, Ordering::Relaxed);
        let session = Arc::new(LavSession::new(id, url)?);
        self.sessions.lock().unwrap().insert(id, session);
        Ok(id)
    }

    pub fn get(&self, handle: i64) -> Option<Arc<LavSession>> {
        self.sessions.lock().unwrap().get(&handle).cloned()
    }

    pub fn close(&self, handle: i64) {
        if let Some(session) = self.sessions.lock().unwrap().remove(&handle) {
            session.kill();
        }
    }
}

/// A single decode session.
pub struct LavSession {
    id: i64,
    url: String,
    interrupted: Arc<AtomicBool>,
    error: Mutex<String>,
    cache: Mutex<Option<SharedPacketRing>>,
    inner: Mutex<SessionInner>,
}

struct SessionInner {
    input_context: Option<ffmpeg::format::context::Input>,
    video_stream_index: Option<usize>,
    decoder: Option<ffmpeg::decoder::Video>,
    scaler: Option<ffmpeg::software::scaling::Context>,
    /// Format/size the current scaler was built for; the scaler is (re)created
    /// lazily whenever a decoded frame doesn't match (e.g. hardware decode
    /// transfers VAAPI/D3D11 frames to NV12 while the codec default used at
    /// open time is YUV420P — sws returns AVERROR_INPUT_CHANGED on mismatch).
    scaler_src: Option<(ffmpeg::format::Pixel, u32, u32)>,
    sw_frame: ffmpeg::frame::Video,
    hw_frame: ffmpeg::frame::Video,
    frame_count: u64,
    last_pts_us: i64,
    eof: bool,
    hw_backend: HwBackend,
    hw_device_ctx: Option<*mut sys::AVBufferRef>,
    hw_pix_fmt: Option<sys::AVPixelFormat>,
    audio: Option<AudioState>,
}

/// Audio decode + resample state for a session. Audio packets are fed while
/// reading video frames (they are interleaved in the container), resampled to
/// interleaved S16 mono at the source rate, and buffered for Java to drain.
struct AudioState {
    decoder: ffmpeg::decoder::Audio,
    stream_index: usize,
    time_base: ffmpeg::Rational,
    /// Lazy-created swresample context: decoder format -> S16 packed mono at
    /// the source sample rate (the JavaCV path delivers mono short PCM at the
    /// stream's rate too).
    resampler: Option<ffmpeg::software::resampling::Context>,
    /// Resampled interleaved S16 mono bytes waiting to be drained by Java.
    ring: VecDeque<u8>,
    /// PTS (µs) of the sample at the head of the ring.
    ring_pts_f64: f64,
    /// Sample rate of the resampled output.
    out_rate: i32,
}

impl AudioState {
    /// Feed one demuxed audio packet and collect any decoded PCM.
    fn feed(&mut self, packet: &ffmpeg::Packet) {
        if self.decoder.send_packet(packet).is_err() {
            return;
        }
        self.drain_frames();
    }

    /// Drop all buffered state after a seek.
    fn on_seek(&mut self) {
        self.decoder.flush();
        self.ring.clear();
        self.ring_pts_f64 = 0.0;
    }

    /// Decode delayed frames still buffered in the decoder (end of stream).
    fn drain_at_eof(&mut self) {
        let _ = self.decoder.send_eof();
        self.drain_frames();
    }

    fn drain_frames(&mut self) {
        loop {
            let mut frame = ffmpeg::frame::Audio::empty();
            match self.decoder.receive_frame(&mut frame) {
                Ok(()) => self.resample_into_ring(&mut frame),
                Err(ffmpeg::Error::Other { errno: ffmpeg::error::EAGAIN }) => break,
                Err(ffmpeg::Error::Eof) => break,
                Err(e) => {
                    log::warn!("Session audio: receive_frame failed: {e}");
                    break;
                }
            }
        }
    }

    fn resample_into_ring(&mut self, frame: &mut ffmpeg::frame::Audio) {
        let in_fmt = frame.format();
        let in_layout = frame.channel_layout();
        let in_rate = frame.rate();
        if in_rate == 0 {
            return;
        }
        if self.resampler.is_none() {
            match ffmpeg::software::resampling::Context::get(
                in_fmt,
                in_layout,
                in_rate,
                ffmpeg::format::Sample::I16(ffmpeg::format::sample::Type::Packed),
                ffmpeg::channel_layout::ChannelLayout::MONO,
                in_rate,
            ) {
                Ok(ctx) => {
                    self.resampler = Some(ctx);
                    self.out_rate = in_rate as i32;
                }
                Err(e) => {
                    log::warn!("Session audio: resampler create failed: {e}");
                    return;
                }
            }
        }

        let pts_us = self.time_base.pts_us(frame.pts().unwrap_or(0));
        let mut out = ffmpeg::frame::Audio::empty();
        if let Err(e) = self.resampler.as_mut().unwrap().run(frame, &mut out) {
            log::warn!("Session audio: resample failed: {e}");
            return;
        }
        let samples = out.samples();
        if samples == 0 {
            return;
        }
        let data = out.data(0);
        let bytes = (samples * 2).min(data.len());
        if self.ring.is_empty() {
            self.ring_pts_f64 = pts_us as f64;
        }
        self.ring.extend(&data[..bytes]);
    }
}

unsafe impl Send for SessionInner {}

impl LavSession {
    fn new(id: i64, url: &str) -> Result<Self> {
        let interrupted = Arc::new(AtomicBool::new(false));
        let hw_backend = HwBackend::detect();

        // Same HTTP identity as the JavaCV path (FFmpegFrameGrabber's
        // user_agent + Referer options): some CDNs vary behavior by user
        // agent or require the YouTube referer, and the two decode paths
        // should look identical to the server. Unknown options (non-http
        // inputs like plain files) are ignored by avformat_open_input.
        let mut open_opts = ffmpeg::Dictionary::new();
        open_opts.set(
            "user_agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
             (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        );
        open_opts.set("headers", "Referer: https://www.youtube.com/\r\n");

        let ictx = ffmpeg::format::input_with_dictionary(&url, open_opts)
            .context("failed to open input")?;

        let video_stream_index = ictx
            .streams()
            .best(ffmpeg::media::Type::Video)
            .map(|s| s.index())
            .context("no video stream found")?;

        let stream = ictx.stream(video_stream_index).unwrap();
        let codec_params = stream.parameters();

        // Try to create a hardware device context
        let mut hw_device: Option<*mut sys::AVBufferRef> = None;
        let mut hw_pix_fmt: Option<sys::AVPixelFormat> = None;

        unsafe {
            if let Some(mut dev) = hw_backend.try_create_device() {
                // Find the hw pixel format supported by this codec
                let codec_id: sys::AVCodecID = codec_params.id().into();
                let codec = sys::avcodec_find_decoder(codec_id);
                if !codec.is_null() {
                    let mut i = 0;
                    loop {
                        let config = sys::avcodec_get_hw_config(codec, i);
                        if config.is_null() {
                            break;
                        }
                        let cfg = &*config;
                        if cfg.methods & sys::AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX as i32 != 0 {
                            hw_pix_fmt = Some(cfg.pix_fmt);
                            break;
                        }
                        i += 1;
                    }
                }

                if hw_pix_fmt.is_some() {
                    hw_device = Some(dev);
                    log::info!("Session {}: using {:?} hardware acceleration", id, hw_backend);
                } else {
                    // HW device created but codec doesn't support it
                    sys::av_buffer_unref(&mut dev as *mut _);
                    log::info!("Session {}: {:?} device created but codec lacks HW config, using software", id, hw_backend);
                }
            } else {
                log::info!("Session {}: {:?} not available, using software decode", id, hw_backend);
            }
        }

        let mut context_decoder = ffmpeg::codec::context::Context::from_parameters(codec_params)
            .context("failed to create codec context")?;

        // If HW accel is available, configure the decoder with hw_device_ctx
        // and set the hw pixel format so frames are decoded directly on the GPU.
        unsafe {
            if let (Some(dev_ptr), Some(pf)) = (&hw_device, hw_pix_fmt) {
                let raw_ctx = context_decoder.as_mut_ptr();
                (*raw_ctx).hw_device_ctx = sys::av_buffer_ref(*dev_ptr);
                (*raw_ctx).get_format = Some(hardware_get_format);
                log::info!("Session {}: configured decoder with hw_pix_fmt={:?}", id, pf);
            }
        }

        let mut decoder = context_decoder.decoder().video()
            .context("failed to create video decoder")?;

        // Many layers can decode concurrently (7+ displays), so keep the frame
        // threading count per decoder modest to avoid oversubscribing the CPU
        // (previously num_cpus.min(8) threads for every open layer).
        decoder.set_threading(ffmpeg::threading::Config {
            kind: ffmpeg::threading::Type::Frame,
            count: (num_cpus::get() / 4).clamp(1, 4),
        });

        // Determine the pixel format the decoder is actually outputting.
        // When HW accel is active this will be the hw pixel format (e.g. D3D11,
        // VAAPI, CUDA). We need to create the scaler with the *software* format
        // as input, and an extra step to transfer hw->sw in between.
        let decoder_pix_fmt = decoder.format();

        let scaler = ffmpeg::software::scaling::Context::get(
            decoder_pix_fmt,
            decoder.width(),
            decoder.height(),
            ffmpeg::format::Pixel::BGR24,
            decoder.width(),
            decoder.height(),
            ffmpeg::software::scaling::Flags::BILINEAR,
        )?;

        let sw_frame = ffmpeg::frame::Video::empty();
        let hw_frame = ffmpeg::frame::Video::empty();

        // Best-effort audio: open the best audio stream's decoder. Audio
        // packets are fed and resampled to mono S16 while reading video
        // frames (the packet loop drains them as they are demuxed).
        let mut audio_state: Option<AudioState> = None;
        if let Some(astream) = ictx.streams().best(ffmpeg::media::Type::Audio) {
            let aidx = astream.index();
            let atb = astream.time_base();
            let aparams = astream.parameters();
            match ffmpeg::codec::context::Context::from_parameters(aparams)
                .and_then(|ctx| ctx.decoder().audio())
            {
                Ok(aud_decoder) => {
                    log::info!(
                        "Session {}: audio stream {} decoded to mono S16",
                        id, aidx
                    );
                    audio_state = Some(AudioState {
                        decoder: aud_decoder,
                        stream_index: aidx,
                        time_base: atb,
                        resampler: None,
                        ring: VecDeque::new(),
                        ring_pts_f64: 0.0,
                        out_rate: 0,
                    });
                }
                Err(e) => {
                    log::info!("Session {}: no usable audio decoder ({e}), video only", id);
                }
            }
        }

        Ok(Self {
            id,
            url: url.to_owned(),
            interrupted,
            error: Mutex::new(String::new()),
            cache: Mutex::new(None),
            inner: Mutex::new(SessionInner {
                input_context: Some(ictx),
                video_stream_index: Some(video_stream_index),
                decoder: Some(decoder),
                scaler: Some(scaler),
                scaler_src: None,
                sw_frame,
                hw_frame,
                frame_count: 0,
                last_pts_us: 0,
                eof: false,
                hw_backend,
                hw_device_ctx: hw_device,
                hw_pix_fmt,
                audio: audio_state,
            }),
        })
    }

    pub fn read_frame_i420(&self, buf: *mut u8, buf_len: usize) -> Result<DecodedFrame> {
        let mut inner = self.inner.lock().unwrap();

        if inner.eof {
            return Err(anyhow::anyhow!("end of stream"));
        }

        let video_idx = inner.video_stream_index.context("no video stream")?;
        let tb = inner.input_context.as_ref().context("no input context")?
            .stream(video_idx).unwrap().time_base();

        let hw_active = inner.hw_pix_fmt.is_some();
        let audio_idx = inner.audio.as_ref().map(|a| a.stream_index);

        loop {
            if self.interrupted.load(Ordering::Relaxed) {
                return Err(anyhow::anyhow!("interrupted"));
            }

            // Read a packet from the input context, then release the borrow
            let mut packet = ffmpeg::Packet::empty();
            {
                let ictx = inner.input_context.as_mut().context("no input context")?;
                match packet.read(ictx) {
                    Ok(()) => {}
                    Err(ffmpeg::Error::Eof) => {
                        inner.eof = true;
                        // Decode any audio frames still buffered so the tail
                        // of the soundtrack can be drained by Java.
                        if let Some(audio) = inner.audio.as_mut() {
                            audio.drain_at_eof();
                        }
                        return Err(anyhow::anyhow!("end of stream"));
                    }
                    Err(ffmpeg::Error::InvalidData) => continue,
                    Err(e) => {
                        eprintln!("[lav] packet.read failed: {e:?} ({e})");
                        *self.error.lock().unwrap() = e.to_string();
                        return Err(e.into());
                    }
                }
            }

            if packet.stream() != video_idx {
                if Some(packet.stream()) == audio_idx {
                    if let Some(audio) = inner.audio.as_mut() {
                        audio.feed(&packet);
                    }
                }
                continue;
            }

            // Cache packet
            if let Ok(cache_guard) = self.cache.lock() {
                if let Some(ref cache) = *cache_guard {
                    let is_key = packet.is_key();
                    let pts = packet.pts().unwrap_or(0);
                    let duration = packet.duration();
                    let pts_us = tb.pts_us(pts);
                    let dur_us = tb.pts_us(duration);
                    let data = packet.data().unwrap_or(&[]).to_vec();
                    cache.push(CachedPacket {
                        data,
                        pts_us,
                        duration_us: dur_us,
                        is_keyframe: is_key,
                    });
                }
            }

            // Decode
            if hw_active {
                // Hardware decode path: decode into hw_frame, then transfer to sw_frame
                let mut hw_frame = ffmpeg::frame::Video::empty();
                std::mem::swap(&mut hw_frame, &mut inner.hw_frame);
                let decoder = inner.decoder.as_mut().context("no decoder")?;
                if let Err(e) = decoder.send_packet(&packet) {
                    eprintln!("[lav] hw send_packet failed: {e:?} ({e})");
                    return Err(e.into());
                }

                match decoder.receive_frame(&mut hw_frame) {
                    Ok(()) => {
                        // Transfer HW frame to software frame (GPU -> CPU)
                        let mut sw_frame = ffmpeg::frame::Video::empty();
                        std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                        unsafe {
                            let ret = sys::av_hwframe_transfer_data(
                                sw_frame.as_mut_ptr() as *mut _,
                                hw_frame.as_mut_ptr() as *mut _,
                                0,
                            );
                            if ret < 0 {
                                std::mem::swap(&mut hw_frame, &mut inner.hw_frame);
                                std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                                let err = format!("hw frame transfer failed: {}", ret);
                                *self.error.lock().unwrap() = err.clone();
                                return Err(anyhow::anyhow!(err));
                            }
                            // Hardware transfer does not carry timestamps over;
                            // keep the decoded frame's pts for playback pacing.
                            let hw_pts = hw_frame.pts().or_else(|| sw_frame.pts());
                            sw_frame.set_pts(hw_pts);
                        }
                        std::mem::swap(&mut hw_frame, &mut inner.hw_frame);
                        std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                    }
                    Err(ffmpeg::Error::Other { errno: ffmpeg::error::EAGAIN }) => {
                        std::mem::swap(&mut hw_frame, &mut inner.hw_frame);
                        continue;
                    }
                    Err(e) => {
                        std::mem::swap(&mut hw_frame, &mut inner.hw_frame);
                        *self.error.lock().unwrap() = e.to_string();
                        return Err(e.into());
                    }
                }
            } else {
                // Software decode path (original)
                let mut sw_frame = ffmpeg::frame::Video::empty();
                std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                let decoder = inner.decoder.as_mut().context("no decoder")?;
                if let Err(e) = decoder.send_packet(&packet) {
                    eprintln!("[lav] sw send_packet failed: {e:?} ({e})");
                    return Err(e.into());
                }

                match decoder.receive_frame(&mut sw_frame) {
                    Ok(()) => {
                        std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                    }
                    Err(ffmpeg::Error::Other { errno: ffmpeg::error::EAGAIN }) => {
                        std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                        continue;
                    }
                    Err(e) => {
                        std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                        *self.error.lock().unwrap() = e.to_string();
                        return Err(e.into());
                    }
                }
            }

            // Scale frame: convert the decoder output (NV12 from hardware
            // transfer, YUV420P from software decode) to packed BGR using
            // swscale — the same conversion ffmpeg's own tooling and the
            // JavaCV path use, so colors match exactly.
            let mut output_frame = ffmpeg::frame::Video::empty();
            {
                let mut sw_frame = ffmpeg::frame::Video::empty();
                std::mem::swap(&mut sw_frame, &mut inner.sw_frame);

                let in_fmt = sw_frame.format();
                let in_w = sw_frame.width();
                let in_h = sw_frame.height();
                let needs_new = in_w > 0 && in_h > 0
                    && match inner.scaler_src {
                        Some((f, w, h)) => f != in_fmt || w != in_w || h != in_h,
                        None => true,
                    };

                if needs_new || inner.scaler.is_none() {
                    let ctx = ffmpeg::software::scaling::Context::get(
                        in_fmt,
                        in_w,
                        in_h,
                        ffmpeg::format::Pixel::BGR24,
                        in_w,
                        in_h,
                        ffmpeg::software::scaling::Flags::BILINEAR,
                    )
                    .context("failed to create scaler")?;
                    inner.scaler = Some(ctx);
                    inner.scaler_src = Some((in_fmt, in_w, in_h));
                    log::info!(
                        "Session {}: scaler reconfigured for {:?} {}x{}",
                        self.id,
                        in_fmt,
                        in_w,
                        in_h
                    );
                }

                if in_w == 0 || in_h == 0 {
                    // Nothing usable in this frame; skip it.
                    std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                    continue;
                }

                let scaler = inner.scaler.as_mut().context("no scaler")?;
                if let Err(e) = scaler.run(&sw_frame, &mut output_frame) {
                    *self.error.lock().unwrap() = e.to_string();
                    log::warn!("Session {}: scaler.run failed: {:?}", self.id, e);
                    std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                    return Err(e.into());
                }
                // sws only converts pixels; carry the pts into the output frame.
                output_frame.set_pts(sw_frame.pts());
                std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
            }

            let width = output_frame.width() as i32;
            let height = output_frame.height() as i32;
            let pts = output_frame.pts().unwrap_or(0);
            let pts_us = tb.pts_us(pts);
            inner.last_pts_us = pts_us;
            inner.frame_count += 1;

            // Packed BGR output: one row of width*3 bytes (stride-aware).
            let row_bytes = (width * 3) as usize;
            let total = row_bytes * height as usize;

            if total > buf_len {
                return Err(anyhow::anyhow!("buffer too small: need {} have {}", total, buf_len));
            }

            let mut offset = 0;
            let data = output_frame.data(0);
            let stride = output_frame.stride(0);
            for row in 0..height as usize {
                let src_start = row * stride;
                let src_end = src_start + row_bytes;
                if src_end <= data.len() {
                    unsafe {
                        std::ptr::copy_nonoverlapping(data[src_start..src_end].as_ptr(), buf.add(offset), row_bytes);
                    }
                }
                offset += row_bytes;
            }

            return Ok(DecodedFrame { width, height, pts_us, data_len: total });
        }
    }

    /// Drain up to `buf_len` bytes of interleaved S16 mono PCM into `buf`.
    /// Returns (bytes_written, pts_us_of_first_sample, sample_rate); a zero
    /// length means no audio is pending right now (or the stream has none).
    pub fn read_audio_pcm(&self, buf: *mut u8, buf_len: usize) -> Result<(usize, i64, i32)> {
        let mut inner = self.inner.lock().unwrap();
        let audio = match inner.audio.as_mut() {
            Some(a) => a,
            None => return Ok((0, 0, 0)),
        };
        if audio.ring.is_empty() || buf_len < 2 {
            return Ok((0, 0, audio.out_rate));
        }
        // Whole S16 samples only.
        let n = audio.ring.len().min(buf_len) & !1usize;
        if n == 0 {
            return Ok((0, 0, audio.out_rate));
        }
        let pts_us = audio.ring_pts_f64.round() as i64;
        let rate = audio.out_rate.max(1);
        let chunk: Vec<u8> = audio.ring.drain(..n).collect();
        unsafe {
            ptr::copy_nonoverlapping(chunk.as_ptr(), buf, n);
        }
        audio.ring_pts_f64 += (n / 2) as f64 * 1_000_000.0 / rate as f64;
        Ok((n, pts_us, audio.out_rate))
    }

    pub fn seek(&self, position_us: i64) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        let video_idx = inner.video_stream_index.context("no video stream")?;
        let tb = inner.input_context.as_ref().context("no input context")?
            .stream(video_idx).unwrap().time_base();
        let timestamp = us_to_tb(position_us, tb);
        let ictx = inner.input_context.as_mut().context("no input context")?;
        ictx.seek(timestamp, timestamp..)?;
        if let Some(ref mut decoder) = inner.decoder {
            decoder.flush();
        }
        if let Some(audio) = inner.audio.as_mut() {
            audio.on_seek();
        }
        inner.eof = false;
        Ok(())
    }

    pub fn enable_cache(&self, max_duration_us: i64, max_bytes: i64) {
        let cache = SharedPacketRing::new(max_duration_us, max_bytes as usize);
        if let Ok(mut guard) = self.cache.lock() {
            *guard = Some(cache);
        }
    }

    pub fn last_error(&self) -> String {
        self.error.lock().unwrap().clone()
    }

    pub fn kill(&self) {
        self.interrupted.store(true, Ordering::Relaxed);
    }
}

impl Drop for LavSession {
    fn drop(&mut self) {
        let mut inner = self.inner.lock().unwrap();
        if let Some(ref mut decoder) = inner.decoder {
            let _ = decoder.send_eof();
        }
        // Free hardware device context
        if let Some(dev) = inner.hw_device_ctx.take() {
            unsafe {
                let mut dev_copy = dev;
                sys::av_buffer_unref(&mut dev_copy);
            }
        }
        log::debug!("Session {} dropped after {} frames (hw={:?})", self.id, inner.frame_count, inner.hw_backend);
    }
}

/// FFmpeg get_format callback that selects the hardware pixel format when
/// the decoder offers a list of supported formats.
///
/// # Safety
/// Called by FFmpeg from the decoder. The `formats` pointer is valid and
/// terminated by `AV_PIX_FMT_NONE`.
unsafe extern "C" fn hardware_get_format(
    _ctx: *mut sys::AVCodecContext,
    formats: *const sys::AVPixelFormat,
) -> sys::AVPixelFormat {
    let mut i = 0;
    loop {
        let fmt = *formats.add(i);
        if fmt == sys::AVPixelFormat::AV_PIX_FMT_NONE {
            break;
        }
        log::debug!("hardware_get_format: offering {:?}", fmt);
        i += 1;
    }
    // Return the first format offered — the decoder's preferred hw format.
    // The HW transfer step will convert it to software I420 before scaling.
    *formats
}

fn us_to_tb(us: i64, tb: ffmpeg::Rational) -> i64 {
    us * tb.denominator() as i64 / (tb.numerator() as i64 * 1_000_000)
}

trait TimeBaseExt {
    fn pts_us(&self, pts: i64) -> i64;
}

impl TimeBaseExt for ffmpeg::Rational {
    fn pts_us(&self, pts: i64) -> i64 {
        pts * self.numerator() as i64 * 1_000_000 / self.denominator() as i64
    }
}
