mod session;
mod cache;
mod chunked;

pub use session::LavSession;
pub use cache::PacketRing;

use std::sync::OnceLock;
use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer, JByteArray, JIntArray, JLongArray};
use jni::sys::{jbyte, jbyteArray, jint, jintArray, jlong, jlongArray};

use crate::session::LavSessions;

static SESSIONS: OnceLock<LavSessions> = OnceLock::new();

fn get_sessions() -> &'static LavSessions {
    SESSIONS.get_or_init(LavSessions::new)
}

// ---------------------------------------------------------------------------
// JNI entry points - match native method declarations in
// fr.theorozier.webstreamer.jni.NativeLibrary
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_fr_theorozier_webstreamer_jni_NativeLibrary_nAbiVersion<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    6
}

#[no_mangle]
pub extern "system" fn Java_fr_theorozier_webstreamer_jni_NativeLibrary_nInitLogging<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    let _ = env_logger::try_init();
}

#[no_mangle]
pub extern "system" fn Java_fr_theorozier_webstreamer_jni_NativeLibrary_nOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
) -> jlong {
    let url: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    match get_sessions().open(&url) {
        Ok(id) => id,
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", e.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_fr_theorozier_webstreamer_jni_NativeLibrary_nReadFrameI420<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    buf: jni::sys::jobject,
    buf_len: jint,
    out_dims: jintArray,
    out_pts: jlongArray,
) -> jlong {
    let session = match get_sessions().get(handle) {
        Some(s) => s,
        None => return -1,
    };

    let byte_buf = unsafe { JByteBuffer::from_raw(buf) };
    let buf_ptr = match env.get_direct_buffer_address(&byte_buf) {
        Ok(p) => p as *mut u8,
        Err(_) => return -1,
    };
    let buf_len = buf_len as usize;

    match session.read_frame_i420(buf_ptr, buf_len) {
        Ok(frame) => {
            let dims_array = unsafe { JIntArray::from_raw(out_dims) };
            let dims_slice = [frame.width, frame.height];
            if let Err(_) = env.set_int_array_region(&dims_array, 0, &dims_slice) {
                return -1;
            }
            let pts_array = unsafe { JLongArray::from_raw(out_pts) };
            let pts_slice = [frame.pts_us];
            if let Err(_) = env.set_long_array_region(&pts_array, 0, &pts_slice) {
                return -1;
            }
            frame.data_len as jlong
        }
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", e.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_fr_theorozier_webstreamer_jni_NativeLibrary_nSeek<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    position_us: jlong,
) -> jint {
    let session = match get_sessions().get(handle) {
        Some(s) => s,
        None => return -1,
    };
    match session.seek(position_us) {
        Ok(()) => 0,
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", e.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_fr_theorozier_webstreamer_jni_NativeLibrary_nEnableCache<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    max_duration_us: jlong,
    max_bytes: jlong,
) -> jint {
    let session = match get_sessions().get(handle) {
        Some(s) => s,
        None => return -1,
    };
    session.enable_cache(max_duration_us, max_bytes);
    0
}

#[no_mangle]
pub extern "system" fn Java_fr_theorozier_webstreamer_jni_NativeLibrary_nError<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    buf: jbyteArray,
    buf_len: jint,
) -> jint {
    let session = match get_sessions().get(handle) {
        Some(s) => s,
        None => return 0,
    };
    let msg = session.last_error();
    let bytes = msg.as_bytes();
    let len = (bytes.len() as jint).min(buf_len - 1);
    let java_bytes: Vec<jbyte> = bytes.iter().map(|&b| b as jbyte).collect();
    let byte_array = unsafe { JByteArray::from_raw(buf) };
    if let Err(_) = env.set_byte_array_region(&byte_array, 0, &java_bytes) {
        return 0;
    }
    len
}

#[no_mangle]
pub extern "system" fn Java_fr_theorozier_webstreamer_jni_NativeLibrary_nKill<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if let Some(session) = get_sessions().get(handle) {
        session.kill();
    }
}

#[no_mangle]
pub extern "system" fn Java_fr_theorozier_webstreamer_jni_NativeLibrary_nClose<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    get_sessions().close(handle);
}
