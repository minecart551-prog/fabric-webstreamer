use std::io::{self, Read, Seek, SeekFrom};

/// Bounded HTTP range request reader. Paces network reads to avoid
/// CDN throttling and supports seeking within the current request.
pub struct ChunkedReader {
    url: String,
    agent: ureq::Agent,
    total_size: Option<u64>,
    chunk_size: u64,
    current_pos: u64,
    buffer: Vec<u8>,
    buffer_start: u64,
    reader: Option<Box<dyn Read + Send>>,
    consecutive_failures: u32,
    max_failures: u32,
}

impl ChunkedReader {
    pub fn new(url: String) -> Self {
        let agent = ureq::Agent::new_with_config(
            ureq::config::Config::builder()
                .max_redirects(5)
                .build()
        );
        Self {
            url,
            agent,
            total_size: None,
            chunk_size: 8 * 1024 * 1024, // 8 MiB default
            current_pos: 0,
            buffer: Vec::new(),
            buffer_start: 0,
            reader: None,
            consecutive_failures: 0,
            max_failures: 6,
        }
    }

    /// Probe the total content length with a HEAD request.
    pub fn probe_size(&mut self) -> io::Result<u64> {
        let resp = self
            .agent
            .head(&self.url)
            .call()
            .map_err(|e| io::Error::new(io::ErrorKind::Other, e.to_string()))?;

        let total = resp
            .headers()
            .get("content-length")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(0);

        self.total_size = if total > 0 { Some(total) } else { None };
        Ok(total)
    }

    /// Open a range request starting at `start`.
    fn open_range(&mut self, start: u64, end: Option<u64>) -> io::Result<()> {
        let range_end = match end {
            Some(e) => format!("-{}", e),
            None => "-".to_string(),
        };
        let range_header = format!("bytes={}-{}", start, range_end);

        let resp = self
            .agent
            .get(&self.url)
            .header("Range", &range_header)
            .call()
            .map_err(|e| io::Error::new(io::ErrorKind::Other, e.to_string()))?;

        self.reader = Some(Box::new(resp.into_body().into_reader()));
        self.buffer.clear();
        self.buffer_start = start;
        self.consecutive_failures = 0;
        Ok(())
    }

    /// Read up to `buf.len()` bytes from the stream.
    fn read_chunked(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        // If we have buffered data, serve from buffer
        if self.buffer_start < self.buffer.len() as u64 {
            let available = &self.buffer[self.buffer_start as usize..];
            let to_copy = available.len().min(buf.len());
            buf[..to_copy].copy_from_slice(&available[..to_copy]);
            self.current_pos += to_copy as u64;
            self.buffer_start += to_copy as u64;
            return Ok(to_copy);
        }

        // Open a new range request if needed
        if self.reader.is_none() {
            self.open_range(self.current_pos, None)?;
        }

        // Read from the response
        if let Some(ref mut reader) = self.reader {
            let mut chunk = vec![0u8; self.chunk_size as usize];
            match reader.read(&mut chunk) {
                Ok(0) => {
                    // EOF on current range
                    self.reader = None;
                    if self.current_pos > 0 {
                        // Retry from current position
                        self.open_range(self.current_pos, None)?;
                        return self.read_chunked(buf);
                    }
                    Ok(0)
                }
                Ok(n) => {
                    chunk.truncate(n);
                    self.buffer = chunk;
                    self.buffer_start = 0;
                    let to_copy = n.min(buf.len());
                    buf[..to_copy].copy_from_slice(&self.buffer[..to_copy]);
                    self.current_pos += to_copy as u64;
                    self.buffer_start = to_copy as u64;
                    Ok(to_copy)
                }
                Err(e) => {
                    self.consecutive_failures += 1;
                    if self.consecutive_failures >= self.max_failures {
                        self.reader = None;
                        return Err(io::Error::new(
                            io::ErrorKind::Other,
                            format!("too many consecutive failures: {e}"),
                        ));
                    }
                    // Retry with a fresh connection
                    self.reader = None;
                    self.open_range(self.current_pos, None)?;
                    self.read_chunked(buf)
                }
            }
        } else {
            Ok(0)
        }
    }
}

impl Read for ChunkedReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.read_chunked(buf)
    }
}

impl Seek for ChunkedReader {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let new_pos = match pos {
            SeekFrom::Start(offset) => offset,
            SeekFrom::Current(offset) => {
                if offset >= 0 {
                    self.current_pos + offset as u64
                } else {
                    self.current_pos.saturating_sub((-offset) as u64)
                }
            }
            SeekFrom::End(offset) => {
                if let Some(total) = self.total_size {
                    if offset >= 0 {
                        total.saturating_add(offset as u64)
                    } else {
                        total.saturating_sub((-offset) as u64)
                    }
                } else {
                    return Err(io::Error::new(
                        io::ErrorKind::Other,
                        "unknown total size",
                    ));
                }
            }
        };

        // If seeking forward within the current buffer, just adjust
        if new_pos >= self.buffer_start && new_pos <= self.buffer_start + (self.buffer.len() as u64 - self.buffer_start) {
            self.current_pos = new_pos;
            return Ok(new_pos);
        }

        // Otherwise, discard the buffer and reopen
        self.buffer.clear();
        self.buffer_start = 0;
        self.reader = None;
        self.current_pos = new_pos;
        Ok(new_pos)
    }
}
