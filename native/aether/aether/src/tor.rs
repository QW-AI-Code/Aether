use std::net::SocketAddr;
use std::path::PathBuf;

use crate::error::{AetherError, Result};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Off,
    Chain,
    Reverse,
    Only,
}

impl Mode {
    pub fn label(self) -> &'static str {
        match self {
            Mode::Off => "off",
            Mode::Chain => "tor through the tunnel",
            Mode::Reverse => "the tunnel through tor",
            Mode::Only => "tor alone",
        }
    }
}

pub fn mode() -> Mode {
    let raw = match std::env::var("AETHER_TOR") {
        Ok(value) => value.trim().to_lowercase(),
        Err(_) => return Mode::Off,
    };

    match raw.as_str() {
        "" | "0" | "off" | "false" | "no" => Mode::Off,
        "reverse" | "rev" | "warp-over-tor" => Mode::Reverse,
        "only" | "alone" | "tor-only" | "direct" => Mode::Only,
        _ => Mode::Chain,
    }
}

pub fn listen_address() -> SocketAddr {
    std::env::var("AETHER_TOR_BIND")
        .ok()
        .and_then(|value| value.trim().parse().ok())
        .unwrap_or_else(|| "127.0.0.1:1820".parse().expect("a literal address"))
}

pub fn http_listen_address() -> Option<SocketAddr> {
    std::env::var("AETHER_TOR_HTTP")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty() && value != "off")
        .and_then(|value| value.parse().ok())
}

pub fn state_dir(base_config: &str) -> PathBuf {
    if let Some(dir) = std::env::var("AETHER_TOR_DIR")
        .ok()
        .filter(|d| !d.trim().is_empty())
    {
        return PathBuf::from(dir.trim());
    }

    let config = PathBuf::from(base_config);
    let stem = config
        .file_stem()
        .map(|name| name.to_string_lossy().to_string())
        .unwrap_or_else(|| "aether".to_string());

    match config.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent.join(format!("{stem}-tor")),
        _ => PathBuf::from(format!("{stem}-tor")),
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Bridges {
    Disabled,
    Manual(Vec<String>),
    Auto { forced: bool },
}

pub fn bridges_from_file() -> Vec<String> {
    let path = match std::env::var("AETHER_TOR_BRIDGE_FILE") {
        Ok(value) if !value.trim().is_empty() => value.trim().to_string(),
        _ => return Vec::new(),
    };

    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) => {
            log::warn!("[-] cannot read the bridge file {path}: {e}");
            return Vec::new();
        }
    };

    let lines = read_bridge_lines(&text);
    if lines.is_empty() {
        log::warn!("[-] the bridge file {path} held no bridge line");
    } else {
        log::info!("[+] {} bridge(s) read from {path}", lines.len());
    }
    lines
}

pub fn read_bridge_lines(text: &str) -> Vec<String> {
    text.lines()
        .map(|line| line.trim())
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(|line| {
            line.strip_prefix("Bridge ")
                .or_else(|| line.strip_prefix("bridge "))
                .unwrap_or(line)
                .trim()
                .to_string()
        })
        .filter(|line| !line.is_empty())
        .collect()
}

pub fn bridges() -> Bridges {
    let from_file = bridges_from_file();
    let raw = std::env::var("AETHER_TOR_BRIDGES").unwrap_or_default();
    let trimmed = raw.trim();

    if !from_file.is_empty() {
        let mut lines = from_file;
        if !matches!(
            trimmed.to_lowercase().as_str(),
            "" | "off"
                | "no"
                | "0"
                | "false"
                | "none"
                | "auto"
                | "on"
                | "1"
                | "yes"
                | "true"
                | "force"
        ) {
            lines.extend(read_bridge_lines(&trimmed.replace(';', "\n")));
        }
        return Bridges::Manual(lines);
    }

    match trimmed.to_lowercase().as_str() {
        "" => Bridges::Auto { forced: false },
        "off" | "no" | "0" | "false" | "none" => Bridges::Disabled,
        "auto" | "on" | "1" | "yes" | "true" | "force" => Bridges::Auto { forced: true },
        _ => {
            let lines: Vec<String> = trimmed
                .split(['\n', ';'])
                .map(|line| line.trim().to_string())
                .filter(|line| !line.is_empty())
                .collect();

            if lines.is_empty() {
                Bridges::Auto { forced: false }
            } else {
                Bridges::Manual(lines)
            }
        }
    }
}

pub fn bridge_lines() -> Vec<String> {
    match bridges() {
        Bridges::Manual(lines) => lines,
        _ => Vec::new(),
    }
}

pub fn transports() -> Vec<(String, String)> {
    std::env::var("AETHER_TOR_PT")
        .unwrap_or_default()
        .split(['\n', ';'])
        .filter_map(|entry| {
            let entry = entry.trim();
            if entry.is_empty() {
                return None;
            }
            match entry.split_once('=') {
                Some((protocol, path)) => {
                    Some((protocol.trim().to_string(), path.trim().to_string()))
                }
                None => Some(("obfs4".to_string(), entry.to_string())),
            }
        })
        .collect()
}

#[cfg(not(feature = "tor"))]
fn unsupported() -> AetherError {
    AetherError::Other(
        "this build has no tor support; rebuild with `cargo build --release --features tor`".into(),
    )
}

#[cfg(not(feature = "tor"))]
pub async fn run_chain(_through: SocketAddr, _state: PathBuf) -> Result<()> {
    Err(unsupported())
}

#[cfg(not(feature = "tor"))]
pub async fn run_only(
    _listener: tokio::net::TcpListener,
    _listen: SocketAddr,
    _state: PathBuf,
) -> Result<()> {
    Err(unsupported())
}

#[cfg(not(feature = "tor"))]
pub async fn start_reverse(_state: PathBuf) -> Result<(SocketAddr, tokio::task::AbortHandle)> {
    Err(unsupported())
}

#[cfg(feature = "tor")]
pub use with_tor::{run_chain, run_only, start_reverse};

#[cfg(feature = "tor")]
mod with_tor {
    use super::*;

    use std::collections::BTreeMap;
    use std::path::Path;
    use std::time::Duration;

    use arti_client::config::{BridgeConfigBuilder, CfgPath, TorClientConfigBuilder};
    use arti_client::{TorClient, TorClientConfig};
    use tokio::net::TcpListener;
    use tor_chanmgr::ProxyProtocol;
    use tor_rtcompat::PreferredRuntime;

    use crate::bridges::{Plan, Transport};

    pub type Client = std::sync::Arc<TorClient<PreferredRuntime>>;

    const BOOTSTRAP_RETRY: Duration = Duration::from_secs(10);
    const DIRECT_PROBE: Duration = Duration::from_secs(75);
    const BRIDGE_PROBE: Duration = Duration::from_secs(360);
    const STALL_LIMIT: Duration = Duration::from_secs(75);
    const STALL_CHECK: Duration = Duration::from_secs(5);
    const PROOF_LIMIT: Duration = Duration::from_secs(45);
    const WAVE_TRIES: usize = 2;
    const FOREVER: usize = usize::MAX;

    fn init_tracing() {
        use tracing_subscriber::filter::EnvFilter;

        let wanted = std::env::var("AETHER_TOR_LOG")
            .ok()
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| match std::env::var("AETHER_LOG_LEVEL").as_deref() {
                Ok("debug") | Ok("trace") => "debug".to_string(),
                _ => "info".to_string(),
            });

        let filter = EnvFilter::try_new(&wanted).unwrap_or_else(|_| EnvFilter::new("info"));
        let _ = tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_writer(std::io::stderr)
            .with_target(true)
            .try_init();
    }
    const REVERSE_ATTEMPTS: usize = 3;

    fn direct_probe() -> Duration {
        std::env::var("AETHER_TOR_DIRECT_SECS")
            .ok()
            .and_then(|value| value.trim().parse().ok())
            .map(Duration::from_secs)
            .unwrap_or(DIRECT_PROBE)
    }

    fn manual_transports() -> Vec<Transport> {
        let mut grouped: Vec<Transport> = Vec::new();

        for (protocol, path) in transports() {
            let path = PathBuf::from(path);
            match grouped.iter_mut().find(|held| held.path == path) {
                Some(held) if !held.protocols.contains(&protocol) => held.protocols.push(protocol),
                Some(_) => {}
                None => grouped.push(Transport {
                    protocols: vec![protocol],
                    path,
                }),
            }
        }

        grouped
    }

    fn announce(plan: &Plan) {
        let mut kinds: BTreeMap<String, usize> = BTreeMap::new();
        for line in &plan.lines {
            let kind = crate::bridges::transport_of(line).unwrap_or_else(|| "vanilla".to_string());
            *kinds.entry(kind).or_default() += 1;
        }

        if !plan.lines.is_empty() {
            let summary: Vec<String> = kinds
                .iter()
                .map(|(kind, count)| format!("{count} {kind}"))
                .collect();
            log::info!(
                "[+] tor has {} bridge(s) {}: {}",
                plan.lines.len(),
                plan.source,
                summary.join(", ")
            );
        }

        for transport in &plan.transports {
            log::info!(
                "[+] {} speaks {}",
                transport.path.display(),
                transport.protocols.join(", ")
            );
        }

        if !plan.missing.is_empty() {
            log::warn!(
                "[-] nothing here can speak {}; {}",
                plan.missing.join(", "),
                crate::bridges::install_hint()
            );
        }
    }

    async fn auto_plan(state: &Path, through: Option<SocketAddr>) -> Plan {
        let (lines, source) = crate::bridges::fetch(state, through).await;
        let lines = crate::bridges::keep_reachable(lines, through).await;
        let plan = crate::bridges::plan(lines, source, manual_transports());
        announce(&plan);
        plan
    }

    fn build_config(
        state: &Path,
        through: Option<SocketAddr>,
        plan: Option<&Plan>,
        slot: Option<&str>,
    ) -> Result<TorClientConfig> {
        prepare_dir(state)?;
        let home = match slot {
            Some(name) => state.join("attempts").join(name),
            None => state.to_path_buf(),
        };
        let cache = home.join("cache");
        let data = home.join("state");
        prepare_dir(&home)?;
        prepare_dir(&cache)?;
        prepare_dir(&data)?;
        // >>> AETHER-APP-PATCH tor-cache-seed
        if slot.is_some() {
            seed::into_slot(state, &cache);
        }
        // <<< AETHER-APP-PATCH tor-cache-seed

        let mut builder = TorClientConfigBuilder::default();
        builder
            .storage()
            .cache_dir(CfgPath::new(cache.to_string_lossy().to_string()));
        builder
            .storage()
            .state_dir(CfgPath::new(data.to_string_lossy().to_string()));

        // Without a tunnel to dial through, tor leaves through the upstream proxy, if there is one.
        if let Some(proxy) = through.or_else(upstream_socks) {
            let parsed: ProxyProtocol = format!("socks5://{proxy}")
                .parse()
                .map_err(|e| AetherError::Other(format!("tor cannot dial through {proxy}: {e}")))?;
            builder.channel().outbound_proxy(parsed);
        } else if let Some(proxy) = crate::upstream::configured() {
            // Connecting directly would leave outside the proxy aether was told to dial through.
            return Err(AetherError::Other(format!(
                "tor cannot dial through the upstream proxy at {}: it takes a socks5 address without a password",
                proxy.endpoint()
            )));
        }

        if let Some(plan) = plan {
            let mut taken = 0usize;
            for line in &plan.lines {
                match line.parse::<BridgeConfigBuilder>() {
                    Ok(bridge) => {
                        builder.bridges().bridges().push(bridge);
                        taken += 1;
                    }
                    Err(e) => log::warn!("[-] skipping a bridge tor cannot read: {e}"),
                }
            }

            if taken == 0 {
                return Err(AetherError::Other(
                    "none of the bridges could be read by tor".into(),
                ));
            }

            for transport in &plan.transports {
                let mut named = Vec::new();
                for protocol in &transport.protocols {
                    named.push(
                        protocol.parse().map_err(|e| {
                            AetherError::Other(format!("transport '{protocol}': {e}"))
                        })?,
                    );
                }

                let mut entry = arti_client::config::pt::TransportConfigBuilder::default();
                entry
                    .protocols(named)
                    .path(CfgPath::new(transport.path.to_string_lossy().to_string()))
                    .run_on_startup(true);
                builder.bridges().transports().push(entry);
            }
        }

        builder
            .build()
            .map_err(|e| AetherError::Other(format!("tor configuration: {e}")))
    }

    struct AbortOnDrop(tokio::task::AbortHandle);

    impl Drop for AbortOnDrop {
        fn drop(&mut self) {
            self.0.abort();
        }
    }

    struct Sleeper {
        client: Client,
        keep: bool,
    }

    impl Drop for Sleeper {
        fn drop(&mut self) {
            if !self.keep {
                self.client.set_dormant(arti_client::DormantMode::Soft);
            }
        }
    }

    fn watch_progress(
        client: Client,
        label: &'static str,
        seen: std::sync::Arc<std::sync::atomic::AtomicU64>,
        started: std::time::Instant,
    ) -> AbortOnDrop {
        let task = tokio::spawn(async move {
            use futures::StreamExt;
            use std::sync::atomic::Ordering;

            let mut events = client.bootstrap_events();
            let mut shown = String::new();
            let mut best = 0u64;

            while let Some(status) = events.next().await {
                let reached = (status.as_frac().max(0.0) * 1000.0).round() as u64;
                if reached > best {
                    best = reached;
                    seen.store(started.elapsed().as_secs(), Ordering::Relaxed);
                }

                let line = status.to_string();
                if line == shown {
                    continue;
                }
                shown = line.clone();

                if status.blocked().is_some() {
                    log::warn!("[-] tor {label}: {line}");
                } else {
                    log::info!("[*] tor {label}: {line}");
                }
            }
        });

        AbortOnDrop(task.abort_handle())
    }

    async fn boot(
        config: TorClientConfig,
        label: &'static str,
        stall: Option<Duration>,
    ) -> Result<Client> {
        use std::sync::atomic::{AtomicU64, Ordering};

        let client = TorClient::builder()
            .config(config)
            .create_unbootstrapped()
            .map_err(|e| AetherError::Other(format!("tor client: {e}")))?;

        let started = std::time::Instant::now();
        let seen = std::sync::Arc::new(AtomicU64::new(0));
        let _watcher = watch_progress(client.clone(), label, seen.clone(), started);
        let mut sleeper = Sleeper {
            client: client.clone(),
            keep: false,
        };

        let booted: Result<()> = match stall {
            None => client
                .bootstrap()
                .await
                .map_err(|e| AetherError::Other(format!("{e}"))),
            Some(limit) => {
                let handle = client.clone();
                let running = async move { handle.bootstrap().await };
                tokio::pin!(running);

                loop {
                    tokio::select! {
                        outcome = &mut running => {
                            break outcome.map_err(|e| AetherError::Other(format!("{e}")));
                        }
                        _ = tokio::time::sleep(STALL_CHECK) => {
                            let quiet = started
                                .elapsed()
                                .as_secs()
                                .saturating_sub(seen.load(Ordering::Relaxed));

                            if quiet >= limit.as_secs() {
                                break Err(AetherError::Other(format!("no headway for {quiet}s")));
                            }
                        }
                    }
                }
            }
        };

        booted?;
        prove(&client, label).await?;

        sleeper.keep = true;
        Ok(client)
    }

    fn proof_target() -> (String, u16) {
        let raw = std::env::var("AETHER_TOR_CHECK").unwrap_or_default();
        let raw = raw.trim();

        if raw.is_empty() {
            return ("check.torproject.org".to_string(), 443);
        }

        match raw.rsplit_once(':') {
            Some((host, port)) => (host.to_string(), port.parse().unwrap_or(443)),
            None => (raw.to_string(), 443),
        }
    }

    async fn prove(client: &Client, label: &'static str) -> Result<()> {
        let (host, port) = proof_target();
        log::info!("[*] tor {label}: trying a way out through {host}:{port}");

        match tokio::time::timeout(PROOF_LIMIT, client.connect((host.as_str(), port))).await {
            Ok(Ok(_stream)) => {
                log::info!("[+] tor {label}: the way out is open");
                Ok(())
            }
            Ok(Err(e)) => Err(AetherError::Other(format!(
                "tor came up but could not reach {host}:{port}: {e}"
            ))),
            Err(_) => Err(AetherError::Other(format!(
                "tor came up but {host}:{port} stayed silent for {}s",
                PROOF_LIMIT.as_secs()
            ))),
        }
    }

    async fn stage(
        state: &Path,
        through: Option<SocketAddr>,
        plan: Option<&Plan>,
        label: &'static str,
        attempts: usize,
        deadline: Option<Duration>,
        stall: Option<Duration>,
        slot: Option<&str>,
    ) -> Result<Client> {
        let mut tried = 0usize;
        loop {
            tried += 1;
            let config = build_config(state, through, plan, slot)?;
            let run = boot(config, label, stall);

            let outcome = match deadline {
                Some(limit) => match tokio::time::timeout(limit, run).await {
                    Ok(result) => result,
                    Err(_) => Err(AetherError::Other(format!(
                        "no answer in {}s",
                        limit.as_secs()
                    ))),
                },
                None => run.await,
            };

            match outcome {
                Ok(client) => return Ok(client),
                Err(e) if tried >= attempts => return Err(e),
                Err(e) => {
                    log::warn!("[-] tor {label} failed: {e}; trying again in {BOOTSTRAP_RETRY:?}");
                    tokio::time::sleep(BOOTSTRAP_RETRY).await;
                }
            }
        }
    }

    async fn establish(state: &Path, through: Option<SocketAddr>, rounds: usize) -> Result<Client> {
        // >>> AETHER-APP-PATCH tor-cache-seed
        // No tor client of this process exists yet, so every cache on disk is at
        // rest and can be copied consistently.
        seed::snapshot(state);
        // <<< AETHER-APP-PATCH tor-cache-seed
        let policy = bridges();

        if let Bridges::Manual(lines) = &policy {
            let plan = crate::bridges::plan(lines.clone(), "given by hand", manual_transports());
            announce(&plan);
            if plan.is_empty() {
                return Err(AetherError::Other(format!(
                    "the bridges you gave need a pluggable transport that is not installed; {}",
                    crate::bridges::install_hint()
                )));
            }
            return stage(
                state,
                through,
                Some(&plan),
                "over your bridges",
                rounds,
                None,
                None,
                None,
            )
            .await;
        }

        let forced = matches!(policy, Bridges::Auto { forced: true });
        let may_fall_back = match (&policy, through) {
            (Bridges::Disabled, _) => false,
            (_, Some(_)) => forced,
            _ => true,
        };

        if !forced {
            let attempts = if may_fall_back { 1 } else { rounds };
            let deadline = may_fall_back.then(direct_probe);
            let tries = stage(
                state,
                through,
                None,
                "reaching the network",
                attempts,
                deadline,
                None,
                Some("direct"),
            );
            match tries.await {
                Ok(client) => return Ok(client),
                Err(e) if !may_fall_back => return Err(e),
                Err(e) => log::warn!("[-] tor could not get through on its own: {e}"),
            }
        }

        if through.is_some() {
            log::warn!(
                "[-] a pluggable transport dials for itself, outside the tunnel; plain bridges go \
                 through it, so they are the ones that work on a network which blocks tor outright"
            );
        }

        let plan = auto_plan(state, through).await;
        if plan.is_empty() {
            return Err(AetherError::Other(format!(
                "tor is blocked on this network and no pluggable transport is installed, so no \
                 bridge can be used; {}",
                crate::bridges::install_hint()
            )));
        }

        if let Some(client) = try_each_transport(state, through, &plan).await? {
            return Ok(client);
        }

        stage(
            state,
            through,
            Some(&plan),
            "over bridges",
            rounds,
            None,
            None,
            Some("all"),
        )
        .await
    }

    fn bridge_probe() -> Duration {
        std::env::var("AETHER_TOR_BRIDGE_SECS")
            .ok()
            .and_then(|value| value.trim().parse().ok())
            .map(Duration::from_secs)
            .unwrap_or(BRIDGE_PROBE)
    }

    fn stall_limit() -> Duration {
        std::env::var("AETHER_TOR_STALL_SECS")
            .ok()
            .and_then(|value| value.trim().parse().ok())
            .map(Duration::from_secs)
            .unwrap_or(STALL_LIMIT)
    }

    async fn try_each_transport(
        state: &Path,
        through: Option<SocketAddr>,
        plan: &Plan,
    ) -> Result<Option<Client>> {
        let worked = crate::bridges::recall(state);
        let waves = crate::bridges::waves(plan, worked.as_deref());
        if waves.is_empty() {
            return Ok(None);
        }

        let deadline = bridge_probe();
        let names: Vec<&str> = waves.iter().map(|(name, _)| name.as_str()).collect();
        log::info!("[*] tor will try {} in turn", names.join(", then "));

        for (name, wave) in &waves {
            for round in 1..=WAVE_TRIES {
                let mut only = wave.clone();
                crate::bridges::shuffle(&mut only.lines);

                let where_to: Vec<String> = only
                    .lines
                    .iter()
                    .filter_map(|line| crate::bridges::address_of(line))
                    .map(|address| address.to_string())
                    .collect();

                log::info!(
                    "[*] {name}: go {round} of {WAVE_TRIES}, dropped after {}s without headway, \
                     over {}",
                    stall_limit().as_secs(),
                    if where_to.is_empty() {
                        format!("{} bridge(s)", only.lines.len())
                    } else {
                        where_to.join(", ")
                    }
                );

                let outcome = stage(
                    state,
                    through,
                    Some(&only),
                    "over bridges",
                    1,
                    Some(deadline),
                    Some(stall_limit()),
                    Some(name.as_str()),
                )
                .await;

                match outcome {
                    Ok(client) => {
                        crate::bridges::remember(state, name);
                        log::info!("[+] {name} got through");
                        return Ok(Some(client));
                    }
                    Err(e) => log::warn!("[-] {name} did not get through: {e}"),
                }
            }
        }

        log::warn!("[-] no transport got through on its own; trying every bridge together");
        Ok(None)
    }

    fn announce_exit(proxy: SocketAddr, what: &'static str) {
        let proxy = crate::socks::dialable(proxy);
        tokio::spawn(async move {
            crate::exitloc::report_through_socks(proxy, what).await;
        });
    }

    /// Binds Tor's http proxy when one is asked for, with --tor-http.
    async fn bind_http() -> Result<Option<TcpListener>> {
        match http_listen_address() {
            Some(address) => Ok(Some(
                crate::socks::bind_listener("tor http proxy", address, "--tor-http").await?,
            )),
            None => Ok(None),
        }
    }

    async fn serve(
        listener: TcpListener,
        http: Option<TcpListener>,
        client: Client,
        kind: &'static str,
    ) -> Result<()> {
        let connector = move |host: String, port: u16| {
            let client = client.clone();
            async move {
                client
                    .connect((host.as_str(), port))
                    .await
                    .map(|stream| {
                        use tokio_util::compat::FuturesAsyncReadCompatExt;
                        stream.compat()
                    })
                    .map_err(std::io::Error::other)
            }
        };

        // Served inside this future, so that it ends with it, letting go of its port, also when
        // the future is dropped as the core ends. Its failing leaves the socks5 one serving.
        let http_connector = connector.clone();
        let http_proxy = async move {
            if let Some(http_listener) = http {
                if let Err(e) = crate::socks::serve_http_connector(
                    http_listener,
                    "tor http proxy",
                    http_connector,
                )
                .await
                {
                    log::error!("[-] the tor http proxy stopped: {e}");
                }
            }
            std::future::pending::<std::convert::Infallible>().await
        };

        tokio::select! {
            outcome = crate::socks::serve_connector(listener, kind, connector) => outcome,
            never = http_proxy => match never {},
        }
    }

    /// The upstream proxy tor dials through when no tunnel carries it, see
    /// [crate::upstream::Upstream::socks_address].
    fn upstream_socks() -> Option<SocketAddr> {
        crate::upstream::configured()?.socks_address()
    }

    /// Says how tor reaches the network when no tunnel carries it: through the upstream proxy, or
    /// directly when there is none; with one tor cannot dial through, tor does not start.
    fn announce_upstream() {
        let Some(proxy) = crate::upstream::configured() else {
            return;
        };
        match proxy.socks_address() {
            Some(address) => {
                log::info!("[*] tor dials out through the upstream proxy at {address}")
            }
            None => log::warn!(
                "[-] tor cannot dial through the upstream proxy at {}, which is no socks5 address \
                 without a password; it does not connect directly instead",
                proxy.endpoint()
            ),
        }
    }

    /// Waits for the core's socks5 server on `through`: the core holds its listener from its start
    /// and answers once a tunnel serves it.
    async fn wait_for_proxy(through: SocketAddr) {
        crate::socks::until_answered(through, Duration::from_secs(2), || {
            log::info!("[*] tor is waiting for the tunnel proxy at {through}")
        })
        .await
    }

    pub async fn run_chain(through: SocketAddr, state: PathBuf) -> Result<()> {
        init_tracing();
        let listen = listen_address();
        // Both held from the start, as the core holds its own: the user dials them while the
        // tunnel and Tor come up, and no other program may take them meanwhile.
        let listener = crate::socks::bind_listener("tor socks5", listen, "--tor-bind").await?;
        let listen = listener.local_addr().unwrap_or(listen);
        let http = bind_http().await?;

        wait_for_proxy(through).await;
        log::info!("[*] bootstrapping tor through the tunnel at {through}");

        let client = establish(&state, Some(through), FOREVER).await?;
        log::info!("[+] tor is ready; {listen} leaves through tor, carried by the tunnel");
        announce_exit(listen, "tor through the tunnel");

        serve(listener, http, client, "tor socks5").await
    }

    /// Tor with no tunnel underneath it, serving `listener`, which the core holds on `listen` from
    /// its start; its http proxy is held from the start as well.
    pub async fn run_only(listener: TcpListener, listen: SocketAddr, state: PathBuf) -> Result<()> {
        init_tracing();
        // The port the system gave, where --bind names port 0.
        let listen = listener.local_addr().unwrap_or(listen);
        let http = bind_http().await?;

        log::info!("[*] bootstrapping tor with no tunnel underneath it");
        announce_upstream();

        let client = establish(&state, None, FOREVER).await?;
        log::info!("[+] tor is ready; {listen} leaves through tor");
        announce_exit(listen, "tor");

        serve(listener, http, client, "tor socks5").await
    }

    /// Starts Tor for the tunnel to be dialled through: its socks5 address, and the handle that
    /// ends it with the core.
    pub async fn start_reverse(state: PathBuf) -> Result<(SocketAddr, tokio::task::AbortHandle)> {
        init_tracing();
        let listen = listen_address();
        let listener = crate::socks::bind_listener("tor socks5", listen, "--tor-bind").await?;
        // Where the tunnel dials Tor: where its listener is bound, a port 0 asked for being the port
        // the system picked.
        let dialled = crate::socks::dialable(listener.local_addr().unwrap_or(listen));

        log::info!("[*] bootstrapping tor; the tunnel will be dialled through it");
        // The upstream is still the proxy aether was given: the caller points it at tor once tor is up.
        announce_upstream();

        let client = establish(&state, None, REVERSE_ATTEMPTS).await?;
        log::info!("[+] tor is ready; the tunnel goes out through {dialled}");
        announce_exit(dialled, "tor");

        let serving = tokio::spawn(async move {
            let served = match bind_http().await {
                Ok(http) => serve(listener, http, client, "tor socks5").await,
                Err(e) => Err(e),
            };
            if let Err(e) = served {
                log::error!("[-] the tor proxy stopped: {e}");
            }
        });

        Ok((dialled, serving.abort_handle()))
    }

    // >>> AETHER-APP-PATCH tor-cache-seed
    /// Shares the directory cache between the bootstrap attempts.
    ///
    /// ROOT CAUSE (field logs `Config -> Tor`, 1.5.0): every attempt boots in a
    /// cache of its own (`attempts/direct`, `attempts/obfs4`, ...). The direct
    /// attempt loaded a complete, valid consensus from disk ("Loaded a good
    /// directory from cache"), but the bridge attempt that followed it started
    /// from an EMPTY cache ("Didn't get usable directory from cache") and had to
    /// download the whole consensus and all 9 149 microdescriptors over an obfs4
    /// bridge that was itself reached through the proxy chain - the one path on
    /// which that download is slowest. The consensus is public and identical
    /// for every attempt, so the freshest cache on disk is now copied into an
    /// attempt that has nothing as good. Guard state (`state/`) stays separate.
    mod seed {
        use std::path::{Path, PathBuf};
        use std::time::SystemTime;

        /// Where the snapshot of the freshest cache is kept. Never opened by a tor client.
        const SEED: &str = ".seed";
        /// The sqlite files tor keeps. `-shm` is deliberately left out: sqlite
        /// rebuilds it from the WAL, and a copied one could be stale.
        const DB_FILES: [&str; 2] = ["dir.sqlite3", "dir.sqlite3-wal"];
        /// Below this the cache holds no microdescriptors worth sharing.
        const MIN_DB_BYTES: u64 = 1024 * 1024;

        fn seed_dir(state: &Path) -> PathBuf {
            state.join("attempts").join(SEED)
        }

        /// When the newest consensus in `cache` was stored, if `cache` also holds
        /// enough microdescriptors to be worth copying.
        fn usable_since(cache: &Path) -> Option<SystemTime> {
            let db_bytes: u64 = DB_FILES
                .iter()
                .filter_map(|name| std::fs::metadata(cache.join(name)).ok())
                .map(|meta| meta.len())
                .sum();
            if db_bytes < MIN_DB_BYTES {
                return None;
            }
            std::fs::read_dir(cache.join("dir_blobs"))
                .ok()?
                .flatten()
                .filter(|entry| entry.file_name().to_string_lossy().starts_with("con_microdesc"))
                .filter_map(|entry| entry.metadata().and_then(|meta| meta.modified()).ok())
                .max()
        }

        fn caches(state: &Path) -> Vec<PathBuf> {
            let mut found = vec![state.join("cache")];
            if let Ok(entries) = std::fs::read_dir(state.join("attempts")) {
                for entry in entries.flatten() {
                    // Skips the snapshot itself and any staging copy.
                    if !entry.file_name().to_string_lossy().starts_with('.') {
                        found.push(entry.path().join("cache"));
                    }
                }
            }
            found
        }

        fn private_dir(path: &Path) -> std::io::Result<()> {
            std::fs::create_dir_all(path)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))?;
            }
            Ok(())
        }

        /// Copies the sqlite files and every blob `into` does not have yet.
        fn copy_cache(from: &Path, into: &Path) -> std::io::Result<()> {
            private_dir(into)?;
            for name in DB_FILES.iter().chain(["dir.sqlite3-shm"].iter()) {
                let _ = std::fs::remove_file(into.join(name));
            }
            for name in DB_FILES {
                let source = from.join(name);
                if source.is_file() {
                    std::fs::copy(&source, into.join(name))?;
                }
            }
            let blobs = from.join("dir_blobs");
            if blobs.is_dir() {
                let target = into.join("dir_blobs");
                private_dir(&target)?;
                for entry in std::fs::read_dir(&blobs)?.flatten() {
                    let destination = target.join(entry.file_name());
                    if entry.path().is_file() && !destination.exists() {
                        std::fs::copy(entry.path(), &destination)?;
                        // Keep the original store time: freshness is judged by it.
                        if let Ok(modified) = entry.metadata().and_then(|meta| meta.modified()) {
                            if let Ok(file) = std::fs::OpenOptions::new().write(true).open(&destination) {
                                let _ = file.set_modified(modified);
                            }
                        }
                    }
                }
            }
            Ok(())
        }

        /// True while a tor client of this process may still have `cache` open.
        fn in_use(cache: &Path) -> bool {
            let lock = cache.join("dir.lock");
            if !lock.exists() {
                return false;
            }
            match std::fs::OpenOptions::new().read(true).write(true).open(&lock) {
                Ok(file) => match file.try_lock() {
                    Ok(()) => {
                        let _ = file.unlock();
                        false
                    }
                    Err(_) => true,
                },
                Err(_) => true,
            }
        }

        /// Keeps a copy of the freshest cache. Called before any client exists.
        pub(super) fn snapshot(state: &Path) {
            let seed = seed_dir(state);
            let best = caches(state)
                .into_iter()
                .filter(|cache| !in_use(cache))
                .filter_map(|cache| usable_since(&cache).map(|when| (when, cache)))
                .max_by_key(|(when, _)| *when);
            let Some((when, source)) = best else {
                return;
            };
            if usable_since(&seed).is_some_and(|held| held >= when) {
                return;
            }
            let staging = state.join("attempts").join(".seed-new");
            let _ = std::fs::remove_dir_all(&staging);
            let outcome = copy_cache(&source, &staging).and_then(|()| {
                let _ = std::fs::remove_dir_all(&seed);
                std::fs::rename(&staging, &seed)
            });
            if let Err(e) = outcome {
                let _ = std::fs::remove_dir_all(&staging);
                log::warn!("[-] tor could not keep a copy of its directory cache: {e}");
            }
        }

        /// Gives an attempt's cache the snapshot when it has nothing as fresh.
        pub(super) fn into_slot(state: &Path, cache: &Path) {
            let seed = seed_dir(state);
            let Some(seeded) = usable_since(&seed) else {
                return;
            };
            if usable_since(cache).is_some_and(|held| held >= seeded) || in_use(cache) {
                return;
            }
            match copy_cache(&seed, cache) {
                Ok(()) => log::info!("[*] tor starts this attempt from the directory it already has"),
                Err(e) => {
                    for name in DB_FILES {
                        let _ = std::fs::remove_file(cache.join(name));
                    }
                    log::warn!("[-] tor could not share its directory cache with this attempt: {e}");
                }
            }
        }
    }

    // <<< AETHER-APP-PATCH tor-cache-seed
    fn prepare_dir(path: &Path) -> Result<()> {
        std::fs::create_dir_all(path)?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(path)?.permissions();
            perms.set_mode(0o700);
            std::fs::set_permissions(path, perms)?;
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The hold on the tor settings of a test: the variables are the whole process's, and the
    /// tests run side by side, so each test holds them first, or one clearing them would wipe a
    /// value another had just set. They start out clear, and are cleared again as the hold is
    /// let go, also by a test that failed half way.
    struct SettingsHeld(std::sync::MutexGuard<'static, ()>);

    impl Drop for SettingsHeld {
        fn drop(&mut self) {
            clear();
        }
    }

    /// Waits for the hold on the tor settings, see `SettingsHeld`. A test that failed while it
    /// held them poisoned the lock, but cleared them all the same, so the lock is taken anyway.
    fn hold_settings() -> SettingsHeld {
        static SETTINGS: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();
        let held = SETTINGS
            .get_or_init(|| std::sync::Mutex::new(()))
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        clear();
        SettingsHeld(held)
    }

    fn clear() {
        std::env::remove_var("AETHER_TOR");
        std::env::remove_var("AETHER_TOR_DIR");
        std::env::remove_var("AETHER_TOR_BIND");
        std::env::remove_var("AETHER_TOR_BRIDGES");
        std::env::remove_var("AETHER_TOR_PT");
    }

    #[test]
    fn every_mode_has_a_spelling() {
        let _settings = hold_settings();
        assert_eq!(mode(), Mode::Off);
        for written in ["1", "on", "chain", "yes"] {
            std::env::set_var("AETHER_TOR", written);
            assert_eq!(mode(), Mode::Chain, "{written}");
        }
        for written in ["reverse", "rev", "warp-over-tor"] {
            std::env::set_var("AETHER_TOR", written);
            assert_eq!(mode(), Mode::Reverse, "{written}");
        }
        for written in ["only", "alone", "tor-only", "direct"] {
            std::env::set_var("AETHER_TOR", written);
            assert_eq!(mode(), Mode::Only, "{written}");
        }
        for written in ["0", "off", "false", "no"] {
            std::env::set_var("AETHER_TOR", written);
            assert_eq!(mode(), Mode::Off, "{written}");
        }
    }

    #[test]
    fn the_state_dir_sits_beside_the_identity_file() {
        let _settings = hold_settings();
        assert_eq!(state_dir("aether.toml"), PathBuf::from("aether-tor"));
        assert_eq!(
            state_dir("/etc/aether/aether.toml"),
            PathBuf::from("/etc/aether/aether-tor")
        );
        std::env::set_var("AETHER_TOR_DIR", "/var/lib/aether-tor");
        assert_eq!(
            state_dir("aether.toml"),
            PathBuf::from("/var/lib/aether-tor")
        );
    }

    #[test]
    fn the_tor_listener_has_a_default_of_its_own() {
        let _settings = hold_settings();
        assert_eq!(listen_address(), "127.0.0.1:1820".parse().unwrap());
        std::env::set_var("AETHER_TOR_BIND", "127.0.0.1:9150");
        assert_eq!(listen_address(), "127.0.0.1:9150".parse().unwrap());
    }

    #[test]
    fn bridges_are_read_one_per_entry() {
        let _settings = hold_settings();
        assert!(bridge_lines().is_empty());
        std::env::set_var(
            "AETHER_TOR_BRIDGES",
            "obfs4 192.0.2.55:38114 316E64 cert=abc iat-mode=0 ; obfs4 198.51.100.25:443 7DD627 cert=def",
        );
        let lines = bridge_lines();
        assert_eq!(lines.len(), 2);
        assert!(lines[0].starts_with("obfs4 192.0.2.55"));
        assert!(lines[1].starts_with("obfs4 198.51.100.25"));
    }

    #[test]
    fn a_transport_may_be_named_or_assumed_to_be_obfs4() {
        let _settings = hold_settings();
        assert!(transports().is_empty());
        std::env::set_var("AETHER_TOR_PT", "/usr/bin/lyrebird");
        assert_eq!(
            transports(),
            vec![("obfs4".to_string(), "/usr/bin/lyrebird".to_string())]
        );
        std::env::set_var(
            "AETHER_TOR_PT",
            "obfs4=/usr/bin/lyrebird;snowflake=/usr/bin/snowflake-client",
        );
        assert_eq!(
            transports(),
            vec![
                ("obfs4".to_string(), "/usr/bin/lyrebird".to_string()),
                (
                    "snowflake".to_string(),
                    "/usr/bin/snowflake-client".to_string()
                ),
            ]
        );
    }
}

#[cfg(test)]
mod bridge_file_tests {
    use super::*;

    #[test]
    fn a_torrc_shaped_file_reads_line_by_line() {
        let text = "\
# my bridges
Bridge obfs4 1.2.3.4:443 ABCD cert=xx iat-mode=0

bridge obfs4 5.6.7.8:80 EF01 cert=yy iat-mode=0
9.9.9.9:443 0123456789012345678901234567890123456789
";
        let lines = read_bridge_lines(text);
        assert_eq!(lines.len(), 3);
        assert!(lines[0].starts_with("obfs4 1.2.3.4:443"));
        assert!(lines[1].starts_with("obfs4 5.6.7.8:80"));
        assert!(lines[2].starts_with("9.9.9.9:443"));
    }

    #[test]
    fn comments_and_blank_lines_carry_nothing() {
        assert!(read_bridge_lines("\n  \n# only a comment\n").is_empty());
    }
}
