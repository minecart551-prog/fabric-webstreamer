use std::collections::HashMap;
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
    sw_frame: ffmpeg::frame::Video,
    hw_frame: ffmpeg::frame::Video,
    frame_count: u64,
    last_pts_us: i64,
    eof: bool,
    hw_backend: HwBackend,
    hw_device_ctx: Option<*mut sys::AVBufferRef>,
    hw_pix_fmt: Option<sys::AVPixelFormat>,
}

unsafe impl Send for SessionInner {}

impl LavSession {
    fn new(id: i64, url: &str) -> Result<Self> {
        let interrupted = Arc::new(AtomicBool::new(false));
        let hw_backend = HwBackend::detect();

        let ictx = ffmpeg::format::input(&url)
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

        decoder.set_threading(ffmpeg::threading::Config {
            kind: ffmpeg::threading::Type::Frame,
            count: num_cpus::get().min(8),
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
            ffmpeg::format::Pixel::YUV420P,
            decoder.width(),
            decoder.height(),
            ffmpeg::software::scaling::Flags::BILINEAR,
        )?;

        let sw_frame = ffmpeg::frame::Video::empty();
        let hw_frame = ffmpeg::frame::Video::empty();

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
                sw_frame,
                hw_frame,
                frame_count: 0,
                last_pts_us: 0,
                eof: false,
                hw_backend,
                hw_device_ctx: hw_device,
                hw_pix_fmt,
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
                        return Err(anyhow::anyhow!("end of stream"));
                    }
                    Err(ffmpeg::Error::InvalidData) => continue,
                    Err(e) => {
                        *self.error.lock().unwrap() = e.to_string();
                        return Err(e.into());
                    }
                }
            }

            if packet.stream() != video_idx {
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
                decoder.send_packet(&packet)?;

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
                decoder.send_packet(&packet)?;

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

            // Scale frame (converts whatever the decoder output to I420)
            let mut output_frame = ffmpeg::frame::Video::empty();
            {
                let mut sw_frame = ffmpeg::frame::Video::empty();
                std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
                let scaler = inner.scaler.as_mut().context("no scaler")?;
                scaler.run(&sw_frame, &mut output_frame)?;
                std::mem::swap(&mut sw_frame, &mut inner.sw_frame);
            }

            let width = output_frame.width() as i32;
            let height = output_frame.height() as i32;
            let pts = output_frame.pts().unwrap_or(0);
            let pts_us = tb.pts_us(pts);
            inner.last_pts_us = pts_us;
            inner.frame_count += 1;

            let y_size = (width * height) as usize;
            let uv_size = ((width + 1) / 2 * (height + 1) / 2) as usize;
            let total = y_size + uv_size * 2;

            if total > buf_len {
                return Err(anyhow::anyhow!("buffer too small: need {} have {}", total, buf_len));
            }

            let mut offset = 0;

            // Y plane
            let y_data = output_frame.data(0);
            let y_stride = output_frame.stride(0);
            for row in 0..height as usize {
                let src_start = row * y_stride;
                let src_end = src_start + width as usize;
                if src_end <= y_data.len() {
                    unsafe {
                        std::ptr::copy_nonoverlapping(y_data[src_start..src_end].as_ptr(), buf.add(offset), width as usize);
                    }
                }
                offset += width as usize;
            }

            // U plane
            let u_data = output_frame.data(1);
            let u_stride = output_frame.stride(1);
            for row in 0..(height / 2) as usize {
                let src_start = row * u_stride;
                let src_end = src_start + (width / 2) as usize;
                if src_end <= u_data.len() {
                    unsafe {
                        std::ptr::copy_nonoverlapping(u_data[src_start..src_end].as_ptr(), buf.add(offset), (width / 2) as usize);
                    }
                }
                offset += (width / 2) as usize;
            }

            // V plane
            let v_data = output_frame.data(2);
            let v_stride = output_frame.stride(2);
            for row in 0..(height / 2) as usize {
                let src_start = row * v_stride;
                let src_end = src_start + (width / 2) as usize;
                if src_end <= v_data.len() {
                    unsafe {
                        std::ptr::copy_nonoverlapping(v_data[src_start..src_end].as_ptr(), buf.add(offset), (width / 2) as usize);
                    }
                }
                offset += (width / 2) as usize;
            }

            return Ok(DecodedFrame { width, height, pts_us, data_len: total });
        }
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
