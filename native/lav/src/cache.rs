use std::collections::VecDeque;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// A single cached packet.
#[derive(Clone)]
pub struct CachedPacket {
    pub data: Vec<u8>,
    pub pts_us: i64,
    pub duration_us: i64,
    pub is_keyframe: bool,
}

/// Rolling packet ring cache. Keeps recent encoded packets, bounded by
/// duration and byte size. Evicts old GOPs (groups of pictures) when
/// over budget.
pub struct PacketRing {
    packets: VecDeque<CachedPacket>,
    total_bytes: usize,
    max_duration: Duration,
    max_bytes: usize,
    start_instant: Option<Instant>,
    last_pts_us: i64,
}

impl PacketRing {
    pub fn new(max_duration_us: i64, max_bytes: usize) -> Self {
        Self {
            packets: VecDeque::new(),
            total_bytes: 0,
            max_duration: Duration::from_micros(max_duration_us.max(0) as u64),
            max_bytes,
            start_instant: None,
            last_pts_us: 0,
        }
    }

    /// Push a packet into the ring.
    pub fn push(&mut self, packet: CachedPacket) {
        if self.start_instant.is_none() {
            self.start_instant = Some(Instant::now());
        }
        self.total_bytes += packet.data.len();
        self.last_pts_us = packet.pts_us;
        self.packets.push_back(packet);
        self.evict();
    }

    /// Drain packets starting from a given PTS. Returns keyframe-aligned
    /// packets from the nearest keyframe at or before `from_pts`.
    pub fn drain_from(&mut self, from_pts: i64) -> Vec<CachedPacket> {
        // Find the nearest keyframe at or before from_pts
        let mut start_idx = 0;
        for (i, pkt) in self.packets.iter().enumerate() {
            if pkt.pts_us > from_pts {
                break;
            }
            if pkt.is_keyframe {
                start_idx = i;
            }
        }

        let mut result = Vec::new();
        for _ in start_idx..self.packets.len() {
            if let Some(pkt) = self.packets.pop_front() {
                self.total_bytes -= pkt.data.len();
                result.push(pkt);
            }
        }
        result
    }

    /// Check if the ring has packets for the given PTS.
    pub fn has_pts(&self, pts: i64) -> bool {
        self.packets.iter().any(|p| p.pts_us == pts)
    }

    /// Get the PTS range covered by the cache.
    pub fn pts_range(&self) -> Option<(i64, i64)> {
        let front = self.packets.front()?;
        let back = self.packets.back()?;
        Some((front.pts_us, back.pts_us))
    }

    fn evict(&mut self) {
        let now = Instant::now();

        // Evict by duration
        while let Some(front) = self.packets.front() {
            if let Some(start) = self.start_instant {
                let age = now.duration_since(start);
                let pkt_age = Duration::from_micros(front.pts_us.max(0) as u64);
                if age - pkt_age > self.max_duration {
                    if let Some(pkt) = self.packets.pop_front() {
                        self.total_bytes -= pkt.data.len();
                        continue;
                    }
                }
            }
            break;
        }

        // Evict by bytes - drop oldest GOPs
        while self.total_bytes > self.max_bytes && !self.packets.is_empty() {
            // Find the next keyframe to drop a complete GOP
            let mut drop_to = 0;
            for (i, pkt) in self.packets.iter().enumerate().skip(1) {
                if pkt.is_keyframe {
                    drop_to = i;
                    break;
                }
            }
            if drop_to == 0 {
                // No more keyframes, drop everything
                drop_to = self.packets.len();
            }
            for _ in 0..drop_to {
                if let Some(pkt) = self.packets.pop_front() {
                    self.total_bytes -= pkt.data.len();
                }
            }
        }
    }

    pub fn clear(&mut self) {
        self.packets.clear();
        self.total_bytes = 0;
        self.start_instant = None;
    }

    pub fn is_empty(&self) -> bool {
        self.packets.is_empty()
    }

    pub fn packet_count(&self) -> usize {
        self.packets.len()
    }
}

/// Thread-safe wrapper around PacketRing.
pub struct SharedPacketRing {
    inner: Mutex<PacketRing>,
}

impl SharedPacketRing {
    pub fn new(max_duration_us: i64, max_bytes: usize) -> Self {
        Self {
            inner: Mutex::new(PacketRing::new(max_duration_us, max_bytes)),
        }
    }

    pub fn push(&self, packet: CachedPacket) {
        if let Ok(mut ring) = self.inner.lock() {
            ring.push(packet);
        }
    }

    pub fn drain_from(&self, from_pts: i64) -> Vec<CachedPacket> {
        self.inner
            .lock()
            .map(|mut r| r.drain_from(from_pts))
            .unwrap_or_default()
    }

    pub fn pts_range(&self) -> Option<(i64, i64)> {
        self.inner.lock().ok().and_then(|r| r.pts_range())
    }

    pub fn clear(&self) {
        if let Ok(mut ring) = self.inner.lock() {
            ring.clear();
        }
    }
}
