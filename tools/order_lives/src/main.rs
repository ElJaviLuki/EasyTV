//! Reorder `lives` in channels_clean.json by:
//! 1) server priority Manolo1…Manolo6
//! 2) probed TS quality = resolution (w×h) × bitrate
//! 3) name stability (no-backup before backups)
//!
//! Probing reads a small HTTP prefix of each unique stream_id, demuxes MPEG-TS,
//! parses H.264 SPS for resolution, and estimates bitrate from PCR deltas.
//! Results are cached in secrets/ts_probe_cache.json.

use anyhow::{anyhow, Context, Result};
use h264_reader::nal::sps::SeqParameterSet;
use h264_reader::rbsp;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

const TS_PACKET: usize = 188;
const SYNC: u8 = 0x47;
/// Bytes to pull per unique stream (enough for PAT/PMT/SPS + a few PCRs).
const PROBE_BYTES: usize = 1_500_000;
const SERVER_ORDER: &[&str] = &[
    "Manolo1", "Manolo2", "Manolo3", "Manolo4", "Manolo5", "Manolo6",
];

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct Probe {
    width: u32,
    height: u32,
    /// Estimated bits/s from PCR window over the probed prefix (0 if unknown).
    bitrate: u64,
    codec: String,
    error: Option<String>,
}

impl Probe {
    fn pixels(&self) -> u64 {
        u64::from(self.width) * u64::from(self.height)
    }
    /// Higher is better.
    fn score(&self) -> u64 {
        let px = self.pixels().max(1);
        let br = self.bitrate.max(1);
        px.saturating_mul(br)
    }
}

#[derive(Debug, Deserialize)]
struct CatalogFile {
    name: Option<String>,
    live: Option<Vec<CatalogLive>>,
}

#[derive(Debug, Deserialize)]
struct CatalogLive {
    name: Option<String>,
    url: Option<String>,
    stream_id: Option<Value>,
}

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../.."))
}

fn server_rank(server: &str) -> usize {
    SERVER_ORDER
        .iter()
        .position(|s| *s == server)
        .unwrap_or(SERVER_ORDER.len() + 10)
}

fn stream_id_from_url(url: &str) -> Option<String> {
    let re = Regex::new(r"/(\d+)\.ts(?:\?|$)").ok()?;
    re.captures(url).map(|c| c[1].to_string())
}

fn stability_rank(name: &str) -> u32 {
    let n = name.to_ascii_lowercase();
    if n.contains("backup 2") || n.contains("backup2") {
        return 50;
    }
    if n.contains("backup 1") || n.contains("backup1") {
        return 40;
    }
    if n.contains("backup") {
        return 35;
    }
    if n.contains("solo eventos") || n.contains("solo evento") {
        return 30;
    }
    if n.contains("multiaudio backup") {
        return 28;
    }
    if n.contains("multiaudio") {
        return 20;
    }
    if n.contains("(zap)") {
        return 8;
    }
    if n.contains("(o)") {
        return 0;
    }
    if n.contains("(ty)") || n.contains("(av)") {
        return 5;
    }
    10
}

fn load_url_names(catalogs: &Path) -> HashMap<String, String> {
    let mut map = HashMap::new();
    let Ok(rd) = fs::read_dir(catalogs) else {
        return map;
    };
    for ent in rd.flatten() {
        let path = ent.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let Ok(text) = fs::read_to_string(&path) else {
            continue;
        };
        let Ok(cat) = serde_json::from_str::<CatalogFile>(&text) else {
            continue;
        };
        for live in cat.live.unwrap_or_default() {
            let Some(url) = live.url else { continue };
            if let Some(name) = live.name {
                map.insert(url, name);
            }
        }
    }
    map
}

/// Download a bounded prefix of a live TS.
fn fetch_prefix(url: &str, max_bytes: usize) -> Result<Vec<u8>> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(20))
        .connect_timeout(Duration::from_secs(8))
        .user_agent("VLC/3.0.20 LibVLC/3.0.20")
        .no_proxy() // Windows system proxy often returns 407 for IPTV hosts
        .build()?;

    let mut resp = client
        .get(url)
        .header("Range", format!("bytes=0-{}", max_bytes.saturating_sub(1)))
        .send()
        .with_context(|| format!("GET {url}"))?;

    // Some IPTV endpoints ignore/reject Range — retry plain GET.
    if resp.status().as_u16() == 416 || resp.status().as_u16() == 405 {
        resp = client.get(url).send().with_context(|| format!("GET retry {url}"))?;
    }
    if !resp.status().is_success() && resp.status().as_u16() != 206 {
        return Err(anyhow!("HTTP {} for {url}", resp.status()));
    }

    use std::io::Read;
    let mut buf = Vec::with_capacity(max_bytes.min(64 * 1024));
    let mut left = max_bytes;
    while left > 0 {
        let mut chunk = vec![0u8; left.min(64 * 1024)];
        let n = resp.read(&mut chunk)?;
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&chunk[..n]);
        left -= n;
    }
    if buf.len() < TS_PACKET * 10 {
        return Err(anyhow!("too few bytes ({}) from {url}", buf.len()));
    }
    Ok(buf)
}

fn find_sync(data: &[u8]) -> Option<usize> {
    data.windows(TS_PACKET * 3)
        .position(|w| {
            w[0] == SYNC && w[TS_PACKET] == SYNC && w[TS_PACKET * 2] == SYNC
        })
}

/// Extract 27 MHz PCR base from adaptation field, if present.
fn read_pcr(pkt: &[u8]) -> Option<u64> {
    if pkt.len() < TS_PACKET || pkt[0] != SYNC {
        return None;
    }
    let afc = (pkt[3] >> 4) & 0x03;
    if afc != 0b10 && afc != 0b11 {
        return None;
    }
    let adapt_len = pkt[4] as usize;
    if adapt_len < 7 || 5 + adapt_len > TS_PACKET {
        return None;
    }
    let flags = pkt[5];
    if flags & 0x10 == 0 {
        return None; // PCR_flag
    }
    // PCR base: 33 bits
    let b = &pkt[6..12];
    let pcr_base = ((b[0] as u64) << 25)
        | ((b[1] as u64) << 17)
        | ((b[2] as u64) << 9)
        | ((b[3] as u64) << 1)
        | ((b[4] as u64) >> 7);
    let pcr_ext = (((b[4] as u64) & 1) << 8) | (b[5] as u64);
    Some(pcr_base * 300 + pcr_ext)
}

fn payload<'a>(pkt: &'a [u8]) -> Option<&'a [u8]> {
    if pkt.len() < TS_PACKET || pkt[0] != SYNC {
        return None;
    }
    let afc = (pkt[3] >> 4) & 0x03;
    let mut off = 4usize;
    if afc == 0b10 {
        return None; // adaptation only
    }
    if afc == 0b11 {
        let al = pkt[4] as usize;
        off = 5 + al;
        if off >= TS_PACKET {
            return None;
        }
    }
    Some(&pkt[off..TS_PACKET])
}

fn annex_b_nals(data: &[u8]) -> Vec<&[u8]> {
    let mut nals = Vec::new();
    let mut i = 0usize;
    while i + 4 < data.len() {
        // start code 00 00 01 or 00 00 00 01
        let sc = if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            3
        } else if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1 {
            4
        } else {
            i += 1;
            continue;
        };
        let start = i + sc;
        let mut j = start;
        while j + 3 < data.len() {
            if data[j] == 0
                && data[j + 1] == 0
                && (data[j + 2] == 1 || (data[j + 2] == 0 && j + 3 < data.len() && data[j + 3] == 1))
            {
                break;
            }
            j += 1;
        }
        if j > start {
            nals.push(&data[start..j]);
        }
        i = j;
    }
    nals
}

fn parse_sps_dims(sps_nal: &[u8]) -> Option<(u32, u32)> {
    // Full NAL unit (header + payload), no start code.
    if sps_nal.is_empty() {
        return None;
    }
    let nal_type = sps_nal[0] & 0x1F;
    if nal_type != 7 {
        return None;
    }
    let rbsp = rbsp::decode_nal(sps_nal).ok()?;
    let sps = SeqParameterSet::from_bits(rbsp::BitReader::new(rbsp.as_ref())).ok()?;
    let (w, h) = sps.pixel_dimensions().ok()?;
    // Reject garbage SPS (corrupt NALs sometimes decode to nonsense).
    if w < 160 || h < 120 || w > 7680 || h > 4320 {
        return None;
    }
    Some((w, h))
}

fn probe_ts_bytes(data: &[u8]) -> Probe {
    let mut probe = Probe {
        codec: "unknown".into(),
        ..Default::default()
    };

    let Some(start) = find_sync(data) else {
        probe.error = Some("no TS sync".into());
        return probe;
    };

    let mut first_pcr: Option<(usize, u64)> = None;
    let mut last_pcr: Option<(usize, u64)> = None;
    let mut pes_buf: Vec<u8> = Vec::new();

    let slice = &data[start..];
    let n_packets = slice.len() / TS_PACKET;
    for pi in 0..n_packets {
        let pkt = &slice[pi * TS_PACKET..(pi + 1) * TS_PACKET];
        if let Some(pcr) = read_pcr(pkt) {
            let off = start + pi * TS_PACKET;
            if first_pcr.is_none() {
                first_pcr = Some((off, pcr));
            }
            last_pcr = Some((off, pcr));
        }

        let pusi = (pkt[1] & 0x40) != 0;
        let Some(pl) = payload(pkt) else { continue };

        // Accumulate all payloads and scan for SPS (simple, robust enough for probe).
        if pusi {
            // flush previous
            if pes_buf.len() > 6 {
                scan_pes_for_sps(&pes_buf, &mut probe);
            }
            pes_buf.clear();
            // skip PES header if present (0x000001)
            if pl.len() >= 9 && pl[0] == 0 && pl[1] == 0 && pl[2] == 1 {
                let hdr_len = 9 + pl[8] as usize;
                if pl.len() > hdr_len {
                    pes_buf.extend_from_slice(&pl[hdr_len..]);
                }
            } else {
                pes_buf.extend_from_slice(pl);
            }
        } else if !pes_buf.is_empty() {
            pes_buf.extend_from_slice(pl);
            if pes_buf.len() > 512 * 1024 {
                scan_pes_for_sps(&pes_buf, &mut probe);
                pes_buf.clear();
            }
        }

        if probe.width > 0 {
            // keep scanning a bit for better PCR window
        }
    }
    if !pes_buf.is_empty() {
        scan_pes_for_sps(&pes_buf, &mut probe);
    }

    // Also brute-scan whole buffer for Annex-B SPS (some providers pack oddly).
    if probe.width == 0 {
        for nal in annex_b_nals(slice) {
            if let Some((w, h)) = parse_sps_dims(nal) {
                probe.width = w;
                probe.height = h;
                probe.codec = "h264".into();
                break;
            }
        }
    }

    if let (Some((o0, p0)), Some((o1, p1))) = (first_pcr, last_pcr) {
        if p1 > p0 && o1 > o0 {
            let bits = (o1 - o0) as u64 * 8;
            let dt = (p1 - p0) as f64 / 27_000_000.0;
            if dt > 0.01 {
                probe.bitrate = (bits as f64 / dt) as u64;
            }
        }
    }

    if probe.width == 0 {
        probe.error = Some(probe.error.unwrap_or_else(|| "no SPS found".into()));
    }
    probe
}

fn scan_pes_for_sps(pes: &[u8], probe: &mut Probe) {
    if probe.width > 0 {
        return;
    }
    for nal in annex_b_nals(pes) {
        if let Some((w, h)) = parse_sps_dims(nal) {
            probe.width = w;
            probe.height = h;
            probe.codec = "h264".into();
            return;
        }
    }
}

fn probe_url(url: &str) -> Probe {
    match fetch_prefix(url, PROBE_BYTES) {
        Ok(bytes) => probe_ts_bytes(&bytes),
        Err(e) => Probe {
            error: Some(e.to_string()),
            ..Default::default()
        },
    }
}

/// Try URLs in order until one yields SPS dimensions.
fn probe_urls(urls: &[String]) -> Probe {
    let mut last = Probe {
        error: Some("no urls".into()),
        ..Default::default()
    };
    // Cap failover attempts — same credentials rarely help past 2 hosts.
    for (i, url) in urls.iter().take(2).enumerate() {
        if i > 0 {
            thread::sleep(Duration::from_millis(150));
        }
        let p = probe_url(url);
        if p.width > 0 {
            return p;
        }
        last = p;
        // Provider 407 = stream/auth denied; other hosts usually same.
        if last
            .error
            .as_deref()
            .map(|e| e.contains("407"))
            .unwrap_or(false)
        {
            break;
        }
    }
    last
}

fn load_cache(path: &Path) -> HashMap<String, Probe> {
    fs::read_to_string(path)
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

fn save_cache(path: &Path, cache: &HashMap<String, Probe>) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let text = serde_json::to_string_pretty(cache)?;
    fs::write(path, text + "\n")?;
    Ok(())
}

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    // Debug helpers: --probe-url URL | --probe-file PATH
    if let Some(i) = args.iter().position(|a| a == "--probe-url") {
        let url = args.get(i + 1).ok_or_else(|| anyhow!("--probe-url needs URL"))?;
        let p = probe_url(url);
        println!("{}", serde_json::to_string_pretty(&p)?);
        return Ok(());
    }
    if let Some(i) = args.iter().position(|a| a == "--probe-file") {
        let path = args.get(i + 1).ok_or_else(|| anyhow!("--probe-file needs PATH"))?;
        let bytes = fs::read(path)?;
        let p = probe_ts_bytes(&bytes);
        println!("{}", serde_json::to_string_pretty(&p)?);
        return Ok(());
    }

    let root = repo_root();
    let channels_path = root.join("secrets/channels_clean.json");
    let catalogs = root.join("secrets/catalogs");
    let cache_path = root.join("secrets/ts_probe_cache.json");

    let mut data: Value = serde_json::from_str(
        &fs::read_to_string(&channels_path)
            .with_context(|| format!("read {}", channels_path.display()))?,
    )?;
    let url_names = load_url_names(&catalogs);
    let mut cache = load_cache(&cache_path);

    let canales = data
        .get_mut("canales")
        .and_then(|c| c.as_array_mut())
        .ok_or_else(|| anyhow!("missing canales[]"))?;

    // Prefer probing Manolo1 URL per stream_id; keep failover list.
    let mut urls_for_sid: HashMap<String, Vec<(usize, String)>> = HashMap::new();
    for canal in canales.iter() {
        let Some(lives) = canal.get("lives").and_then(|l| l.as_array()) else {
            continue;
        };
        for live in lives {
            let url = live.get("url").and_then(|u| u.as_str()).unwrap_or("");
            let server = live.get("server").and_then(|s| s.as_str()).unwrap_or("");
            let Some(sid) = stream_id_from_url(url) else {
                continue;
            };
            let rank = server_rank(server);
            let entry = urls_for_sid.entry(sid).or_default();
            if !entry.iter().any(|(_, u)| u == url) {
                entry.push((rank, url.to_string()));
            }
        }
    }
    for urls in urls_for_sid.values_mut() {
        urls.sort_by_key(|(rank, _)| *rank);
    }

    // Drop failed / empty / garbage probes so we retry them.
    cache.retain(|_, p| p.width >= 160 && p.height >= 120);

    let to_probe: Vec<(String, Vec<String>)> = urls_for_sid
        .into_iter()
        .filter_map(|(sid, ranked)| {
            if cache.contains_key(&sid) {
                return None;
            }
            let urls: Vec<String> = ranked.into_iter().map(|(_, u)| u).collect();
            if urls.is_empty() {
                None
            } else {
                Some((sid, urls))
            }
        })
        .collect();

    eprintln!(
        "Probing {} unique stream_ids (cache has {})…",
        to_probe.len(),
        cache.len()
    );

    let cache_mu = Arc::new(Mutex::new(cache));
    let done = Arc::new(Mutex::new(0usize));
    let total = to_probe.len();
    let concurrency = std::env::var("ORDER_LIVES_JOBS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(2usize);

    // Bounded thread pool — IPTV endpoints 502/503 under high concurrency.
    let (tx, rx) = std::sync::mpsc::channel::<(String, Vec<String>)>();
    for item in to_probe {
        tx.send(item).unwrap();
    }
    drop(tx);
    let rx = Arc::new(Mutex::new(rx));

    let mut handles = Vec::new();
    for _ in 0..concurrency {
        let rx = Arc::clone(&rx);
        let cache_mu = Arc::clone(&cache_mu);
        let done = Arc::clone(&done);
        let cache_path_w = cache_path.clone();
        handles.push(thread::spawn(move || {
            loop {
                let job = { rx.lock().unwrap().recv() };
                let Ok((sid, urls)) = job else { break };
                // pace requests — IPTV gateways hate bursts
                thread::sleep(Duration::from_millis(250));
                let p = probe_urls(&urls);
                let n = {
                    let mut g = cache_mu.lock().unwrap();
                    g.insert(sid.clone(), p.clone());
                    let mut d = done.lock().unwrap();
                    *d += 1;
                    let n = *d;
                    if n % 25 == 0 {
                        let _ = save_cache(&cache_path_w, &g);
                    }
                    n
                };
                if n % 10 == 0 || n == total || p.width == 0 {
                    eprintln!(
                        "  [{n}/{total}] sid={sid} {}x{} ~{} kbps {:?}",
                        p.width,
                        p.height,
                        p.bitrate / 1000,
                        p.error
                    );
                }
            }
        }));
    }
    for h in handles {
        h.join().unwrap();
    }

    let cache = Arc::try_unwrap(cache_mu)
        .unwrap_or_else(|a| Mutex::new(a.lock().unwrap().clone()))
        .into_inner()
        .unwrap();
    save_cache(&cache_path, &cache)?;

    let mut reordered = 0usize;
    for canal in canales.iter_mut() {
        let Some(lives_v) = canal.get_mut("lives") else {
            continue;
        };
        let Some(lives) = lives_v.as_array_mut() else {
            continue;
        };
        let before: Vec<String> = lives
            .iter()
            .filter_map(|l| l.get("url").and_then(|u| u.as_str()).map(|s| s.to_string()))
            .collect();

        lives.sort_by(|a, b| {
            let url_a = a.get("url").and_then(|u| u.as_str()).unwrap_or("");
            let url_b = b.get("url").and_then(|u| u.as_str()).unwrap_or("");
            let srv_a = a.get("server").and_then(|s| s.as_str()).unwrap_or("");
            let srv_b = b.get("server").and_then(|s| s.as_str()).unwrap_or("");
            let sid_a = stream_id_from_url(url_a).unwrap_or_default();
            let sid_b = stream_id_from_url(url_b).unwrap_or_default();
            let pa = cache.get(&sid_a).cloned().unwrap_or_default();
            let pb = cache.get(&sid_b).cloned().unwrap_or_default();
            let name_a = url_names.get(url_a).map(|s| s.as_str()).unwrap_or("");
            let name_b = url_names.get(url_b).map(|s| s.as_str()).unwrap_or("");

            server_rank(srv_a)
                .cmp(&server_rank(srv_b))
                .then_with(|| pb.score().cmp(&pa.score())) // higher quality first
                .then_with(|| stability_rank(name_a).cmp(&stability_rank(name_b)))
                .then_with(|| url_a.cmp(url_b))
        });

        // de-dupe by url preserving order
        let mut seen = HashSet::new();
        lives.retain(|l| {
            let u = l.get("url").and_then(|x| x.as_str()).unwrap_or("");
            if u.is_empty() || !seen.insert(u.to_string()) {
                false
            } else {
                // strip to server+url only
                true
            }
        });
        for live in lives.iter_mut() {
            if let Some(obj) = live.as_object_mut() {
                obj.retain(|k, _| k == "server" || k == "url");
            }
        }

        let after: Vec<String> = lives
            .iter()
            .filter_map(|l| l.get("url").and_then(|u| u.as_str()).map(|s| s.to_string()))
            .collect();
        if after != before {
            reordered += 1;
        }
    }

    if let Some(note) = data.get_mut("grouping_note").and_then(|n| n.as_str()).map(|s| s.to_string()) {
        if !note.contains("TS probe") {
            data["grouping_note"] = Value::String(format!(
                "{note} Lives ordered: Manolo1…6, then probed TS resolution×bitrate, then name stability."
            ));
        }
    }

    let out = serde_json::to_string_pretty(&data)?;
    fs::write(&channels_path, out + "\n")?;
    eprintln!(
        "OK -> {} (reordered {reordered} canales, cache {})",
        channels_path.display(),
        cache_path.display()
    );
    Ok(())
}
