#![allow(dead_code)]
pub mod account;

pub mod aethernoize;

pub mod api;

pub mod bridges;

pub mod cli;

pub mod config;

pub mod consts;

pub mod dns;

pub mod egress;

pub mod error;

pub mod exitloc;

// Upstream's own C API is deliberately not vendored. Nothing here calls it --
// native/android-bridge is this project's FFI and predates it -- and its
// spawn_job wrapper around run_with does not satisfy Send for our merged
// engine, so carrying it would mean carrying a build failure for an interface
// we do not ship.
pub mod fragment;

pub mod https;

pub mod lastconn;

pub mod masque;

pub mod masque_h2;

pub mod netstack;

pub mod noize;

pub mod prober;

pub mod psiphon;

pub mod quic;

pub mod routing;

// Ours: the VpnService callback that keeps a socket out of the tunnel.
pub mod sniff;

// Public for its access policy: the embedded caller decides who may use the
// listener, and that type has to cross the crate boundary with the config.
pub mod socks;

pub mod stats;

pub mod sysprofile;

pub mod tls;

pub mod tor;

pub mod tunnelping;

pub mod upstream;

pub mod wg_prober;

pub mod wireguard;

pub mod zerotrust;

use std::collections::{HashMap, HashSet};

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use std::time::Instant;

pub use error::{AetherError, Result};

fn parse_local_v4(s: &str) -> Ipv4Addr {
    s.split('/')
        .next()
        .unwrap_or(s)
        .parse()
        .unwrap_or(Ipv4Addr::UNSPECIFIED)
}

const INNER_MTU: usize = 1200;

const H2_TUNNEL_MTU: usize = 1500;

fn masque_tunnel_mtu() -> usize {
    if let Some(mtu) = std::env::var("AETHER_MASQUE_MTU")
        .ok()
        .and_then(|value| value.trim().parse::<usize>().ok())
        .filter(|mtu| (576..=1500).contains(mtu))
    {
        return mtu;
    }

    if masque_h2::enabled() {
        H2_TUNNEL_MTU
    } else {
        MASQUE_MTU
    }
}

const DEFAULT_CONFIG: &str = "aether.toml";

pub async fn run() -> Result<()> {
    run_with(std::env::args().skip(1).collect()).await
}

pub async fn run_with(args: Vec<String>) -> Result<()> {
    if cli::parse_args(args)? == cli::Parsed::Done {
        return Ok(());
    }

    let level = std::env::var("AETHER_LOG_LEVEL")
        .ok()
        .map(|v| v.trim().to_lowercase())
        .filter(|v| matches!(v.as_str(), "error" | "warn" | "info" | "debug" | "trace"))
        .unwrap_or_else(|| "info".to_string());
    let default_filter = format!("info,aether={level}");
    let _ =
        env_logger::Builder::from_env(env_logger::Env::default().default_filter_or(default_filter))
            .format_timestamp_millis()
            .try_init();

    log::info!("Aether v{}", env!("CARGO_PKG_VERSION"));
    sysprofile::log_summary();
    sysprofile::raise_fd_limit();
    egress::init()?;
    stats::init();
    stats::spawn_reporter();

    install_netstack_panic_guard();
    // A cipher string or a group list BoringSSL does not take, a --masque-sni that is no domain
    // name, an --api-address, --api-sni or --api-host the calls to the WARP API cannot use, or an
    // --upstream aether cannot dial through, stops the core here, with its option named.
    tls::check_tls_options()?;
    quic::masque_sni()?;
    account::check_api_settings()?;
    upstream::check_setting()?;
    // The key of the WARP API calls of an earlier run of the library is not this run's, and
    // neither is the name of the API that answered them.
    account::forget_api_ech();
    account::forget_answered_api_host();

    let base_config = std::env::var("AETHER_CONFIG").unwrap_or_else(|_| DEFAULT_CONFIG.to_string());

    // A registration serves no proxy, so it needs none of the listeners bound below.
    if let Some(wanted) = register_request()? {
        return register_identities(wanted, &base_config).await;
    }

    let listen: SocketAddr = std::env::var("AETHER_SOCKS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or_else(|| "127.0.0.1:1819".parse().unwrap());

    // What ends the core on its own, with its error, while its tunnels come and go: a listener the
    // user dials breaking, or Tor or Psiphon inside the tunnel ending.
    let (core_end, core_ended) = tokio::sync::mpsc::unbounded_channel::<AetherError>();
    let listeners = Listeners::bind(listen, core_end.clone()).await?;

    // Tor or Psiphon alone serves the socks5 listener the core holds, from its start on.
    if tor::mode() == tor::Mode::Only {
        let state = tor::state_dir(&base_config);
        return tor::run_only(listeners.into_socks(), listen, state).await;
    }

    if psiphon::mode() == psiphon::Mode::Only {
        let state = psiphon::state_dir(&base_config);
        return psiphon::run_only(listeners.into_socks(), listen, state).await;
    }

    if tor::mode() == tor::Mode::Reverse && psiphon::mode() == psiphon::Mode::Reverse {
        return Err(AetherError::Other(
            "--tor-reverse and --psiphon-reverse both want to carry the tunnel; pick one, or put \
             the other inside it with --tor or --psiphon"
                .into(),
        ));
    }

    let pinned_wiw = wiw_endpoints_from_env()?;
    let pinned_mim = mim_endpoints_from_env()?;

    let protocol = match std::env::var("AETHER_PROTOCOL") {
        Ok(v) => Protocol::parse(&v),
        Err(_) if !pinned_wiw.is_empty() => Protocol::WarpInWarp,
        Err(_) if !pinned_mim.is_empty() => Protocol::MasqueInMasque,
        Err(_)
            if std::env::var("AETHER_PEER").is_ok() || std::env::var("AETHER_WG_PEER").is_ok() =>
        {
            Protocol::Masque
        }
        Err(_) => select_protocol(&base_config).await,
    };

    if tor::mode() == tor::Mode::Only {
        let state = tor::state_dir(&base_config);
        return tor::run_only(listeners.into_socks(), listen, state).await;
    }

    if psiphon::mode() == psiphon::Mode::Only {
        let state = psiphon::state_dir(&base_config);
        return psiphon::run_only(listeners.into_socks(), listen, state).await;
    }

    if protocol != Protocol::WarpInWarp && !pinned_wiw.is_empty() {
        log::warn!(
            "[-] the warp-in-warp endpoints you set are ignored on {}; they only apply to --gool",
            protocol.label()
        );
    }

    if protocol != Protocol::MasqueInMasque && !pinned_mim.is_empty() {
        log::warn!(
            "[-] the masque-in-masque endpoints you set are ignored on {}; they only apply to --mim",
            protocol.label()
        );
    }

    // Tor or Psiphon inside the tunnel serves the listener the user dials. Its end leaves nothing
    // behind that port, so the core ends with it, saying why, rather than run on without it. And
    // as the core ends, Tor and Psiphon end with it, inside the tunnel or carrying it, letting go
    // of their listeners, also where the process goes on after it, as through the C API.
    let mut tor_and_psiphon = TaskGuard::new();
    // Inside the tunnel, Tor or Psiphon dials the core's own listener: where it is bound, a port 0
    // asked for being the port the system picked.
    let held = crate::socks::dialable(listeners.socks.local_addr().unwrap_or(listen));
    match tor::mode() {
        tor::Mode::Chain => {
            let through = held;
            let state = tor::state_dir(&base_config);
            let chain = tokio::spawn(tor::run_chain(through, state));
            watch_chain("tor", chain, core_end.clone(), &mut tor_and_psiphon);
        }
        tor::Mode::Reverse => {
            if needs_udp_to_warp(protocol, gool_classic()) {
                return Err(AetherError::Other(format!(
                    "tor carries tcp only and warp's wireguard endpoints answer on udp alone, so \
                     {} can never be reached through tor; use --masque or --gool, which this mode \
                     runs over http/2, or put tor inside the tunnel instead with --tor",
                    protocol.label()
                )));
            }

            let (socks, carrier) = tor::start_reverse(tor::state_dir(&base_config)).await?;
            tor_and_psiphon.push(carrier);
            std::env::set_var("AETHER_UPSTREAM", carrier_upstream("tor", socks)?);
            std::env::set_var("AETHER_MASQUE_HTTP2", "1");
            log::info!("[+] the tunnel is dialled through tor on the http/2 carrier");
        }
        tor::Mode::Only | tor::Mode::Off => {}
    }

    match psiphon::mode() {
        psiphon::Mode::Chain => {
            let through = held;
            let state = psiphon::state_dir(&base_config);
            let chain = tokio::spawn(psiphon::run_chain(through, state));
            watch_chain("psiphon", chain, core_end.clone(), &mut tor_and_psiphon);
        }
        psiphon::Mode::Reverse => {
            if needs_udp_to_warp(protocol, gool_classic()) {
                return Err(AetherError::Other(format!(
                    "psiphon carries tcp only and warp's wireguard endpoints answer on udp alone, \
                     so {} can never be reached through psiphon; use --masque or --gool, which this \
                     mode runs over http/2, or put psiphon inside the tunnel instead with --psiphon",
                    protocol.label()
                )));
            }

            let (socks, carrier) = psiphon::start_reverse(psiphon::state_dir(&base_config)).await?;
            tor_and_psiphon.push(carrier);
            std::env::set_var("AETHER_UPSTREAM", carrier_upstream("psiphon", socks)?);
            std::env::set_var("AETHER_MASQUE_HTTP2", "1");
            log::info!("[+] the tunnel is dialled through psiphon on the http/2 carrier");
        }
        psiphon::Mode::Only | psiphon::Mode::Off => {}
    }
    drop(core_end);

    let session = async {
        match protocol {
            Protocol::Masque => {
                select_masque_transport().await;
                let config_path = masque_config_path(&base_config);
                let identity = load_or_provision_masque_path(&config_path).await?;
                log::info!(
                    "[+] identity ready: device={} ipv4={} ipv6={}",
                    identity.device_id,
                    identity.ipv4,
                    identity.ipv6
                );
                // Every MASQUE handshake of the session offers it, on either carrier, until it ends.
                ech_policy_satisfied().await?;
                let _ech = tls::EchSession::start(resolve_ech().await);
                let lastconn_path = lastconn_path(&config_path);
                run_masque(identity, listeners, lastconn_path, None).await
            }
            Protocol::WireGuard => {
                let config_path = warp_config_path(&base_config);
                let identity = load_or_provision_warp_path(&config_path).await?;
                log::info!(
                    "[+] identity ready: device={} ipv4={} ipv6={}",
                    identity.device_id,
                    identity.ipv4,
                    identity.ipv6
                );

                let lastconn_path = lastconn_path(&config_path);
                run_wireguard(identity, listeners, lastconn_path).await
            }
            Protocol::WarpInWarp if !gool_classic() => {
                select_masque_transport().await;
                let config_path = masque_config_path(&base_config);
                let identity = load_or_provision_masque_path(&config_path).await?;
                let inner_path = gool_identity_path(&config_path);
                log::info!(
                    "[+] gool: masque device={} carries the wireguard identity in {inner_path}",
                    identity.device_id
                );
                ech_policy_satisfied().await?;
                let _ech = tls::EchSession::start(resolve_ech().await);
                let lastconn_path = lastconn_path(&config_path);
                run_masque(identity, listeners, lastconn_path, Some(inner_path)).await
            }
            Protocol::WarpInWarp => {
                let primary_path = warp_config_path(&base_config);
                let secondary_path = derive_sibling_path(&primary_path, "secondary");
                let primary = load_or_provision_warp_path(&primary_path).await?;
                let secondary = load_or_provision_warp_path(&secondary_path).await?;
                log::info!(
                    "[+] outer device={} ipv4={} | inner device={} ipv4={}",
                    primary.device_id,
                    primary.ipv4,
                    secondary.device_id,
                    secondary.ipv4
                );
                run_gool(primary, secondary, listeners).await
            }
            Protocol::MasqueInMasque => {
                select_masque_transport().await;
                let primary_path = masque_config_path(&base_config);
                let secondary_path = derive_sibling_path(&primary_path, "secondary");
                let primary = load_or_provision_masque_path(&primary_path).await?;
                let secondary = load_or_provision_masque_path(&secondary_path).await?;
                log::info!(
                    "[+] outer device={} ipv4={} | inner device={} ipv4={}",
                    primary.device_id,
                    primary.ipv4,
                    secondary.device_id,
                    secondary.ipv4
                );
                // Every MASQUE handshake of the session offers it, on either carrier, until it ends.
                ech_policy_satisfied().await?;
                let _ech = tls::EchSession::start(resolve_ech().await);
                run_mim(primary, secondary, listeners).await
            }
        }
    };
    until_core_end(session, core_ended).await
}

/// Watches `chain`, the task of Tor or Psiphon (`name`) inside the tunnel, and reports its end on
/// `core_end` however it ends: with its error, which is logged as "[-] tor: ..." or "[-] psiphon:
/// ..." under the target "aether", as the apps that run the core read it; or by a panic, which
/// tokio would otherwise keep to itself while the core ran on without that listener. `guard` ends
/// this watch and then the chain as the core ends, and a chain ended so is not reported as one
/// that ended by itself.
fn watch_chain(
    name: &'static str,
    chain: tokio::task::JoinHandle<Result<()>>,
    core_end: tokio::sync::mpsc::UnboundedSender<AetherError>,
    guard: &mut TaskGuard,
) {
    let chain_task = chain.abort_handle();
    let watch = tokio::spawn(async move {
        let e = match chain.await {
            Ok(Err(e)) => e,
            Ok(Ok(())) => AetherError::Other(format!("{name} inside the tunnel stopped")),
            // Ended with the core: nothing to report.
            Err(failed) if failed.is_cancelled() => return,
            Err(failed) => AetherError::Other(format!("{name} inside the tunnel failed: {failed}")),
        };
        log::error!(target: "aether", "[-] {name}: {e}");
        let _ = core_end.send(e);
    });
    guard.push(watch.abort_handle());
    guard.push(chain_task);
}

/// Runs `session` until it ends, or until something the core cannot go on without reports its end
/// on `core_ended`, which ends the session with that error.
async fn until_core_end(
    session: impl std::future::Future<Output = Result<()>>,
    mut core_ended: tokio::sync::mpsc::UnboundedReceiver<AetherError>,
) -> Result<()> {
    tokio::select! {
        result = session => result,
        Some(e) = core_ended.recv() => Err(e),
    }
}

/// The listeners the user dials, the core's socks5 one and its http proxy when one is asked for,
/// bound once as the core starts and held until it ends. A tunnel that ends and the one that comes
/// up in its place serve them in turn, so their ports never come free while the core reconnects,
/// where the user goes on dialling them and another program could take them; a client that comes
/// meanwhile waits for the next tunnel.
struct Listeners {
    socks: tokio::net::TcpListener,
    http: Option<tokio::net::TcpListener>,
    /// Where a listener that breaks is reported, to end the core.
    broken: tokio::sync::mpsc::UnboundedSender<AetherError>,
}

impl Listeners {
    async fn bind(
        listen: SocketAddr,
        broken: tokio::sync::mpsc::UnboundedSender<AetherError>,
    ) -> Result<Self> {
        Ok(Self {
            socks: socks::bind_listener("socks5", listen, "--bind").await?,
            http: bind_http_proxy().await?,
            broken,
        })
    }

    /// The socks5 listener alone, for Tor or Psiphon alone to serve. The http proxy has no part in
    /// those, and its port is let go at once.
    fn into_socks(self) -> tokio::net::TcpListener {
        self.socks
    }

    /// Serves both through `stack`, the tunnel's, for as long as that tunnel runs. The servers run
    /// inside this future, so a tunnel that ends, or is dropped, leaves none behind on them. A
    /// server stops only when its listener itself is broken (see `socks::accept_backoff`), which
    /// no tunnel could serve again: that is reported on `broken`, to end the core with it, rather
    /// than end one tunnel after another on it and blame their peers.
    async fn serve(&self, stack: &netstack::StackHandle) -> std::convert::Infallible {
        let http = async {
            match &self.http {
                Some(listener) => socks::serve_http(listener, stack.clone()).await,
                None => std::future::pending().await,
            }
        };
        let (what, stopped) = tokio::select! {
            stopped = socks::serve(&self.socks, stack.clone()) => ("socks5 server", stopped),
            stopped = http => ("http proxy", stopped),
        };
        let _ = self.broken.send(AetherError::Other(match stopped {
            Err(e) => format!("the {what} stopped: {e}"),
            Ok(()) => format!("the {what} stopped"),
        }));
        std::future::pending().await
    }
}

const WG_EXAMPLE_PORT: u16 = 2408;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct WiwEndpoints {
    outer: Option<SocketAddr>,
    inner: Option<SocketAddr>,
}

impl WiwEndpoints {
    fn is_empty(&self) -> bool {
        self.outer.is_none() && self.inner.is_none()
    }

    fn checked(self) -> Result<Self> {
        match (self.outer, self.inner) {
            (Some(outer), Some(inner)) if outer.ip() == inner.ip() => {
                Err(AetherError::Other(format!(
                    "the two hops need separate edges, but both point at {}",
                    outer.ip()
                )))
            }
            _ => Ok(self),
        }
    }
}

fn parse_endpoint(raw: &str) -> Result<SocketAddr> {
    let text = raw.trim();

    if let Ok(peer) = text.parse::<SocketAddr>() {
        return Ok(peer);
    }

    let portless = text.parse::<IpAddr>().ok().or_else(|| {
        text.strip_prefix('[')
            .and_then(|rest| rest.strip_suffix(']'))
            .and_then(|inner| inner.parse::<IpAddr>().ok())
    });

    if let Some(address) = portless {
        return Err(AetherError::Other(format!(
            "{text} carries no port, and the port is required; write it out, as in {}",
            SocketAddr::new(address, WG_EXAMPLE_PORT)
        )));
    }

    Err(AetherError::Other(format!(
        "'{text}' is not an endpoint; write an address and a port together, \
         such as 162.159.192.1:{WG_EXAMPLE_PORT}"
    )))
}

fn parse_endpoint_list(raw: &str) -> Result<Vec<SocketAddr>> {
    let mut peers = Vec::new();

    for part in raw.split([',', ';', ' ']) {
        if part.trim().is_empty() {
            continue;
        }
        peers.push(parse_endpoint(part)?);
    }

    match peers.len() {
        0 => Err(AetherError::Other(
            "no endpoint was given; expected one or two addresses".to_string(),
        )),
        1 | 2 => Ok(peers),
        found => Err(AetherError::Other(format!(
            "warp-in-warp has two hops, but {found} addresses were given"
        ))),
    }
}

fn env_value(key: &str) -> Option<String> {
    std::env::var(key)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

fn wiw_scan_requested(lookup: &dyn Fn(&str) -> Option<String>) -> bool {
    match lookup("AETHER_WIW_PEERS") {
        Some(value) => matches!(
            value.to_lowercase().as_str(),
            "auto" | "scan" | "none" | "off" | "0"
        ),
        None => false,
    }
}

fn scan_keyword(value: &str) -> bool {
    matches!(
        value.to_lowercase().as_str(),
        "auto" | "scan" | "none" | "off" | "0"
    )
}

fn nested_endpoints_of(
    lookup: &dyn Fn(&str) -> Option<String>,
    list_key: &str,
    outer_key: &str,
    inner_key: &str,
) -> Result<WiwEndpoints> {
    let mut chosen = WiwEndpoints::default();

    if let Some(list) = lookup(list_key) {
        if !scan_keyword(&list) {
            let peers = parse_endpoint_list(&list)?;
            chosen.outer = peers.first().copied();
            chosen.inner = peers.get(1).copied();
        }
    }

    if let Some(value) = lookup(outer_key) {
        chosen.outer = Some(parse_endpoint(&value)?);
    }

    if let Some(value) = lookup(inner_key) {
        chosen.inner = Some(parse_endpoint(&value)?);
    }

    chosen.checked()
}

fn wiw_endpoints_of(lookup: &dyn Fn(&str) -> Option<String>) -> Result<WiwEndpoints> {
    nested_endpoints_of(
        lookup,
        "AETHER_WIW_PEERS",
        "AETHER_WIW_OUTER_PEER",
        "AETHER_WIW_INNER_PEER",
    )
}

fn wiw_endpoints_from_env() -> Result<WiwEndpoints> {
    wiw_endpoints_of(&env_value)
}

fn mim_endpoints_of(lookup: &dyn Fn(&str) -> Option<String>) -> Result<WiwEndpoints> {
    nested_endpoints_of(
        lookup,
        "AETHER_MIM_PEERS",
        "AETHER_MIM_OUTER_PEER",
        "AETHER_MIM_INNER_PEER",
    )
}

fn mim_endpoints_from_env() -> Result<WiwEndpoints> {
    mim_endpoints_of(&env_value)
}

fn wiw_endpoints_with_fallback(lookup: &dyn Fn(&str) -> Option<String>) -> Result<WiwEndpoints> {
    let mut chosen = wiw_endpoints_of(lookup)?;

    if chosen.outer.is_some() {
        return Ok(chosen);
    }

    let forced = lookup("AETHER_WG_PEER").or_else(|| lookup("AETHER_PEER"));
    let Some(forced) = forced else {
        return Ok(chosen);
    };

    let peers = parse_endpoint_list(&forced)?;
    chosen.outer = peers.first().copied();
    if chosen.inner.is_none() {
        chosen.inner = peers.get(1).copied();
    }

    chosen.checked()
}

async fn run_gool(
    primary: account::Identity,
    secondary: account::Identity,
    listeners: Listeners,
) -> Result<()> {
    let pinned = wiw_endpoints_with_fallback(&env_value)?;

    match (pinned.outer, pinned.inner) {
        (Some(outer), Some(inner)) => log::info!(
            "[+] warp-in-warp endpoints given by hand: {outer} (outer) and {inner} (inner); the scan is skipped"
        ),
        (Some(outer), None) => log::info!(
            "[+] outer warp-in-warp endpoint given by hand: {outer}; scanning for the inner one"
        ),
        (None, Some(inner)) => log::info!(
            "[+] inner warp-in-warp endpoint given by hand: {inner}; scanning for the outer one"
        ),
        (None, None) => {}
    }

    let mut outer_peer = pinned.outer;
    let mut inner_peer = pinned.inner;
    let mut consecutive_fails: u32 = 0;
    const MAX_CONSECUTIVE_FAILS: u32 = 2;
    let mut scan_settings: Option<(String, prober::IpScan)> = None;

    loop {
        if consecutive_fails >= MAX_CONSECUTIVE_FAILS {
            let mut rescanning = false;

            if pinned.outer.is_none() {
                if let Some(peer) = outer_peer.take() {
                    log::warn!(
                        "[-] outer endpoint {peer} failed {consecutive_fails} times in a row; blacklisting and rescanning"
                    );
                    rescanning = true;
                }
            }

            if pinned.inner.is_none() {
                if let Some(peer) = inner_peer.take() {
                    log::warn!(
                        "[-] inner endpoint {peer} failed {consecutive_fails} times in a row; blacklisting and rescanning"
                    );
                    rescanning = true;
                }
            }

            if !rescanning {
                log::warn!(
                    "[-] the endpoints you chose failed {consecutive_fails} times in a row; still retrying them, drop --wiw-outer/--wiw-inner to let the scan pick instead"
                );
            }

            consecutive_fails = 0;
        }

        let (peer, inner_peer_now) = match (outer_peer, inner_peer) {
            (Some(outer), Some(inner)) => (outer, inner),
            (known_outer, known_inner) => {
                let wanted =
                    usize::from(known_outer.is_none()) + usize::from(known_inner.is_none());
                let avoid: HashSet<IpAddr> = known_outer
                    .into_iter()
                    .chain(known_inner)
                    .map(|peer| peer.ip())
                    .collect();

                let (mode_str, ip) = match &scan_settings {
                    Some(settings) => settings.clone(),
                    None => {
                        let mode_str = select_scan_mode_str(WIW_MANUAL_TIP).await;
                        let ip = select_ip_version().await;
                        scan_settings.insert((mode_str, ip)).clone()
                    }
                };

                let found = match select_wg_peers(&primary, &mode_str, ip, wanted, &avoid).await {
                    Ok(found) => found,
                    Err(e) => {
                        log::warn!("[-] no usable WARP endpoint found: {e}; rescanning shortly");
                        tokio::time::sleep(wg_reconnect_delay()).await;
                        continue;
                    }
                };

                let mut found = found;
                if verified_scan_selected(&scan_settings) {
                    spread_hops(&mut found);
                }

                let mut found = found.into_iter();
                let outer = known_outer.or_else(|| found.next());
                let inner = known_inner.or_else(|| found.next());

                match (outer, inner) {
                    (Some(outer), Some(inner)) => (outer, inner),
                    _ => {
                        log::warn!(
                            "[-] the scan only turned up one edge, so warp-in-warp would use it twice; rescanning"
                        );
                        outer_peer = pinned.outer;
                        inner_peer = pinned.inner;
                        tokio::time::sleep(wg_reconnect_delay()).await;
                        continue;
                    }
                }
            }
        };

        log::info!("[+] using cloudflare edge {peer} (outer) and {inner_peer_now} (inner)");
        outer_peer = Some(peer);
        inner_peer = Some(inner_peer_now);

        let started = Instant::now();
        match run_warp_in_warp(
            primary.clone(),
            secondary.clone(),
            peer,
            inner_peer_now,
            &listeners,
        )
        .await
        {
            Ok(()) => log::warn!("[-] gool tunnel closed; reconnecting"),
            Err(e) => log::warn!("[-] gool tunnel ended: {e}; reconnecting"),
        }
        consecutive_fails = count_tunnel_end(consecutive_fails, started.elapsed());

        tokio::time::sleep(wg_reconnect_delay()).await;
    }
}

fn install_netstack_panic_guard() {
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let from_netstack = info
            .location()
            .map(|l| l.file().contains("smoltcp"))
            .unwrap_or(false);
        if from_netstack {
            log::debug!("[netstack] recovered from a malformed segment: {info}");
        } else {
            default_hook(info);
        }
    }));
}

fn noize_config() -> noize::NoizeConfig {
    let profile = std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "firewall".to_string());
    log::info!("[+] obfuscation profile: {profile}");
    noize::from_profile(&profile)
}

fn aethernoize_config() -> aethernoize::AetherNoizeConfig {
    let profile = std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "balanced".to_string());
    log::info!("[+] aethernoize profile: {profile}");
    aethernoize::from_profile(&profile)
}

fn team_scope() -> Option<String> {
    zerotrust::TeamSettings::from_env().map(|settings| settings.team)
}

fn enrolled_teams(base: &str) -> Vec<String> {
    let dir_end = base
        .rfind(|c| c == '/' || c == '\\')
        .map(|i| i + 1)
        .unwrap_or(0);
    let dir = if dir_end == 0 { "." } else { &base[..dir_end] };
    let stem = match base[dir_end..].rfind('.') {
        Some(rel) => &base[dir_end..dir_end + rel],
        None => &base[dir_end..],
    };
    let prefix = format!("{stem}-team-");

    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(_) => return Vec::new(),
    };

    let mut teams: Vec<String> = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let Some(rest) = name.strip_prefix(&prefix) else {
            continue;
        };
        let Some(team) = rest.strip_suffix(".toml") else {
            continue;
        };
        if team.is_empty() || team.ends_with("-secondary") || team.ends_with("-lastconn") {
            continue;
        }
        if !teams.iter().any(|known| known == team) {
            teams.push(team.to_string());
        }
    }
    teams.sort();
    teams
}

async fn enrol_zero_trust(base: &str) {
    let known = enrolled_teams(base);

    let prompt = match known.first() {
        Some(team) => format!(
            "\nZero Trust organization.\n  already enrolled: {}\nTeam name from \
             <team>.cloudflareaccess.com, or blank to reuse '{}': ",
            known.join(", "),
            team
        ),
        None => "\nZero Trust organization.\nTeam name from <team>.cloudflareaccess.com \
                 (blank to cancel): "
            .to_string(),
    };

    let answer = prompt_line(&prompt).await.unwrap_or_default();
    let answer = answer.trim().to_string();

    let team = if answer.is_empty() {
        match known.first() {
            Some(team) => team.clone(),
            None => {
                log::info!("[*] Zero Trust skipped; staying on personal WARP");
                return;
            }
        }
    } else {
        match zerotrust::normalize_team(&answer) {
            Some(team) => team,
            None => {
                log::warn!("[-] '{answer}' is not a usable team name");
                return;
            }
        }
    };

    std::env::set_var("AETHER_TEAM", &team);

    if known.iter().any(|enrolled| *enrolled == team) {
        log::info!("[+] reusing the saved enrolment for team {team}; no sign-in needed");
        return;
    }

    let needs_method = match zerotrust::TeamSettings::from_env() {
        Some(settings) => {
            !(settings.token.is_some() || settings.has_service_token() || settings.email.is_some())
        }
        None => {
            std::env::remove_var("AETHER_TEAM");
            return;
        }
    };

    if needs_method {
        let email = prompt_line("Email address for the one-time login code (blank to cancel): ")
            .await
            .unwrap_or_default();
        let email = email.trim().to_string();

        if email.is_empty() {
            log::warn!("[-] no email given; staying on personal WARP");
            std::env::remove_var("AETHER_TEAM");
            return;
        }

        std::env::set_var("AETHER_ACCESS_EMAIL", &email);
    }

    let settings = match zerotrust::TeamSettings::from_env() {
        Some(settings) => settings,
        None => {
            std::env::remove_var("AETHER_TEAM");
            return;
        }
    };

    match zerotrust::resolve_token(&settings).await {
        Ok(_) => log::info!("[+] signed in to team {team}; now pick the transport to use"),
        Err(error) => {
            log::error!("[-] Zero Trust sign-in failed: {error}");
            log::warn!("[-] staying on personal WARP");
            std::env::remove_var("AETHER_TEAM");
            std::env::remove_var("AETHER_ACCESS_EMAIL");
        }
    }
}

async fn provision_account() -> Result<account::Identity> {
    match zerotrust::TeamSettings::from_env() {
        Some(settings) => {
            log::info!(
                "[*] enrolling this device into the Zero Trust organization {} ({})",
                settings.team,
                settings.team_domain()
            );
            let identity =
                account::provision_team(consts::DEFAULT_MODEL, consts::DEFAULT_LOCALE, &settings)
                    .await?;
            Ok(account::refresh_profile(identity).await)
        }
        None => account::provision_wg(consts::DEFAULT_MODEL, consts::DEFAULT_LOCALE, None).await,
    }
}

async fn adopt_team_profile(identity: account::Identity) -> account::Identity {
    if team_scope().is_none() {
        return identity;
    }

    let identity = account::refresh_profile(identity).await;

    if !identity.gateway_proxy.is_empty() {
        if std::env::var("AETHER_GATEWAY").is_ok() {
            socks::set_gateway_proxy(&identity.gateway_proxy);
        } else {
            log::debug!(
                "[zerotrust] the organization offers a gateway proxy at {}; pass --gateway to route http through it",
                identity.gateway_proxy
            );
        }
    }

    if !identity.assigned_endpoint.is_empty() && std::env::var("AETHER_PEER").is_err() {
        let port = if std::env::var("AETHER_PROTOCOL")
            .map(|value| value == "wg" || value == "gool")
            .unwrap_or(false)
        {
            2408
        } else {
            443
        };
        let peer = format!("{}:{port}", identity.assigned_endpoint);
        if peer.parse::<SocketAddr>().is_ok() {
            log::info!("[+] the organization assigned endpoint {peer}; trying it before scanning");
            std::env::set_var("AETHER_TEAM_ENDPOINT", &peer);
        }
    }

    identity
}

fn warp_config_path(base: &str) -> String {
    if let Ok(p) = std::env::var("AETHER_WG_CONFIG") {
        return p;
    }
    match team_scope() {
        Some(team) => derive_sibling_path(base, &format!("team-{team}")),
        None => base.to_string(),
    }
}

/// Where MASQUE's identity lives -- its own file, apart from WireGuard's.
///
/// It looks like it should be the same file. A Cloudflare WARP account already
/// carries WireGuard keys, and a MASQUE certificate is enrolled onto that same
/// device rather than being a second account, so sharing one registration would
/// halve the number a user spends against a rate limit that counts per IP.
///
/// Sharing it destroys WireGuard. Enrolment is `PATCH /reg/{id}` and it writes
/// the same `key` field that registration filled with the Curve25519 public
/// key, replacing it with a secp256r1 SPKI and setting `tunnel_type: masque`.
/// Cloudflare then has no WireGuard key for the device, and WireGuard answers
/// an unrecognised peer with silence -- so every endpoint everywhere stops
/// replying, which is indistinguishable from a network that drops UDP.
///
/// The damage is not immediate. Measured against the live API the old key keeps
/// working for something between thirty and sixty seconds while the change
/// reaches the edge, which is long enough for a check made right after
/// enrolment to come back clean. `wireguard_dies_when_masque_is_enrolled_on_the
/// _same_account` waits it out.
fn masque_config_path(base: &str) -> String {
    if let Ok(p) = std::env::var("AETHER_MASQUE_CONFIG") {
        return p;
    }
    derive_sibling_path(&warp_config_path(base), "masque")
}

/// Where gool keeps the wireguard identity it carries inside its masque tunnel: beside the masque identity at
/// `masque_config`, named `<masque config>-gool.toml`. The tunnel and `--register gool` both find it here.
fn gool_identity_path(masque_config: &str) -> String {
    derive_sibling_path(masque_config, "gool")
}

fn derive_sibling_path(base: &str, suffix: &str) -> String {
    let dir_end = base
        .rfind(|c| c == '/' || c == '\\')
        .map(|i| i + 1)
        .unwrap_or(0);
    match base[dir_end..].rfind('.') {
        Some(rel) => {
            let dot = dir_end + rel;
            format!("{}-{}{}", &base[..dot], suffix, &base[dot..])
        }
        None => format!("{base}-{suffix}"),
    }
}

fn keep_saved_identity() -> bool {
    !matches!(
        std::env::var("AETHER_REPROVISION").as_deref(),
        Ok("0") | Ok("off") | Ok("false")
    )
}

async fn load_or_provision_warp(site: &IdentitySite) -> Result<account::Identity> {
    let _provisioning = provisioning_lock().lock().await;

    let mut loaded = identity::load(&site.store_path, &site.legacy)?;

    if let Some((id, device)) = loaded.store.take_usable(site.slot) {
        // A second opinion, from the file rather than the store. The store's

        // invariants and the reconciliation that feeds them should already have

        // caught a device Cloudflare no longer holds a WireGuard key for -- but

        // the cost of being wrong here is not an error, it is a three-minute

        // endpoint search that finds nothing and reports the network as dead.

        // A directory read is cheap next to that.

        match device_enrolled_elsewhere(&site.path, &id) {

            Some(other) => log::info!(

                "[!] the store offered device {id} for the {} slot, but {other} shows it was                  enrolled for MASQUE; provisioning an account of its own",

                site.slot.key()

            ),

            None => {

                log::info!("[+] loaded existing warp identity for device {id}");

                let identity = identity::identity_from(&id, &device)?;

                let identity = adopt_team_profile(identity).await;
                enable_recorded_warp(site, &identity, false).await?;

                record(&mut loaded.store, site, &identity)?;

                return Ok(identity);

            }

        }
    } else {
        log::info!(

            "[+] no usable warp identity for the {} slot; provisioning a dedicated wireguard account",

            site.slot.key()

        );
    }

    let identity = provision_within_budget(&mut loaded.store, site).await?;

    // Written before anything else is asked of the network. Cloudflare has

    // already counted this registration against the address whether or not the

    // rest of the connect succeeds, so the one thing that must not happen is

    // reaching the next await without it on disk.

    record(&mut loaded.store, site, &identity)?;

    let identity = adopt_team_profile(identity).await;

    record(&mut loaded.store, site, &identity)?;

    log::info!(
        "[+] provisioned and saved a new warp identity for device {}",
        identity.device_id
    );
    enable_recorded_warp(site, &identity, true).await?;

    Ok(identity)
}

/// Enables WARP on a device just registered, as the WARP toggle of the app does. A new identity is
/// saved only once this is done, and an identity file already there is never asked about again;
/// a failure here fails the registration, and nothing is saved.
async fn enable_warp_on(identity: &account::Identity) -> Result<()> {
    account::enable_warp(&identity.device_id, &identity.access_token).await?;
    log::info!("[+] warp enabled for device {}", identity.device_id);
    Ok(())
}

async fn enable_recorded_warp(
    site: &IdentitySite,
    identity: &account::Identity,
    newly_registered: bool,
) -> Result<()> {
    let pending = format!("{}.warp-enable-pending", site.path);
    if newly_registered {
        std::fs::write(&pending, &identity.device_id)?;
    }
    if std::fs::read_to_string(&pending).ok().as_deref() != Some(identity.device_id.as_str()) {
        return Ok(());
    }
    enable_warp_on(identity).await?;
    std::fs::remove_file(pending)?;
    Ok(())
}

async fn load_or_provision_masque(site: &IdentitySite) -> Result<account::Identity> {
    let _provisioning = provisioning_lock().lock().await;

    let mut loaded = identity::load(&site.store_path, &site.legacy)?;

    if let Some((id, device)) = loaded.store.take_usable(site.slot) {
        let identity = identity::identity_from(&id, &device)?;

        if identity.has_masque_credentials() {
            log::info!("[+] loaded existing masque identity for device {id}");
            enable_recorded_warp(site, &identity, false).await?;

            let identity = adopt_team_profile(identity).await;

            if !identity.refused {
                record(&mut loaded.store, site, &identity)?;

                return Ok(identity);
            }

            if !keep_saved_identity() {
                return Ok(identity);
            }

            if let Some(device) = loaded.store.device_mut(&id) {
                device.refused_at = account::now_unix();
            }

            log::warn!("[*] registering a fresh masque account to replace the refused identity");
        } else {
            // Resumed rather than restarted. This is where a registration whose

            // enrolment failed or was cancelled is picked up, which is what

            // stops the next attempt buying another one.

            log::info!("[+] the masque identity for device {id} needs a certificate; enrolling");

            match enrol_and_record(&mut loaded.store, site, identity.clone()).await {
                Ok(identity) => return Ok(identity),

                Err(AetherError::IdentityRefused(reason)) => {
                    log::warn!("[-] the saved masque identity was refused: {reason}");

                    if !keep_saved_identity() {
                        return Ok(account::Identity {
                            refused: true,

                            ..identity
                        });
                    }

                    if let Some(device) = loaded.store.device_mut(&id) {
                        device.refused_at = account::now_unix();
                    }

                    log::warn!(
                        "[*] registering a fresh masque account to replace the refused identity"
                    );
                }

                Err(error) => return Err(error),
            }
        }
    } else {
        log::info!(

            "[+] no usable masque identity for the {} slot; provisioning a dedicated masque account",

            site.slot.key()

        );
    }

    let identity = provision_within_budget(&mut loaded.store, site).await?;

    // Before the enrolment, which is a second network call and the longer of

    // the two. Registration is the expensive half -- Cloudflare counts it

    // against this address the moment it answers -- and the branch above

    // resumes from here, so an enrolment that fails costs a round trip rather

    // than a registration.

    record(&mut loaded.store, site, &identity)?;

    let identity = enrol_and_record(&mut loaded.store, site, identity).await?;

    log::info!(
        "[+] provisioned and saved a new masque identity for device {}",
        identity.device_id
    );

    Ok(identity)
}

/// The identities `--register` asks for: those of one protocol, both hops of a two-hop one, or
/// all five.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct RegisterSet {
    wireguard: bool,
    wireguard_inner: bool,
    masque: bool,
    masque_inner: bool,
    /// The wireguard identity gool carries inside its masque tunnel, see [`gool_identity_path`].
    gool: bool,
}

/// How `--register` makes an identity it does not find.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Provision {
    Warp,
    Masque,
    Gool,
}

impl RegisterSet {
    fn parse(value: &str) -> Result<Self> {
        let none = RegisterSet {
            wireguard: false,
            wireguard_inner: false,
            masque: false,
            masque_inner: false,
            gool: false,
        };
        match value.trim().to_lowercase().as_str() {
            "all" => Ok(RegisterSet {
                wireguard: true,
                wireguard_inner: true,
                masque: true,
                masque_inner: true,
                gool: true,
            }),
            "masque" => Ok(RegisterSet {
                masque: true,
                ..none
            }),
            "wg" | "wireguard" | "warp" => Ok(RegisterSet {
                wireguard: true,
                ..none
            }),
            // What --gool runs: wireguard carried inside masque.
            "gool" | "wg-over-masque" => Ok(RegisterSet {
                masque: true,
                gool: true,
                ..none
            }),
            // What --gool-classic runs: wireguard inside wireguard.
            "gool-classic" | "wiw" | "warp-in-warp" => Ok(RegisterSet {
                wireguard: true,
                wireguard_inner: true,
                ..none
            }),
            "mim" | "masque-in-masque" => Ok(RegisterSet {
                masque: true,
                masque_inner: true,
                ..none
            }),
            other => Err(AetherError::Other(format!(
                "--register takes masque, wg, gool, gool-classic, mim or all, not '{other}'"
            ))),
        }
    }
}

fn register_request() -> Result<Option<RegisterSet>> {
    env_value("AETHER_REGISTER")
        .map(|value| RegisterSet::parse(&value))
        .transpose()
}

/// `--register`: makes sure the identities `wanted` names exist, registering the missing ones,
/// and returns without scanning or opening a tunnel. An identity file already there is kept,
/// never replaced. With a carrier around the tunnel the registrations go through it, whatever
/// the protocol: they are https requests, which tor and psiphon carry, so the refusal of
/// wireguard behind them concerns the tunnel alone. A carrier inside the tunnel, or on its own,
/// plays no part in a registration.
async fn register_identities(wanted: RegisterSet, base_config: &str) -> Result<()> {
    let carrier = match (tor::mode(), psiphon::mode()) {
        (tor::Mode::Reverse, psiphon::Mode::Reverse) => {
            return Err(AetherError::Other(
                "--tor-reverse and --psiphon-reverse both want to carry the registration; \
                 pick one"
                    .into(),
            ));
        }
        (tor::Mode::Reverse, _) => Some((
            "tor",
            tor::start_reverse(tor::state_dir(base_config)).await?,
        )),
        (_, psiphon::Mode::Reverse) => Some((
            "psiphon",
            psiphon::start_reverse(psiphon::state_dir(base_config)).await?,
        )),
        _ => None,
    };
    // The carrier ends as the registration does.
    let mut carried = TaskGuard::new();
    if let Some((name, (socks, carrier))) = carrier {
        carried.push(carrier);
        std::env::set_var("AETHER_UPSTREAM", carrier_upstream(name, socks)?);
        log::info!("[+] the identities are registered through {name}");
    }

    let wireguard = warp_config_path(base_config);
    let masque = masque_config_path(base_config);
    let identities = [
        (
            wanted.wireguard,
            "wireguard",
            wireguard.clone(),
            Provision::Warp,
        ),
        (
            wanted.wireguard_inner,
            "wireguard inner",
            derive_sibling_path(&wireguard, "secondary"),
            Provision::Warp,
        ),
        (wanted.masque, "masque", masque.clone(), Provision::Masque),
        (
            wanted.masque_inner,
            "masque inner",
            derive_sibling_path(&masque, "secondary"),
            Provision::Masque,
        ),
        (
            wanted.gool,
            "gool",
            gool_identity_path(&masque),
            Provision::Gool,
        ),
    ];

    let mut ready = Vec::new();
    for (asked, label, path, provision) in identities {
        if !asked {
            continue;
        }
        let identity = match provision {
            Provision::Warp => load_or_provision_warp_path(&path).await?,
            Provision::Masque => load_or_provision_masque_path(&path).await?,
            Provision::Gool => load_or_provision_gool(&path).await?,
        };
        log::info!(
            "[+] {label} identity ready: device={} ipv4={} ipv6={}",
            identity.device_id,
            identity.ipv4,
            identity.ipv6
        );
        ready.push(label);
    }

    log::info!("[+] identities ready: {}", ready.join(", "));
    Ok(())
}

async fn select_peer(identity: &account::Identity, protocol: Protocol) -> Result<SocketAddr> {
    let force_peer = match protocol {
        Protocol::Masque | Protocol::MasqueInMasque => std::env::var("AETHER_PEER").ok(),
        Protocol::WireGuard | Protocol::WarpInWarp => std::env::var("AETHER_WG_PEER")
            .ok()
            .or_else(|| std::env::var("AETHER_PEER").ok()),
    };

    if let Some(p) = force_peer {
        let peer: SocketAddr = p
            .parse()
            .map_err(|_| AetherError::Other(format!("bad peer address {p}")))?;
        log::info!("[+] using forced peer {peer} (probe skipped)");
        return Ok(peer);
    }

    log::info!("[+] selected protocol: {}", protocol.label());

    let mode_str = select_scan_mode_str("").await;
    let ip = select_ip_version().await;

    match protocol {
        Protocol::Masque | Protocol::MasqueInMasque => {
            log::info!("[*] hunting for a working MASQUE gateway (deep connect-ip verification)");
            let mode = prober::ScanMode::parse(&mode_str);
            let probe = prober::MasqueProbe {
                sni: quic::masque_sni()?,
                authority: quic::default_authority().to_string(),
                path: quic::default_path().to_string(),
                cert_pem: std::sync::Arc::from(identity.cert_pem.clone()),
                key_pem: std::sync::Arc::from(identity.key_pem.clone()),
                ech_config_list: attempt_ech().await.map(std::sync::Arc::from),
                noize: noize_config(),
                ports: prober::MASQUE_PORTS.to_vec(),
                ip,
                local_ipv4: parse_local_v4(&identity.ipv4),
            };

            let best = prober::hunt_best_gateway(&probe, mode).await?;
            log::info!(
                "[+] selected MASQUE gateway {}:{} (rtt {:?})",
                best.ip,
                best.port,
                best.rtt
            );
            Ok(SocketAddr::new(best.ip, best.port))
        }
        Protocol::WireGuard | Protocol::WarpInWarp => {
            let peers = select_wg_peers(identity, &mode_str, ip, 1, &HashSet::new()).await?;
            Ok(peers[0])
        }
    }
}

async fn select_wg_peers(
    identity: &account::Identity,
    mode_str: &str,
    ip: prober::IpScan,
    want: usize,
    avoid: &HashSet<IpAddr>,
) -> Result<Vec<SocketAddr>> {
    log::info!(
        "[*] hunting for {want} working WireGuard endpoint(s) (handshake + data-plane verification)"
    );
    let mode = wg_prober::WgScanMode::parse(mode_str);

    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;

    let ports = wireguard::WG_PORTS.to_vec();
    let excluded: HashSet<SocketAddr> = avoid
        .iter()
        .flat_map(|address| {
            ports
                .iter()
                .map(move |port| SocketAddr::new(*address, *port))
        })
        .collect();

    if !avoid.is_empty() {
        log::info!(
            "[*] the scan leaves out {} address(es) already taken by the other hop",
            avoid.len()
        );
    }

    let probe = wg_prober::WgProbe {
        private_key: std::sync::Arc::new(private_key),
        peer_public_key: std::sync::Arc::new(peer_public),
        client_id: identity.client_id.clone(),
        local_ipv4: identity
            .ipv4
            .parse()
            .map_err(|_| AetherError::Other("invalid ipv4".into()))?,
        aethernoize: aethernoize_config(),
        ports,
        ip,
        excluded,
    };

    let found = wg_prober::hunt_wg_endpoints(&probe, mode, want).await?;

    let picked: Vec<wg_prober::WgProbeResult> = found
        .into_iter()
        .filter(|pr| !avoid.contains(&pr.ip))
        .take(want)
        .collect();

    if picked.is_empty() {
        return Err(AetherError::NoCleanEndpoint);
    }

    for pr in &picked {
        log::info!(
            "[+] selected WireGuard endpoint {}:{} (rtt {:?})",
            pr.ip,
            pr.port,
            pr.rtt
        );
    }

    Ok(picked
        .into_iter()
        .map(|pr| SocketAddr::new(pr.ip, pr.port))
        .collect())
}

async fn resolve_ech() -> Option<Vec<u8>> {
    match std::env::var("AETHER_ECH") {
        Ok(v) if v.eq_ignore_ascii_case("auto") || v.eq_ignore_ascii_case("require") => {
            match dns::fetch_ech_config(&dns::SESSION_ECH).await {
                Ok(raw) => {
                    log::info!(
                        "[+] fetched ECHConfigList automatically ({} bytes)",
                        raw.len()
                    );
                    Some(raw)
                }
                Err(e) => {
                    log::warn!("[-] ECH auto-fetch failed ({e}); continuing without ECH");
                    None
                }
            }
        }
        Ok(b64) if !b64.is_empty() && !b64.eq_ignore_ascii_case("off") => {
            match tls::decode_ech_config_list(&b64) {
                Ok(v) => {
                    log::info!("[+] using ECHConfigList from AETHER_ECH");
                    Some(v)
                }
                Err(e) => {
                    log::warn!("[-] bad AETHER_ECH: {e}; continuing without ECH");
                    None
                }
            }
        }
        _ => {
            log::info!("[+] ECH disabled (warp masque endpoint does not accept ECH); SNI sent in cleartext");
            None
        }
    }
}

fn masque_reconnect_delay() -> std::time::Duration {
    let secs = std::env::var("AETHER_MASQUE_RECONNECT_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(2);
    std::time::Duration::from_secs(secs)
}

fn masque_startup_timeout() -> std::time::Duration {
    let secs = std::env::var("AETHER_MASQUE_STARTUP_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(30);
    std::time::Duration::from_secs(secs)
}

async fn hunt_masque_peer(
    identity: &account::Identity,
    mode_str: &str,
    ip: prober::IpScan,
    avoid: &HashSet<IpAddr>,
) -> Result<SocketAddr> {
    log::info!(
        "[*] hunting for a working MASQUE gateway (deep connect-ip + data-plane verification)"
    );
    let mode = prober::ScanMode::parse(mode_str);
    let probe = prober::MasqueProbe {
        sni: quic::masque_sni()?,
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: std::sync::Arc::from(identity.cert_pem.clone()),
        key_pem: std::sync::Arc::from(identity.key_pem.clone()),
        ech_config_list: tls::session_ech().map(std::sync::Arc::from),
        noize: noize_config(),
        ports: prober::MASQUE_PORTS.to_vec(),
        ip,
        local_ipv4: parse_local_v4(&identity.ipv4),
    };

    let best = prober::hunt_best_gateway_avoiding(&probe, mode, avoid).await?;
    log::info!(
        "[+] selected MASQUE gateway {}:{} (rtt {:?})",
        best.ip,
        best.port,
        best.rtt
    );
    Ok(SocketAddr::new(best.ip, best.port))
}

fn masque_carrier() -> &'static str {
    if masque_h2::enabled() {
        lastconn::CARRIER_MASQUE_H2
    } else {
        lastconn::CARRIER_MASQUE_H3
    }
}

/// Where the last working endpoint for one protocol is remembered.
///
/// Per protocol even though the identity is shared: a MASQUE gateway and a
/// WireGuard endpoint are different addresses on different ports, and offering
/// one to the other wastes a validation attempt on every connect.
/// Where the last working endpoint for an identity is remembered.
///
/// Taken from the identity's own file rather than from the protocol, because
/// each protocol has its own file again and the distinction is already in the
/// path. Applying it twice is what produced `aether-masque-masque-lastconn`.
///
/// The names this yields are the ones installs already wrote -- MASQUE's
/// identity is `aether-masque.toml`, so its cache is still
/// `aether-masque-lastconn.toml` -- so nobody loses a remembered endpoint and
/// re-scans on the first connect after updating.
fn lastconn_path(config_path: &str) -> String {
    derive_sibling_path(config_path, "lastconn")
}

/// Whether `peer` serves MASQUE, by a quick check on the carrier the session uses, which
/// offers `ech`: the session's key, or that of a job of the library.
async fn quick_verify_masque_peer(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
) -> bool {
    // The core checks the name as it starts; a job of the library given no domain name fails
    // every check here.
    let sni = match quic::masque_sni() {
        Ok(sni) => sni,
        Err(e) => {
            log::warn!("[-] {e}");
            return false;
        }
    };
    let vp = quic::VerifyParams {
        peer,
        sni: sni.clone(),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: identity.cert_pem.clone(),
        key_pem: identity.key_pem.clone(),
        ech_config_list: ech.clone(),
        noize: noize_config(),
        timeout: std::time::Duration::from_secs(5),
        local_ipv4: parse_local_v4(&identity.ipv4),
    };

    if masque_h2::enabled() {
        let cfg = masque_h2::H2TunnelConfig {
            peer: masque_h2::h2_peer(peer),
            sni,
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: true,
            pin_endpoint: true,
            expected_pins: consts::masque_pins().iter().map(|p| p.to_vec()).collect(),
            ech_config_list: ech,
        };
        return masque_h2::verify_h2(&cfg, std::time::Duration::from_secs(5))
            .await
            .is_ok();
    }

    quic::verify_masque(&vp).await.is_ok()
}

async fn want_quick_reconnect(cached: &lastconn::LastConnection) -> bool {
    match std::env::var("AETHER_QUICK_RECONNECT").as_deref() {
        Ok("1") | Ok("true") | Ok("yes") | Ok("on") => return true,
        Ok("0") | Ok("false") | Ok("no") | Ok("off") => return false,
        _ => {}
    }

    let answer = prompt_line(&format!(
        "\nLast working gateway: {} (profile '{}')\nReconnect to it now without rescanning? [Y/n]: ",
        cached.peer, cached.profile
    ))
    .await;

    !matches!(answer.as_deref(), Some(a) if a.eq_ignore_ascii_case("n") || a.eq_ignore_ascii_case("no"))
}

async fn run_masque(
    identity: account::Identity,
    listeners: Listeners,
    lastconn_path: String,
    gool_inner: Option<String>,
) -> Result<()> {
    let forced = std::env::var("AETHER_PEER").ok();

    let mut quick_peer: Option<SocketAddr> = None;

    if forced.is_none() {
        if let Some(assigned) = std::env::var("AETHER_TEAM_ENDPOINT")
            .ok()
            .and_then(|value| value.parse::<SocketAddr>().ok())
        {
            log::info!("[*] verifying the endpoint the organization assigned: {assigned}");
            if quick_verify_masque_peer(&identity, assigned, tls::session_ech()).await {
                log::info!("[+] the assigned endpoint {assigned} works; skipping the scan");
                quick_peer = Some(assigned);
            } else {
                log::warn!(
                    "[-] the assigned endpoint {assigned} did not answer; falling back to scanning"
                );
            }
        }
    }

    if forced.is_none() && quick_peer.is_none() {
        if let Some(cached) = lastconn::load(&lastconn_path) {
            let ring = lastconn::usable_peers(&cached, masque_carrier());
            if !ring.is_empty() && want_quick_reconnect(&cached).await {
                for peer in ring {
                    log::info!("[*] verifying cached gateway {peer} before reuse");
                    if quick_verify_masque_peer(&identity, peer, tls::session_ech()).await {
                        log::info!("[+] cached gateway {peer} still works; skipping scan");
                        quick_peer = Some(peer);
                        break;
                    }
                    log::warn!("[-] cached gateway {peer} no longer answers; trying the next one");
                }
                if quick_peer.is_none() {
                    log::warn!("[-] no remembered gateway answers; scanning fresh");
                }
            }
        }
    }

    let (mode_str, ip) = if forced.is_some() || quick_peer.is_some() {
        scan_settings_from_env()
    } else {
        let mode_str = select_scan_mode_str("").await;
        let ip = select_ip_version().await;
        (mode_str, ip)
    };

    let mut last_good_peer: Option<SocketAddr> = None;
    let mut strikes = GatewayStrikes::default();

    loop {
        // A gateway named by hand stays, whatever happens to it: it was asked for on purpose.
        if forced.is_none() {
            if let Some(peer) = strikes.give_up(Instant::now(), MASQUE_GATEWAY_REST) {
                log::warn!(
                    "[-] gateway {peer} failed {MASQUE_GATEWAY_STRIKES} times in a row; leaving it out of the scans for {:?}",
                    MASQUE_GATEWAY_REST
                );
                last_good_peer = None;
            }
        }

        let peer = if let Some(p) = quick_peer.take() {
            p
        } else {
            let retried = match last_good_peer {
                Some(p) => {
                    log::info!("[*] retrying last known-good gateway {p} before rescanning");
                    if quick_verify_masque_peer(&identity, p, tls::session_ech()).await {
                        Some(p)
                    } else {
                        log::warn!(
                            "[-] last known-good gateway {p} no longer responds; rescanning"
                        );
                        None
                    }
                }
                None => None,
            };

            match retried {
                Some(p) => p,
                None => match &forced {
                    Some(p) => match p.parse::<SocketAddr>() {
                        Ok(peer) => {
                            log::info!("[+] using forced peer {peer} (probe skipped)");
                            peer
                        }
                        Err(_) => return Err(AetherError::Other(format!("bad peer address {p}"))),
                    },
                    None => match hunt_masque_peer(
                        &identity,
                        &mode_str,
                        ip,
                        &strikes.left_out(Instant::now()),
                    )
                    .await
                    {
                        Ok(peer) => peer,
                        Err(e) => {
                            log::warn!(
                                "[-] no usable MASQUE gateway found: {e}; rescanning shortly"
                            );
                            tokio::time::sleep(masque_reconnect_delay()).await;
                            continue;
                        }
                    },
                },
            }
        };

        log::info!("[+] using cloudflare edge {peer}");

        last_good_peer = Some(peer);
        strikes.using(peer);

        let started = Instant::now();
        let ended = match &gool_inner {
            Some(path) => {
                run_gool_tunnel(&identity, peer, tls::session_ech(), &listeners, path, None).await
            }
            None => run_masque_tunnel(&identity, peer, tls::session_ech(), &listeners).await,
        };
        strikes.ended(started.elapsed());
        match ended {
            Ok(()) => log::warn!("[-] MASQUE tunnel closed; reconnecting"),
            Err(e) => log::warn!("[-] MASQUE tunnel ended: {e}; reconnecting"),
        }

        tokio::time::sleep(masque_reconnect_delay()).await;
    }
}

/// How long a tunnel has to stay up for its end not to count against the MASQUE gateways or
/// WireGuard endpoints it went through: one that carried the traffic this long proves them good,
/// so its end, a network change say, clears the ends counted before it. Only tunnels that end
/// sooner, one after another, make a run leave a gateway or an endpoint: the MASQUE run (gool over
/// masque too), the WireGuard run, the classic gool run and the masque-in-masque run alike.
const TUNNEL_STAYED_UP: std::time::Duration = std::time::Duration::from_secs(300);

/// The ends in a row of the tunnels through one gateway or endpoint, `in_a_row` before, after one
/// more that `lasted` this long: one more, or none once that tunnel stayed up [`TUNNEL_STAYED_UP`].
fn count_tunnel_end(in_a_row: u32, lasted: std::time::Duration) -> u32 {
    if lasted >= TUNNEL_STAYED_UP {
        0
    } else {
        in_a_row.saturating_add(1)
    }
}

/// How many times in a row the tunnel through a MASQUE gateway may end, each time sooner than
/// [`TUNNEL_STAYED_UP`], before the run leaves the gateway, as the WireGuard run leaves an endpoint.
const MASQUE_GATEWAY_STRIKES: u32 = 2;

/// How long a MASQUE gateway the run left stays out of its scans: as long as a WireGuard endpoint
/// by default (`wg_endpoint_cooldown`).
const MASQUE_GATEWAY_REST: std::time::Duration = std::time::Duration::from_secs(300);

/// The gateways that failed a MASQUE run: the one in use, how many times in a row its tunnel
/// ended soon after it started, and those left out of the scans until a while has passed. The
/// check before a reconnect only proves that a gateway answers, so a tunnel that ends every time,
/// as it does on an exit the exit rule refuses, would otherwise hold the run on one gateway for
/// good.
#[derive(Debug, Default)]
struct GatewayStrikes {
    current: Option<SocketAddr>,
    in_a_row: u32,
    resting: HashMap<IpAddr, Instant>,
}

impl GatewayStrikes {
    /// The tunnel goes through `peer` now; another gateway than the last starts with none.
    fn using(&mut self, peer: SocketAddr) {
        if self.current != Some(peer) {
            self.current = Some(peer);
            self.in_a_row = 0;
        }
    }

    /// The tunnel through the gateway in use ended, for whatever reason, after it `lasted` this
    /// long, see [`count_tunnel_end`].
    fn ended(&mut self, lasted: std::time::Duration) {
        self.in_a_row = count_tunnel_end(self.in_a_row, lasted);
    }

    /// The gateway in use, once its tunnel ended `MASQUE_GATEWAY_STRIKES` times in a row: its
    /// address rests until `rest` after `now`, out of the scans, and the count starts over.
    fn give_up(&mut self, now: Instant, rest: std::time::Duration) -> Option<SocketAddr> {
        if self.in_a_row < MASQUE_GATEWAY_STRIKES {
            return None;
        }
        self.in_a_row = 0;
        let peer = self.current.take()?;
        self.resting.insert(peer.ip(), now + rest);
        Some(peer)
    }

    /// The addresses the scans leave out at `now`.
    fn left_out(&mut self, now: Instant) -> HashSet<IpAddr> {
        self.resting.retain(|_, until| *until > now);
        self.resting.keys().copied().collect()
    }
}

struct MasqueHop {
    stack: netstack::StackHandle,
    exit: TunnelExit,
    _ctrl: tokio::sync::mpsc::Sender<quic::Control>,
    _guard: TaskGuard,
}

#[allow(clippy::too_many_arguments)]
async fn establish_masque(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    h2: bool,
    mtu: usize,
    datagram: usize,
    version_bait: bool,
    obfuscate: bool,
    startup: std::time::Duration,
    label: &str,
) -> Result<MasqueHop> {
    let sni = quic::masque_sni()?;
    let (chans, internals) = quic::channels();
    let quic::Channels {
        outbound_tx,
        inbound_rx,
        ctrl_tx,
    } = chans;

    let stack = netstack::spawn(&identity.ipv4, &identity.ipv6, mtu, inbound_rx, outbound_tx)?;

    let mut guard = TaskGuard::new();

    let (addr_tx, mut addr_rx) = tokio::sync::mpsc::channel::<quic::AssignedAddr>(8);
    let bridge_stack = stack.clone();
    let bridge_task = tokio::spawn(async move {
        while let Some(a) = addr_rx.recv().await {
            let res = match a.ip {
                IpAddr::V4(v4) => bridge_stack.set_addrs(Some((v4, a.prefix)), None).await,
                IpAddr::V6(v6) => bridge_stack.set_addrs(None, Some((v6, a.prefix))).await,
            };
            if let Err(e) = res {
                log::warn!("[-] failed to sync edge address into netstack: {e}");
            }
        }
    });
    guard.push(bridge_task.abort_handle());

    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel::<()>();

    let tunnel_task = if h2 {
        let h2cfg = masque_h2::H2TunnelConfig {
            peer,
            sni,
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: false,
            pin_endpoint: true,
            expected_pins: consts::masque_pins().iter().map(|p| p.to_vec()).collect(),
            ech_config_list: ech.clone(),
        };
        log::info!("[+] [{label}] MASQUE transport: HTTP/2 (TCP) to {peer} (inner mtu {mtu})");
        tokio::spawn(masque_h2::run(
            h2cfg,
            internals,
            Some(addr_tx),
            Some(ready_tx),
        ))
    } else {
        let cfg = quic::TunnelConfig {
            peer,
            sni,
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            ech_config_list: ech,
            noize: if obfuscate {
                noize_config()
            } else {
                noize::NoizeConfig::off()
            },
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: false,
            max_datagram: datagram,
            version_bait,
        };
        log::info!(
            "[+] [{label}] MASQUE transport: HTTP/3 (QUIC) to {peer} (inner mtu {mtu}, datagram {})",
            cfg.datagram_budget()
        );
        tokio::spawn(quic::run(cfg, internals, Some(addr_tx), Some(ready_tx)))
    };
    guard.push(tunnel_task.abort_handle());

    let startup_timeout = startup;
    match tokio::time::timeout(startup_timeout, ready_rx).await {
        Ok(Ok(())) => {}
        Ok(Err(_)) => {
            let joined = tunnel_task.await;
            let msg = match joined {
                Ok(Ok(())) => format!("[{label}] tunnel exited before validation"),
                Ok(Err(e)) => format!("[{label}] tunnel failed before validation: {e}"),
                Err(e) => format!("[{label}] tunnel task join error: {e}"),
            };
            return Err(AetherError::Other(msg));
        }
        Err(_) => {
            tunnel_task.abort();
            let _ = tunnel_task.await;
            return Err(AetherError::Other(format!(
                "[{label}] tunnel startup timed out after {startup_timeout:?}"
            )));
        }
    }

    Ok(MasqueHop {
        stack,
        exit: tunnel_task,
        _ctrl: ctrl_tx,
        _guard: guard,
    })
}

async fn exit_policy_guard(
    stack: &netstack::StackHandle,
    policy: &Option<exitloc::Policy>,
) -> AetherError {
    match policy {
        Some(policy) => exitloc::watch(stack, policy).await,
        None => std::future::pending().await,
    }
}

async fn run_masque_tunnel(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    listeners: &Listeners,
) -> Result<()> {
    let h2 = masque_h2::enabled();
    let dial = if h2 { masque_h2::h2_peer(peer) } else { peer };

    let mut hop = establish_masque(
        identity,
        dial,
        ech,
        h2,
        masque_tunnel_mtu(),
        quic::MAX_DATAGRAM_SIZE,
        true,
        true,
        masque_startup_timeout(),
        "masque",
    )
    .await?;

    let policy = exitloc::Policy::from_env();
    exitloc::settle(&hop.stack, &policy, "masque").await?;

    let tunnel_result = tokio::select! {
        result = &mut hop.exit => result,
        reason = exit_policy_guard(&hop.stack, &policy) => return Err(reason),
        never = listeners.serve(&hop.stack) => match never {},
    };

    match tunnel_result {
        Ok(Ok(())) => Ok(()),
        Ok(Err(e)) => Err(AetherError::Other(format!("tunnel exited: {e}"))),
        Err(e) => Err(AetherError::Other(format!("tunnel task join error: {e}"))),
    }
}

const MASQUE_DATAGRAM_OVERHEAD: usize = quic::MAX_DATAGRAM_SIZE - MASQUE_MTU;

fn mim_inner_budget(outer_mtu: usize, inner_peer: SocketAddr, h2: bool) -> (usize, usize) {
    if h2 {
        let mtu = outer_mtu.saturating_sub(100).clamp(576, 1500);
        return (quic::MAX_DATAGRAM_SIZE, mtu);
    }

    let headers = if inner_peer.is_ipv4() { 28 } else { 48 };
    let datagram = outer_mtu
        .saturating_sub(headers)
        .clamp(quic::MIN_DATAGRAM_SIZE, quic::MAX_DATAGRAM_SIZE);
    let mtu = datagram
        .saturating_sub(MASQUE_DATAGRAM_OVERHEAD)
        .clamp(576, 1500);
    (datagram, mtu)
}

const MASQUE_INNER_PORT: u16 = 443;

const MIM_INNER_TRIES: usize = 6;

fn mim_inner_startup() -> std::time::Duration {
    masque_startup_timeout().min(std::time::Duration::from_secs(12))
}

fn edge_network(ip: IpAddr) -> Option<[u8; 3]> {
    match ip {
        IpAddr::V4(v4) => {
            let octets = v4.octets();
            Some([octets[0], octets[1], octets[2]])
        }
        IpAddr::V6(_) => None,
    }
}

fn verified_scan_selected(settings: &Option<(String, prober::IpScan)>) -> bool {
    let named = match settings {
        Some((mode, _)) => mode.clone(),
        None => std::env::var("AETHER_SCAN").unwrap_or_default(),
    };
    prober::ScanMode::parse(&named) == prober::ScanMode::Verified
}

fn spread_hops(found: &mut [SocketAddr]) {
    if found.len() < 2 {
        return;
    }
    let first = found[0];
    let network = edge_network(first.ip());
    if let Some(other) = found
        .iter()
        .position(|peer| edge_network(peer.ip()) != network)
    {
        if other >= 1 {
            found.swap(1, other);
        }
    }
}

fn masque_verified_ladder(outer: SocketAddr, count: usize) -> Vec<SocketAddr> {
    if outer.is_ipv6() {
        return Vec::new();
    }

    let mut out: Vec<SocketAddr> = Vec::new();
    let mut seen: HashSet<SocketAddr> = HashSet::new();
    let outer_network = edge_network(outer.ip());

    let verified: Vec<IpAddr> = prober::MASQUE_VERIFIED_GATEWAYS
        .iter()
        .filter_map(|entry| entry.parse().ok())
        .collect();

    let push =
        |out: &mut Vec<SocketAddr>, seen: &mut HashSet<SocketAddr>, ip: IpAddr, port: u16| {
            let peer = SocketAddr::new(ip, port);
            if ip != outer.ip() && seen.insert(peer) {
                out.push(peer);
            }
        };

    for ip in verified
        .iter()
        .filter(|ip| edge_network(**ip) != outer_network)
    {
        push(&mut out, &mut seen, *ip, MASQUE_INNER_PORT);
    }
    for ip in &verified {
        push(&mut out, &mut seen, *ip, MASQUE_INNER_PORT);
    }
    for &port in prober::MASQUE_ALT_PORTS {
        for ip in &verified {
            push(&mut out, &mut seen, *ip, port);
        }
    }

    out.truncate(count.max(1));
    out
}

fn inner_masque_candidates(outer: SocketAddr, count: usize, verified: bool) -> Vec<SocketAddr> {
    let mut out: Vec<SocketAddr> = Vec::new();
    let mut seen: HashSet<SocketAddr> = HashSet::new();

    if verified {
        let room = count.saturating_sub(2).max(1);
        for peer in masque_verified_ladder(outer, room) {
            if seen.insert(peer) {
                out.push(peer);
            }
        }
    }

    for peer in sibling_candidates(outer, count) {
        if out.len() >= count {
            break;
        }
        if seen.insert(peer) {
            out.push(peer);
        }
    }

    out.truncate(count.max(1));
    out
}

fn sibling_candidates(outer: SocketAddr, count: usize) -> Vec<SocketAddr> {
    use rand::RngExt;

    let mut rng = rand::rng();
    let mut out: Vec<SocketAddr> = Vec::new();

    match outer.ip() {
        IpAddr::V4(v4) => {
            let octets = v4.octets();
            let mut hosts: Vec<u8> = (1..=254u8).filter(|host| *host != octets[3]).collect();
            for index in (1..hosts.len()).rev() {
                let other = rng.random_range(0..=index);
                hosts.swap(index, other);
            }
            for host in hosts.into_iter().take(count) {
                let ip = Ipv4Addr::new(octets[0], octets[1], octets[2], host);
                out.push(SocketAddr::new(IpAddr::V4(ip), MASQUE_INNER_PORT));
            }
        }
        IpAddr::V6(v6) => {
            let mut segments = v6.segments();
            let last = segments[7];
            let mut seen: HashSet<u16> = HashSet::new();
            while out.len() < count && seen.len() < count * 8 {
                let candidate = rng.random_range(1..=u16::MAX);
                if candidate == last || !seen.insert(candidate) {
                    continue;
                }
                segments[7] = candidate;
                out.push(SocketAddr::new(
                    IpAddr::V6(std::net::Ipv6Addr::from(segments)),
                    MASQUE_INNER_PORT,
                ));
            }
        }
    }

    out
}

async fn spawn_tcp_forwarder(
    outer: &netstack::StackHandle,
    remote: SocketAddr,
) -> Result<(SocketAddr, TaskGuard)> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let local = listener.local_addr()?;
    let stack = outer.clone();

    let task = tokio::spawn(async move {
        let mut clients = tokio::task::JoinSet::new();
        loop {
            tokio::select! {
                accepted = listener.accept() => {
                    let Ok((sock, _)) = accepted else { break };
                    let stack = stack.clone();
                    clients.spawn(async move {
                        match stack.open_tcp(remote).await {
                            Ok(conn) => {
                                let (sender, from_stack) = conn.into_split();
                                socks::relay_tunneled(
                                    sock,
                                    sender,
                                    from_stack,
                                    socks::half_close_linger(),
                                )
                                .await;
                            }
                            Err(e) => log::warn!(
                                "[-] the inner hop could not reach {remote} through the outer tunnel: {e}"
                            ),
                        }
                    });
                }
                Some(_) = clients.join_next(), if !clients.is_empty() => {}
            }
        }
    });

    Ok((local, TaskGuard(vec![task.abort_handle()])))
}

async fn run_masque_in_masque(
    primary: &account::Identity,
    secondary: &account::Identity,
    peer: SocketAddr,
    inner_peers: &[InnerEdge],
    ech: Option<Vec<u8>>,
    listeners: &Listeners,
) -> Result<()> {
    let h2 = masque_h2::enabled();
    let outer_mtu = masque_tunnel_mtu();

    log::info!("[*] establishing outer MASQUE tunnel to {peer}...");
    let mut outer = establish_masque(
        primary,
        peer,
        ech,
        h2,
        outer_mtu,
        quic::MAX_DATAGRAM_SIZE,
        true,
        true,
        masque_startup_timeout(),
        "outer",
    )
    .await?;

    let mut chosen: Option<(SocketAddr, MasqueHop, TaskGuard)> = None;

    for edge in inner_peers
        .iter()
        .copied()
        .filter(|candidate| candidate.addr.ip() != peer.ip())
    {
        let inner_peer = edge.addr;
        let (inner_datagram, inner_mtu) = mim_inner_budget(outer_mtu, inner_peer, h2);

        if !h2 && inner_datagram + 28 > outer_mtu {
            log::warn!(
                "[-] the outer link carries {outer_mtu} bytes, too little for an inner quic \
                 datagram; raise AETHER_MASQUE_MTU or use --h2 for both hops"
            );
        }

        let (forwarder, forwarder_guard) = if h2 {
            spawn_tcp_forwarder(&outer.stack, inner_peer).await?
        } else {
            spawn_udp_forwarder(&outer.stack, inner_peer).await?
        };
        log::info!(
            "[*] trying inner MASQUE edge {inner_peer} ({}) through the outer tunnel via              {forwarder}",
            edge.origin.label()
        );

        match establish_masque(
            secondary,
            forwarder,
            None,
            h2,
            inner_mtu,
            inner_datagram,
            false,
            // Off, for the same reason warp-in-warp turns it off: the inner
            // hop's wire is the outer tunnel, which is already opaque.
            false,
            mim_inner_startup(),
            "inner",
        )
        .await
        {
            Ok(hop) => {
                log::info!("[+] inner MASQUE tunnel established through {inner_peer}");
                chosen = Some((inner_peer, hop, forwarder_guard));
                break;
            }
            Err(e) => log::info!(
                "[-] inner edge {inner_peer} does not serve masque from inside the tunnel: {e}"
            ),
        }
    }

    let Some((inner_peer, mut inner, _forwarder_guard)) = chosen else {
        return Err(AetherError::Other(
            "no inner masque edge answered through the outer tunnel".into(),
        ));
    };

    let policy = exitloc::Policy::from_env();
    exitloc::settle(&inner.stack, &policy, "masque-in-masque").await?;

    log::info!("[+] masque-in-masque ready: {peer} (outer) and {inner_peer} (inner)");

    #[derive(PartialEq)]
    enum Winner {
        Outer,
        Inner,
        Policy,
    }

    let (outcome, winner) = tokio::select! {
        result = &mut outer.exit => (join_outcome("outer masque tunnel", result), Winner::Outer),
        result = &mut inner.exit => (join_outcome("inner masque tunnel", result), Winner::Inner),
        never = listeners.serve(&inner.stack) => match never {},
        reason = exit_policy_guard(&inner.stack, &policy) => (Err(reason), Winner::Policy),
    };

    if winner != Winner::Outer {
        outer.exit.abort();
        let _ = (&mut outer.exit).await;
    }
    if winner != Winner::Inner {
        inner.exit.abort();
        let _ = (&mut inner.exit).await;
    }

    outcome
}

async fn run_mim(
    primary: account::Identity,
    secondary: account::Identity,
    listeners: Listeners,
) -> Result<()> {
    let pinned = mim_endpoints_from_env()?;

    match (pinned.outer, pinned.inner) {
        (Some(outer), Some(inner)) => log::info!(
            "[+] masque-in-masque endpoints given by hand: {outer} (outer) and {inner} (inner); the scan is skipped"
        ),
        (Some(outer), None) => log::info!(
            "[+] outer masque-in-masque endpoint given by hand: {outer}; the inner one is chosen for you"
        ),
        (None, Some(inner)) => log::info!(
            "[+] inner masque-in-masque endpoint given by hand: {inner}; scanning for the outer one"
        ),
        (None, None) => {}
    }

    let mut outer_peer = pinned.outer;
    let mut inner_peer = pinned.inner;
    let mut consecutive_fails: u32 = 0;
    let mut scan_settings: Option<(String, prober::IpScan)> = None;
    const MAX_CONSECUTIVE_FAILS: u32 = 2;

    loop {
        if consecutive_fails >= MAX_CONSECUTIVE_FAILS {
            if pinned.outer.is_none() {
                if let Some(peer) = outer_peer.take() {
                    log::warn!(
                        "[-] outer edge {peer} failed {consecutive_fails} times in a row; rescanning"
                    );
                }
            }
            if pinned.inner.is_none() {
                if let Some(peer) = inner_peer.take() {
                    log::warn!(
                        "[-] inner edge {peer} failed {consecutive_fails} times in a row; trying another"
                    );
                }
            }
            consecutive_fails = 0;
        }

        let outer = match outer_peer {
            Some(peer) => peer,
            None => {
                let (mode_str, ip) = match &scan_settings {
                    Some(settings) => settings.clone(),
                    None => {
                        let mode_str = select_scan_mode_str(MIM_MANUAL_TIP).await;
                        let ip = select_ip_version().await;
                        scan_settings.insert((mode_str, ip)).clone()
                    }
                };

                match hunt_masque_peer(&primary, &mode_str, ip, &HashSet::new()).await {
                    Ok(peer) => peer,
                    Err(e) => {
                        log::warn!("[-] no usable MASQUE gateway found: {e}; rescanning shortly");
                        tokio::time::sleep(masque_reconnect_delay()).await;
                        continue;
                    }
                }
            }
        };

        let candidates = match inner_peer {
            // Asked for by name. Not second-guessed, and not filtered against
            // the outer edge either: a pinned peer is the user's answer.
            Some(peer) => vec![InnerEdge {
                addr: peer,
                origin: EdgeOrigin::Pinned,
            }],
            None => order_inner_candidates(
                assigned_masque_peers(&secondary).await,
                outer,
                inner_masque_candidates(
                    outer,
                    MIM_INNER_TRIES,
                    verified_scan_selected(&scan_settings),
                ),
            ),
        };

        if candidates.is_empty() {
            return Err(AetherError::Other(
                "no second masque edge is known for the inner hop".into(),
            ));
        }

        outer_peer = Some(outer);

        let started = Instant::now();
        match run_masque_in_masque(
            &primary,
            &secondary,
            outer,
            &candidates,
            tls::session_ech(),
            &listeners,
        )
        .await
        {
            Ok(()) => log::warn!("[-] masque-in-masque tunnel closed; reconnecting"),
            Err(e) => log::warn!("[-] masque-in-masque tunnel ended: {e}; reconnecting"),
        }
        consecutive_fails = count_tunnel_end(consecutive_fails, started.elapsed());

        tokio::time::sleep(masque_reconnect_delay()).await;
    }
}

fn wg_keepalive_secs() -> u16 {
    std::env::var("AETHER_WG_KEEPALIVE")
        .ok()
        .and_then(|v| v.parse().ok())
        .filter(|&v| v > 0)
        .unwrap_or(5)
}

fn wg_profile_candidates() -> Vec<(String, aethernoize::AetherNoizeConfig)> {
    let primary = std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "balanced".to_string());
    log::info!("[+] aethernoize primary profile: {primary}");

    let mut names = vec![primary.clone()];
    if std::env::var("AETHER_WG_NO_PROFILE_RETRY").is_err() {
        for fallback in ["balanced", "aggressive", "light", "off"] {
            if !names.iter().any(|n| n.eq_ignore_ascii_case(fallback)) {
                names.push(fallback.to_string());
            }
        }
    }

    names
        .into_iter()
        .map(|n| {
            let cfg = aethernoize::from_profile(&n);
            (n, cfg)
        })
        .collect()
}

async fn hunt_wg_peer_with_profile(
    identity: &account::Identity,
    mode_str: &str,
    ip: prober::IpScan,
    profile: aethernoize::AetherNoizeConfig,
    excluded: &HashSet<SocketAddr>,
) -> Result<SocketAddr> {
    let mode = wg_prober::WgScanMode::parse(mode_str);
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;

    let probe = wg_prober::WgProbe {
        private_key: std::sync::Arc::new(private_key),
        peer_public_key: std::sync::Arc::new(peer_public),
        client_id: identity.client_id,
        local_ipv4: identity
            .ipv4
            .parse()
            .map_err(|_| AetherError::Other("invalid ipv4".into()))?,
        aethernoize: profile,
        ports: wireguard::WG_PORTS.to_vec(),
        ip,
        excluded: excluded.clone(),
    };

    let best = wg_prober::hunt_best_wg_endpoint(&probe, mode).await?;
    Ok(SocketAddr::new(best.ip, best.port))
}

fn wg_reconnect_delay() -> std::time::Duration {
    let secs = std::env::var("AETHER_WG_RECONNECT_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(2);
    std::time::Duration::from_secs(secs)
}

fn wg_endpoint_cooldown() -> std::time::Duration {
    let secs = std::env::var("AETHER_WG_ENDPOINT_COOLDOWN_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(300);
    std::time::Duration::from_secs(secs)
}

async fn hunt_wg_peer(
    identity: &account::Identity,
    candidates: &[(String, aethernoize::AetherNoizeConfig)],
    mode_str: &str,
    ip: prober::IpScan,
    excluded: &HashSet<SocketAddr>,
) -> Result<(SocketAddr, aethernoize::AetherNoizeConfig, String)> {
    if let Some(found) = hunt_wg_anchors(identity, candidates, ip).await {
        return Ok(found);
    }

    // A hard ceiling on the sweep, not a check between profiles: checking
    // between them lets the last one start just under the limit and then run its
    // own full budget past it, which is how a bounded search still takes minutes
    // longer than the bound.
    match tokio::time::timeout(
        wg_hunt_budget(),
        hunt_wg_peer_sweep(identity, candidates, mode_str, ip, excluded),
    )
    .await
    {
        Ok(found) => found,
        Err(_) => {
            log::warn!("[-] the WireGuard search reached its time limit");
            Err(no_wireguard_endpoint())
        }
    }
}

async fn run_wireguard(
    identity: account::Identity,
    listeners: Listeners,
    lastconn_path: String,
) -> Result<()> {
    let candidates = wg_profile_candidates();

    let forced = std::env::var("AETHER_WG_PEER")
        .ok()
        .or_else(|| std::env::var("AETHER_PEER").ok());

    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;
    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;

    let mut quick: Option<(SocketAddr, aethernoize::AetherNoizeConfig, String)> = None;

    if forced.is_none() {
        if let Some(assigned) = std::env::var("AETHER_TEAM_ENDPOINT")
            .ok()
            .and_then(|value| value.parse::<SocketAddr>().ok())
        {
            log::info!("[*] verifying the endpoint the organization assigned: {assigned}");
            for (name, profile) in &candidates {
                match wireguard::verify_endpoint(
                    assigned,
                    private_key,
                    peer_public,
                    identity.client_id,
                    ipv4,
                    profile,
                    std::time::Duration::from_secs(8),
                    None,
                )
                .await
                {
                    Ok(rtt) => {
                        log::info!(
                            "[+] the assigned endpoint {assigned} works with profile '{name}' (rtt {rtt:?}); skipping the scan"
                        );
                        quick = Some((assigned, profile.clone(), name.clone()));
                        break;
                    }
                    Err(e) => {
                        log::debug!(
                            "[-] assigned endpoint {assigned} failed profile '{name}': {e}"
                        );
                    }
                }
            }
            if quick.is_none() {
                log::warn!(
                    "[-] the assigned endpoint {assigned} did not pass validation; falling back to scanning"
                );
            }
        }
    }

    if forced.is_none() && quick.is_none() {
        if let Some(cached) = lastconn::load(&lastconn_path) {
            let ring = lastconn::usable_peers(&cached, lastconn::CARRIER_WIREGUARD);
            if !ring.is_empty() && want_quick_reconnect(&cached).await {
                let profile = aethernoize::from_profile(&cached.profile);
                for peer in ring {
                    log::info!("[*] verifying cached WireGuard endpoint {peer} before reuse");
                    match wireguard::verify_endpoint(
                        peer,
                        private_key,
                        peer_public,
                        identity.client_id,
                        ipv4,
                        &profile,
                        std::time::Duration::from_secs(6),
                        None,
                    )
                    .await
                    {
                        Ok(rtt) => {
                            log::info!(
                                "[+] cached endpoint {peer} still works (rtt {:?}); skipping scan",
                                rtt
                            );
                            quick = Some((peer, profile.clone(), cached.profile.clone()));
                            break;
                        }
                        Err(e) => {
                            log::warn!(
                                "[-] cached endpoint {peer} no longer answers ({e}); trying the next one"
                            );
                        }
                    }
                }
                if quick.is_none() {
                    log::warn!("[-] no remembered endpoint answers; scanning fresh");
                }
            }
        }
    }

    let (mode_str, ip) = if forced.is_some() || quick.is_some() {
        scan_settings_from_env()
    } else {
        let mode_str = select_scan_mode_str("").await;
        let ip = select_ip_version().await;
        (mode_str, ip)
    };

    let mut last_good: Option<(SocketAddr, aethernoize::AetherNoizeConfig, String)> = None;
    let mut consecutive_fails_on_peer: u32 = 0;
    let mut endpoint_cooldowns: HashMap<SocketAddr, Instant> = HashMap::new();
    const MAX_CONSECUTIVE_FAILS: u32 = 2;

    loop {
        let now = Instant::now();
        endpoint_cooldowns.retain(|_, until| *until > now);
        if consecutive_fails_on_peer >= MAX_CONSECUTIVE_FAILS {
            if let Some((peer, _, _)) = last_good.take() {
                let cooldown = wg_endpoint_cooldown();
                endpoint_cooldowns.insert(peer, now + cooldown);
                log::warn!(
                    "[-] endpoint {peer} failed {consecutive_fails_on_peer} times in a row; excluding it for {:?}",
                    cooldown
                );
            }
            consecutive_fails_on_peer = 0;
        }

        let (peer, profile, profile_name) = if let Some(q) = quick.take() {
            q
        } else {
            let retried = match &last_good {
                Some((p, profile, _)) => {
                    log::info!(
                        "[*] retrying last known-good WireGuard endpoint {p} before rescanning"
                    );
                    match wireguard::verify_endpoint(
                        *p,
                        private_key,
                        peer_public,
                        identity.client_id,
                        ipv4,
                        profile,
                        std::time::Duration::from_secs(6),
                        None,
                    )
                    .await
                    {
                        Ok(_) => Some(last_good.clone().unwrap()),
                        Err(e) => {
                            log::warn!("[-] last known-good endpoint {p} no longer responds ({e}); rescanning");
                            None
                        }
                    }
                }
                None => None,
            };

            match retried {
                Some(v) => v,
                None => {
                    if let Some(ref p) = forced {
                        let peer: SocketAddr = p
                            .parse()
                            .map_err(|_| AetherError::Other(format!("bad peer address {p}")))?;
                        log::info!("[+] using forced peer {peer} (probe skipped)");

                        let mut chosen = None;
                        for (name, profile) in &candidates {
                            log::info!(
                                "[*] testing forced peer {peer} with aethernoize profile '{name}'"
                            );
                            match wireguard::verify_endpoint(
                                peer,
                                private_key,
                                peer_public,
                                identity.client_id,
                                ipv4,
                                profile,
                                std::time::Duration::from_secs(10),
                                None,
                            )
                            .await
                            {
                                Ok(rtt) => {
                                    log::info!(
                                        "[+] profile '{}' passed handshake + data-plane (rtt {:?})",
                                        name,
                                        rtt
                                    );
                                    chosen = Some((peer, profile.clone(), name.clone()));
                                    break;
                                }
                                Err(e) => {
                                    log::warn!("[-] profile '{name}' failed on forced peer: {e}");
                                }
                            }
                        }
                        match chosen {
                            Some(v) => v,
                            None => {
                                log::warn!(
                                    "[-] forced peer {peer} failed with every aethernoize profile; retrying shortly"
                                );
                                tokio::time::sleep(wg_reconnect_delay()).await;
                                continue;
                            }
                        }
                    } else {
                        let excluded: HashSet<SocketAddr> =
                            endpoint_cooldowns.keys().copied().collect();
                        match hunt_wg_peer(&identity, &candidates, &mode_str, ip, &excluded).await {
                            Ok(v) => v,
                            Err(e) => {
                                log::warn!("[-] no usable WireGuard endpoint found: {e}; rescanning shortly");
                                tokio::time::sleep(wg_reconnect_delay()).await;
                                continue;
                            }
                        }
                    }
                }
            }
        };

        log::info!("[+] using cloudflare edge {peer}");

        let is_same_peer_as_before = last_good.as_ref().map(|(p, _, _)| *p) == Some(peer);
        if !is_same_peer_as_before {
            consecutive_fails_on_peer = 0;
        }
        last_good = Some((peer, profile.clone(), profile_name));

        let started = Instant::now();
        match run_wireguard_tunnel(identity.clone(), peer, profile, &listeners).await {
            Ok(()) => log::warn!("[-] WireGuard tunnel closed; reconnecting"),
            Err(e) => log::warn!("[-] WireGuard tunnel ended: {e}; reconnecting"),
        }
        consecutive_fails_on_peer = count_tunnel_end(consecutive_fails_on_peer, started.elapsed());

        tokio::time::sleep(wg_reconnect_delay()).await;
    }
}

fn wg_tunnel_validate_timeout() -> std::time::Duration {
    let secs = std::env::var("AETHER_WG_VALIDATE_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(10);
    std::time::Duration::from_secs(secs)
}

async fn run_wireguard_tunnel(
    identity: account::Identity,
    peer: SocketAddr,
    aethernoize: aethernoize::AetherNoizeConfig,
    listeners: &Listeners,
) -> Result<()> {
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;
    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;

    log::info!("[*] validating WireGuard tunnel with {peer} (handshake + data-plane) before serving socks5...");
    let (_, session) = wireguard::verify_endpoint_keep_session(
        peer,
        private_key,
        peer_public,
        identity.client_id,
        ipv4,
        &aethernoize,
        wg_tunnel_validate_timeout(),
        Some(wg_keepalive_secs()),
    )
    .await
    .map_err(|e| AetherError::Other(format!("tunnel failed validation: {e}")))?;
    log::info!("[+] wireguard tunnel validated (end-to-end data confirmed); serving socks5");

    let (outbound_tx, outbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());
    let (inbound_tx, inbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());

    let tunnel = wireguard::WgTunnel::from_established(
        session,
        std::sync::Arc::new(aethernoize),
        inbound_tx,
        ipv4,
    );

    let stack = netstack::spawn(
        &identity.ipv4,
        &identity.ipv6,
        WIREGUARD_MTU,
        inbound_rx,
        outbound_tx,
    )?;

    let policy = exitloc::Policy::from_env();
    exitloc::settle(&stack, &policy, "wireguard").await?;

    let tunnel_result = tokio::select! {
        result = tunnel.run(outbound_rx) => result,
        reason = exit_policy_guard(&stack, &policy) => return Err(reason),
        never = listeners.serve(&stack) => match never {},
    };

    drop(stack);

    match tunnel_result {
        Ok(()) => Ok(()),
        Err(e) => Err(AetherError::Other(format!("wireguard tunnel exited: {e}"))),
    }
}

type TunnelExit = tokio::task::JoinHandle<Result<()>>;

/// The upstream setting that has the tunnel dialled through `name`, Tor or Psiphon around it, at
/// `socks`. Checked as the setting is read: one set aside as unusable would have the tunnel, and
/// a registration, dial out around the carrier, straight out, so it ends aether instead.
fn carrier_upstream(name: &str, socks: SocketAddr) -> Result<String> {
    let setting = format!("socks5://{socks}");
    upstream::Upstream::parse(&setting).map_err(|e| {
        AetherError::Other(format!(
            "{name} listens on {socks}, which the tunnel cannot be dialled through ({}); \
             aether ends rather than dial around {name}",
            e.detail()
        ))
    })?;
    Ok(setting)
}

/// The packet ends of a running WireGuard tunnel.
///
/// A tunnel that fronts a userspace TCP stack and one that is handed straight to
/// an Android interface differ only in what consumes these, so they are taken
/// out here rather than each caller rebuilding the tunnel.
fn http_proxy_listen() -> Option<SocketAddr> {
    let raw = std::env::var("AETHER_HTTP_PROXY").ok()?;
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    match trimmed.parse::<SocketAddr>() {
        Ok(addr) => Some(addr),
        Err(_) => {
            log::warn!("[-] ignoring an unparsable http proxy address: {trimmed}");
            None
        }
    }
}

async fn bind_http_proxy() -> Result<Option<tokio::net::TcpListener>> {
    match http_proxy_listen() {
        Some(listen) => Ok(Some(
            socks::bind_listener("http proxy", listen, "--http-proxy").await?,
        )),
        None => Ok(None),
    }
}

async fn establish_wg(
    identity: &account::Identity,
    peer: SocketAddr,
    mtu: usize,
    obfuscate: bool,
    keepalive: u16,
    label: &'static str,
) -> Result<(netstack::StackHandle, TunnelExit)> {
    let channels = establish_wg_channels(identity, peer, obfuscate, keepalive, label).await?;
    let stack = netstack::spawn(
        &identity.ipv4,
        &identity.ipv6,
        mtu,
        channels.inbound_rx,
        channels.outbound_tx,
    )?;
    Ok((stack, channels.exit))
}

struct TaskGuard(Vec<tokio::task::AbortHandle>);

impl TaskGuard {
    fn new() -> Self {
        Self(Vec::new())
    }

    fn push(&mut self, handle: tokio::task::AbortHandle) {
        self.0.push(handle);
    }
}

impl Drop for TaskGuard {
    fn drop(&mut self) {
        for handle in self.0.drain(..) {
            handle.abort();
        }
    }
}

async fn spawn_udp_forwarder(
    outer: &netstack::StackHandle,
    remote: SocketAddr,
) -> Result<(SocketAddr, TaskGuard)> {
    let sock = std::sync::Arc::new(tokio::net::UdpSocket::bind("127.0.0.1:0").await?);
    let local = sock.local_addr()?;

    let udp = outer.open_udp().await?;
    let (udp_tx, mut udp_rx) = udp.into_split();

    let inner_peer: std::sync::Arc<tokio::sync::Mutex<Option<SocketAddr>>> =
        std::sync::Arc::new(tokio::sync::Mutex::new(None));

    let up_sock = sock.clone();
    let up_peer = inner_peer.clone();
    let up_task = tokio::spawn(async move {
        let mut buf = vec![0u8; 65536];
        // The inner engine is the only sender, and it moves: it rebinds its
        // socket when it reconnects. Latching onto the first source port it
        // used dropped everything after the move and killed the tunnel, so
        // replies follow whichever port it last sent from.
        while let Ok((n, from)) = up_sock.recv_from(&mut buf).await {
            {
                let mut known = up_peer.lock().await;
                if *known != Some(from) {
                    if let Some(previous) = *known {
                        log::debug!("inner udp forwarder follows {from} now, was {previous}");
                    }
                    *known = Some(from);
                }
            }
            if udp_tx.send_to(remote, buf[..n].to_vec()).await.is_err() {
                break;
            }
        }
    });

    let down_sock = sock.clone();
    let down_peer = inner_peer.clone();
    let down_task = tokio::spawn(async move {
        while let Some((_src, data)) = udp_rx.recv().await {
            let dst = *down_peer.lock().await;
            if let Some(dst) = dst {
                let _ = down_sock.send_to(&data, dst).await;
            }
        }
    });

    let guard = TaskGuard(vec![up_task.abort_handle(), down_task.abort_handle()]);

    Ok((local, guard))
}

async fn run_warp_in_warp(
    primary: account::Identity,
    secondary: account::Identity,
    peer: SocketAddr,
    inner_peer: SocketAddr,
    listeners: &Listeners,
) -> Result<()> {
    if inner_peer.ip() == peer.ip() {
        return Err(AetherError::Other(format!(
            "warp-in-warp needs two separate edges but both hops landed on {}",
            peer.ip()
        )));
    }

    let mut tasks = TaskGuard::new();

    log::info!("[*] establishing outer WARP tunnel to {peer}...");
    let (outer_stack, mut outer_exit) =
        establish_wg(&primary, peer, WIREGUARD_MTU, true, 5, "outer").await?;
    tasks.push(outer_exit.abort_handle());

    let (forwarder, _forwarder_guard) = spawn_udp_forwarder(&outer_stack, inner_peer).await?;
    log::info!("[+] inner endpoint {inner_peer} tunneled through outer warp via {forwarder}");

    log::info!("[*] establishing inner WARP tunnel (warp-in-warp)...");
    let (inner_stack, mut inner_exit) =
        establish_wg(&secondary, forwarder, INNER_MTU, false, 20, "inner").await?;
    tasks.push(inner_exit.abort_handle());

    let policy = exitloc::Policy::from_env();
    exitloc::settle(&inner_stack, &policy, "warp-in-warp").await?;

    #[derive(PartialEq)]
    enum Winner {
        Outer,
        Inner,
        Policy,
    }

    let (outcome, winner) = tokio::select! {
        result = &mut outer_exit => (join_outcome("outer wireguard tunnel", result), Winner::Outer),
        result = &mut inner_exit => (join_outcome("inner wireguard tunnel", result), Winner::Inner),
        never = listeners.serve(&inner_stack) => match never {},
        reason = exit_policy_guard(&inner_stack, &policy) => (Err(reason), Winner::Policy),
    };

    if winner != Winner::Outer {
        outer_exit.abort();
        let _ = outer_exit.await;
    }
    if winner != Winner::Inner {
        inner_exit.abort();
        let _ = inner_exit.await;
    }

    drop(inner_stack);
    drop(outer_stack);

    outcome
}

/// Whether a tunnel of `protocol` needs UDP to reach WARP, which Tor and Psiphon around the tunnel
/// do not carry: WireGuard does, and so does the classic gool, WireGuard in WireGuard. Gool over
/// masque dials MASQUE alone, which runs over HTTP/2.
fn needs_udp_to_warp(protocol: Protocol, classic_gool: bool) -> bool {
    match protocol {
        Protocol::WireGuard => true,
        Protocol::WarpInWarp => classic_gool,
        Protocol::Masque | Protocol::MasqueInMasque => false,
    }
}

fn gool_classic() -> bool {
    matches!(
        std::env::var("AETHER_GOOL_MODE").as_deref(),
        Ok("wiw") | Ok("wg") | Ok("classic")
    ) || [
        "AETHER_WIW_OUTER_PEER",
        "AETHER_WIW_INNER_PEER",
        "AETHER_WIW_PEERS",
    ]
    .iter()
    .any(|key| env_value(key).is_some())
}

fn gool_inner_peers(identity: &account::Identity) -> Result<Vec<SocketAddr>> {
    if let Some(value) = env_value("AETHER_GOOL_INNER") {
        return Ok(vec![parse_endpoint(&value)?]);
    }
    let mut peers: Vec<SocketAddr> = Vec::new();
    let named = std::iter::once(identity.assigned_endpoint.trim()).chain(wireguard::wg_seeds_v4());
    for host in named {
        if let Ok(ip) = host.parse::<IpAddr>() {
            let peer = SocketAddr::new(ip, WG_EXAMPLE_PORT);
            if !peers.contains(&peer) {
                peers.push(peer);
            }
        }
    }
    Ok(peers)
}

async fn gool_inner_identity(
    outer: &netstack::StackHandle,
    inner_path: &str,
) -> Result<account::Identity> {
    if let Some(identity) = config::load(inner_path)? {
        log::info!("[+] loaded the gool wireguard identity from {inner_path}");
        return Ok(identity);
    }

    let listener = socks::bind_listener("socks5", "127.0.0.1:0".parse().unwrap(), "--bind").await?;
    let through = listener.local_addr()?;
    let stack = outer.clone();
    let socks_task = tokio::spawn(async move { socks::serve(listener, stack).await });
    let _socks_guard = TaskGuard(vec![socks_task.abort_handle()]);

    log::info!("[*] registering the gool wireguard identity through the masque tunnel");
    // Through the tunnel for this registration alone: AETHER_UPSTREAM, which the rest of the
    // process dials out through, stays as it is.
    let tunnel = upstream::Upstream::parse(&format!("socks5h://{through}"))?;
    let identity = upstream::through(tunnel, provision_gool(inner_path)).await?;
    log::info!(
        "[+] gool wireguard identity registered from inside warp and saved to {inner_path}: device={} ipv4={}",
        identity.device_id,
        identity.ipv4
    );
    Ok(identity)
}

/// `--register gool`: the wireguard identity gool carries inside its masque tunnel, saved at `path`,
/// or a new one registered now, from where the other identities are registered. The tunnel
/// registers one from inside warp only when there is none, see [`gool_inner_identity`].
async fn load_or_provision_gool(path: &str) -> Result<account::Identity> {
    if let Some(identity) = config::load(path)? {
        log::info!("[+] loaded the gool wireguard identity from {path}");
        return Ok(identity);
    }
    let identity = provision_gool(path).await?;
    log::info!(
        "[+] gool wireguard identity registered and saved to {path}: device={} ipv4={}",
        identity.device_id,
        identity.ipv4
    );
    Ok(identity)
}

/// Registers the wireguard identity gool carries inside its masque tunnel, enables WARP on it and
/// saves it at `path` once both are done, see [`enable_warp_on`]. The tunnel tries again at each
/// reconnect, so a device registered here whose enabling failed is kept for the next try of this
/// run, which enables it rather than registering yet another, unless the API refused it.
async fn provision_gool(path: &str) -> Result<account::Identity> {
    let kept = GOOL_NOT_ENABLED
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .take();
    let registered = match kept {
        Some(identity) => identity,
        None => account::provision_wg(consts::DEFAULT_MODEL, consts::DEFAULT_LOCALE, None).await?,
    };
    if let Err(error) = enable_warp_on(&registered).await {
        if worth_enabling_again(&error) {
            *GOOL_NOT_ENABLED.lock().unwrap_or_else(|e| e.into_inner()) = Some(registered);
        }
        return Err(error);
    }
    config::save(path, &registered)?;
    Ok(registered)
}

/// The gool device of this run that is registered but not enabled yet, see [`provision_gool`].
static GOOL_NOT_ENABLED: std::sync::Mutex<Option<account::Identity>> = std::sync::Mutex::new(None);

/// Whether a device whose WARP enabling ended in `error` is worth enabling again rather than
/// registering anew: not when the API refused the device itself.
fn worth_enabling_again(error: &AetherError) -> bool {
    !matches!(error, AetherError::IdentityRefused(_))
}

const GOOL_INNER_ATTEMPTS: u32 = 2;

async fn run_gool_tunnel(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    listeners: &Listeners,
    inner_path: &str,
    ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    let h2 = masque_h2::enabled();
    let dial = if h2 { masque_h2::h2_peer(peer) } else { peer };

    let mut outer = establish_masque(
        identity,
        dial,
        ech,
        h2,
        masque_tunnel_mtu(),
        quic::MAX_DATAGRAM_SIZE,
        true,
        true,
        masque_startup_timeout(),
        "outer",
    )
    .await?;

    let inner_identity = gool_inner_identity(&outer.stack, inner_path).await?;
    let candidates = gool_inner_peers(&inner_identity)?;

    let mut established = None;
    let mut last_error = AetherError::Other("no wireguard endpoint to try".into());
    'peers: for inner_peer in candidates {
        let (forwarder, forwarder_guard) = spawn_udp_forwarder(&outer.stack, inner_peer).await?;
        log::info!("[*] wireguard to {inner_peer} rides inside the masque tunnel via {forwarder}");
        for attempt in 1..=GOOL_INNER_ATTEMPTS {
            match establish_wg(&inner_identity, forwarder, INNER_MTU, false, 25, "inner").await {
                Ok(hop) => {
                    established = Some((inner_peer, hop, forwarder_guard));
                    break 'peers;
                }
                Err(e) => {
                    log::warn!("[-] inner wireguard {inner_peer} attempt {attempt} failed: {e}");
                    last_error = e;
                }
            }
        }
    }
    let Some((inner_peer, (inner_stack, mut inner_exit), _forwarder_guard)) = established else {
        return Err(last_error);
    };
    if inner_identity.assigned_endpoint != inner_peer.ip().to_string()
        && env_value("AETHER_GOOL_INNER").is_none()
    {
        let remembered = account::Identity {
            assigned_endpoint: inner_peer.ip().to_string(),
            ..inner_identity.clone()
        };
        if config::save(inner_path, &remembered).is_ok() {
            log::info!(
                "[+] gool remembers {} as its wireguard endpoint",
                inner_peer.ip()
            );
        }
    }

    let policy = exitloc::Policy::from_env();
    exitloc::settle(&inner_stack, &policy, "gool").await?;
    if let Some(ready) = ready {
        let _ = ready.send(());
    }

    log::info!("[+] gool ready: masque {peer} carries wireguard {inner_peer}");

    #[derive(PartialEq)]
    enum Winner {
        Outer,
        Inner,
        Policy,
    }

    let (outcome, winner) = tokio::select! {
        result = &mut outer.exit => (join_outcome("outer masque tunnel", result), Winner::Outer),
        result = &mut inner_exit => (join_outcome("inner wireguard tunnel", result), Winner::Inner),
        never = listeners.serve(&inner_stack) => match never {},
        reason = exit_policy_guard(&inner_stack, &policy) => (Err(reason), Winner::Policy),
    };

    if winner != Winner::Outer {
        outer.exit.abort();
        let _ = (&mut outer.exit).await;
    }
    if winner != Winner::Inner {
        inner_exit.abort();
        let _ = (&mut inner_exit).await;
    }

    outcome
}

fn join_outcome(
    what: &str,
    result: std::result::Result<Result<()>, tokio::task::JoinError>,
) -> Result<()> {
    match result {
        Ok(Ok(())) => Err(AetherError::Other(format!("{what} stopped"))),
        Ok(Err(e)) => Err(e),
        Err(e) if e.is_cancelled() => Err(AetherError::Other(format!("{what} was cancelled"))),
        Err(e) => Err(AetherError::Other(format!("{what} panicked: {e}"))),
    }
}

async fn prompt_line(prompt: &str) -> Option<String> {
    use std::io::IsTerminal;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    if !std::io::stdin().is_terminal() {
        return None;
    }

    let mut stdout = tokio::io::stdout();
    let _ = stdout.write_all(prompt.as_bytes()).await;
    let _ = stdout.flush().await;

    let mut line = String::new();
    let mut reader = BufReader::new(tokio::io::stdin());
    match reader.read_line(&mut line).await {
        Ok(0) | Err(_) => None,
        Ok(_) => Some(line.trim().to_string()),
    }
}

const SCAN_MODE_PROMPT: &str = "\nScan mode:\n  [1] turbo     (fast, first hit)\n  [2] balanced  (default)\n  [3] thorough  (deep, best ping)\n  [4] verified  (measured edges only, never a guessed neighbour; on gool and\n                 mim it keeps the two hops in different ranges, which is what\n                 moves the exit address)\n  [5] ironclad  (real tunnel + real HTTP check per candidate, guaranteed working)\nChoose [1-5] (default 2): ";

const MIM_MANUAL_TIP: &str = "\n(tip: you can skip this scan and give the two masque hops yourself:\n        aether --mim --mim-outer <ip:port> --mim-inner <ip:port>\n      the port is required, and naming just the outer one lets aether pick\n      the inner edge for you)\n";

const WIW_MANUAL_TIP: &str = "\n(tip: you can skip this scan and give the two gool hops yourself:\n        aether --gool --wiw-outer <ip:port> --wiw-inner <ip:port>\n      the port is required, and naming just one of the two lets the scan\n      find the other)\n";

async fn select_scan_mode() -> prober::ScanMode {
    if let Ok(v) = std::env::var("AETHER_SCAN") {
        return prober::ScanMode::parse(&v);
    }

    let answer = prompt_line(SCAN_MODE_PROMPT).await;

    match answer.as_deref() {
        Some("1") => prober::ScanMode::Turbo,
        Some("3") => prober::ScanMode::Thorough,
        Some("4") => prober::ScanMode::Verified,
        Some("5") => prober::ScanMode::Ironclad,
        _ => prober::ScanMode::Balanced,
    }
}

async fn select_scan_mode_str(tip: &str) -> String {
    if let Ok(v) = std::env::var("AETHER_SCAN") {
        return v;
    }

    let answer = prompt_line(&format!("{tip}{SCAN_MODE_PROMPT}")).await;

    match answer.as_deref() {
        Some("1") => "turbo".to_string(),
        Some("3") => "thorough".to_string(),
        Some("4") => "verified".to_string(),
        Some("5") => "ironclad".to_string(),
        _ => "balanced".to_string(),
    }
}

async fn select_protocol(base: &str) -> Protocol {
    if let Ok(v) = std::env::var("AETHER_PROTOCOL") {
        return Protocol::parse(&v);
    }

    loop {
        let mut extra = String::new();
        let mut next = 5u8;
        let take = |line: &str, extra: &mut String, next: &mut u8| -> String {
            let key = next.to_string();
            extra.push_str(&format!("  [{key}] {line}\n"));
            *next += 1;
            key
        };

        let (tor_alone, tor_chain) = if cfg!(feature = "tor") {
            (
                take("Tor alone, with no warp under it", &mut extra, &mut next),
                take(
                    "Tor and warp chained, either way round",
                    &mut extra,
                    &mut next,
                ),
            )
        } else {
            (String::new(), String::new())
        };

        let psiphon_alone = take(
            "Psiphon alone, with no warp under it",
            &mut extra,
            &mut next,
        );
        let psiphon_chain = take(
            "Psiphon and warp chained, either way round",
            &mut extra,
            &mut next,
        );

        let last = next;
        let zero_trust_key = last.to_string();
        let zero_trust = match team_scope() {
            Some(team) => {
                format!("  [{last}] Zero Trust: signed in to {team}, pick another team\n")
            }
            None => {
                format!("  [{last}] Zero Trust: sign in to an organization (WARP for teams)\n")
            }
        };

        let answer = prompt_line(&format!(
            "\nProtocol:\n  [1] MASQUE (modern, QUIC/H3, default)\n  \
             [2] WireGuard (classic, faster)\n  [3] WARP-in-WARP / gool\n  \
             [4] MASQUE-in-MASQUE (two masque hops, for a different exit address)\n\
             {extra}{zero_trust}Choose [1-{last}] (default 1): "
        ))
        .await;

        let choice = answer.as_deref().unwrap_or_default();

        match choice {
            "2" => return Protocol::WireGuard,
            "3" => return select_gool().await,
            "4" => return Protocol::MasqueInMasque,
            _ if choice == zero_trust_key => {
                enrol_zero_trust(base).await;
                continue;
            }
            _ if !tor_alone.is_empty() && choice == tor_alone => {
                std::env::set_var("AETHER_TOR", "only");
                return Protocol::Masque;
            }
            _ if !tor_chain.is_empty() && choice == tor_chain => return select_tor_chain().await,
            _ if choice == psiphon_alone => {
                std::env::set_var("AETHER_PSIPHON", "only");
                return Protocol::Masque;
            }
            _ if choice == psiphon_chain => return select_psiphon_chain().await,
            _ => return Protocol::Masque,
        }
    }
}

async fn select_gool() -> Protocol {
    let answer = prompt_line(
        "\nWhich gool?\n  \
         [1] gool over masque: wireguard carried inside a masque tunnel, for a foreign exit \
         address (default)\n  \
         [2] classic gool: wireguard carried inside wireguard, a plain warp exit\n\
         Choose [1-2] (default 1): ",
    )
    .await;

    if matches!(answer.as_deref(), Some("2")) {
        std::env::set_var("AETHER_GOOL_MODE", "classic");
    }
    Protocol::WarpInWarp
}

async fn select_psiphon_chain() -> Protocol {
    let answer = prompt_line(
        "\nWhich way round?\n  \
         [1] psiphon inside warp: you, warp, psiphon, the internet. The exit is a psiphon \
         exit, and a network that blocks psiphon never sees it (default)\n  \
         [2] warp inside psiphon: you, psiphon, warp, the internet. The exit is a warp exit \
         reached from a psiphon exit, and your network never sees warp\n\
         Choose [1-2] (default 1): ",
    )
    .await;

    if matches!(answer.as_deref(), Some("2")) {
        std::env::set_var("AETHER_PSIPHON", "reverse");
        log::info!("[*] the tunnel will be dialled through psiphon, on the http/2 carrier");
        return Protocol::Masque;
    }

    std::env::set_var("AETHER_PSIPHON", "chain");
    log::info!("[*] psiphon will be carried inside the tunnel");
    select_chain_carrier("psiphon").await
}

async fn select_tor_chain() -> Protocol {
    let answer = prompt_line(
        "\nWhich way round?\n  \
         [1] tor inside warp: you, warp, tor, the internet. The exit is a tor exit, and a \
         network that blocks tor never sees it (default)\n  \
         [2] warp inside tor: you, tor, warp, the internet. The exit is a warp exit reached \
         from a tor exit, and your network never sees warp\n\
         Choose [1-2] (default 1): ",
    )
    .await;

    if matches!(answer.as_deref(), Some("2")) {
        std::env::set_var("AETHER_TOR", "reverse");
        log::info!("[*] the tunnel will be dialled through tor, on the http/2 carrier");
        return Protocol::Masque;
    }

    std::env::set_var("AETHER_TOR", "chain");
    log::info!("[*] tor will be carried inside the tunnel");
    select_chain_carrier("tor").await
}

async fn select_chain_carrier(what: &str) -> Protocol {
    let answer = prompt_line(&format!(
        "\nWhat carries {what}?\n  \
         [1] MASQUE, over quic/h3 or http/2, asked next (default)\n  \
         [2] WireGuard, warp over udp\n  \
         [3] WARP-in-WARP / gool\n  \
         [4] MASQUE-in-MASQUE\n\
         Choose [1-4] (default 1): "
    ))
    .await;

    match answer.as_deref() {
        Some("2") => Protocol::WireGuard,
        Some("3") => select_gool().await,
        Some("4") => Protocol::MasqueInMasque,
        _ => Protocol::Masque,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Protocol {
    Masque,
    WireGuard,
    WarpInWarp,
    MasqueInMasque,
}

impl Protocol {
    fn parse(s: &str) -> Protocol {
        match s.trim().to_lowercase().as_str() {
            "wg" | "wireguard" => Protocol::WireGuard,
            "gool" | "wiw" | "warp-in-warp" | "warpinwarp" => Protocol::WarpInWarp,
            "mim" | "m2" | "masque-in-masque" | "masqueinmasque" => Protocol::MasqueInMasque,
            _ => Protocol::Masque,
        }
    }

    fn label(&self) -> &'static str {
        match self {
            Protocol::Masque => "MASQUE",
            Protocol::WireGuard => "WireGuard",
            Protocol::WarpInWarp => "WARP-in-WARP (gool)",
            Protocol::MasqueInMasque => "MASQUE-in-MASQUE",
        }
    }
}

async fn select_masque_transport() {
    if std::env::var("AETHER_MASQUE_HTTP2").is_ok() || std::env::var("AETHER_PEER").is_ok() {
        return;
    }

    let answer = prompt_line(
        "\nMASQUE transport:\n  [1] HTTP/3 (QUIC)  (default; fastest handshake, best on healthy UDP networks)\n  [2] HTTP/2 (TCP)   (looks like ordinary HTTPS; use if UDP/QUIC is blocked or throttled)\nChoose [1-2] (default 1): ",
    )
    .await;

    if matches!(answer.as_deref(), Some("2")) {
        std::env::set_var("AETHER_MASQUE_HTTP2", "1");
    }
}

fn scan_settings_from_env() -> (String, prober::IpScan) {
    let mode = std::env::var("AETHER_SCAN").unwrap_or_default();
    let ip = std::env::var("AETHER_IP")
        .map(|value| prober::IpScan::parse(&value))
        .unwrap_or(prober::IpScan::V4);
    (mode, ip)
}

async fn select_ip_version() -> prober::IpScan {
    if let Ok(v) = std::env::var("AETHER_IP") {
        return prober::IpScan::parse(&v);
    }

    let answer = prompt_line(
        "\nIP version to scan:\n  [1] IPv4 (default)\n  [2] IPv6\n  [3] Both\nChoose [1-3] (default 1): ",
    )
    .await;

    match answer.as_deref() {
        Some("2") => prober::IpScan::V6,
        Some("3") => prober::IpScan::Both,
        _ => prober::IpScan::V4,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    #[test]
    fn the_tunnel_is_dialled_through_a_carrier_it_can_be_dialled_through() {
        let tor: SocketAddr = "127.0.0.1:9050".parse().unwrap();
        assert_eq!(
            carrier_upstream("tor", tor).unwrap(),
            "socks5://127.0.0.1:9050"
        );
        let psiphon: SocketAddr = "[::1]:1080".parse().unwrap();
        assert_eq!(
            carrier_upstream("psiphon", psiphon).unwrap(),
            "socks5://[::1]:1080"
        );
    }

    #[test]
    fn a_carrier_the_tunnel_cannot_be_dialled_through_ends_aether() {
        // Port 0, which an upstream setting cannot name: set aside, it would have the tunnel go
        // around the carrier.
        let unbound: SocketAddr = "127.0.0.1:0".parse().unwrap();
        let error = carrier_upstream("tor", unbound).unwrap_err().to_string();
        assert!(
            error.contains("aether ends rather than dial around tor")
                && !error.contains("(other: "),
            "{error}"
        );
    }

    #[tokio::test]
    async fn a_session_ends_when_tor_or_psiphon_inside_the_tunnel_ends() {
        // Nothing reports an end: the session alone decides, also when no sender is left.
        let (end, ended) = tokio::sync::mpsc::unbounded_channel::<AetherError>();
        drop(end);
        assert!(until_core_end(async { Ok(()) }, ended).await.is_ok());
        let (_end, ended) = tokio::sync::mpsc::unbounded_channel::<AetherError>();
        let failed = until_core_end(async { Err(AetherError::Cancelled) }, ended).await;
        assert!(matches!(failed, Err(AetherError::Cancelled)));

        // One inside ends while the tunnel runs on: the session ends with its error.
        let (end, ended) = tokio::sync::mpsc::unbounded_channel::<AetherError>();
        let report = tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
            let _ = end.send(AetherError::Other(
                "psiphon stopped before it was ready".into(),
            ));
        });
        let session = std::future::pending::<Result<()>>();
        let result = tokio::time::timeout(
            std::time::Duration::from_secs(2),
            until_core_end(session, ended),
        )
        .await
        .expect("the end of Tor or Psiphon inside ends the session");
        match result {
            Err(AetherError::Other(why)) => assert_eq!(why, "psiphon stopped before it was ready"),
            other => panic!("expected the chain's error, got {other:?}"),
        }
        report.await.unwrap();
    }

    /// The error records logged while the tests run, as (target, message): the lines the apps
    /// that run the core read.
    static LOGGED_ERRORS: std::sync::Mutex<Vec<(String, String)>> =
        std::sync::Mutex::new(Vec::new());

    struct ErrorCapture;

    impl log::Log for ErrorCapture {
        fn enabled(&self, metadata: &log::Metadata) -> bool {
            metadata.level() == log::Level::Error
        }

        fn log(&self, record: &log::Record) {
            if self.enabled(record.metadata()) {
                LOGGED_ERRORS
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .push((record.target().to_string(), record.args().to_string()));
            }
        }

        fn flush(&self) {}
    }

    /// Keeps the error records from here on, in [`LOGGED_ERRORS`]; no other test sets a logger.
    fn capture_errors() {
        static CAPTURE: ErrorCapture = ErrorCapture;
        static INSTALL: std::sync::Once = std::sync::Once::new();
        INSTALL.call_once(|| {
            log::set_logger(&CAPTURE).expect("no other logger is set in the tests");
            log::set_max_level(log::LevelFilter::Error);
        });
    }

    fn logged_errors() -> Vec<(String, String)> {
        LOGGED_ERRORS
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    }

    #[tokio::test]
    async fn the_end_of_tor_or_psiphon_inside_is_reported_however_it_ends() {
        capture_errors();
        let next = |name: &'static str, chain: tokio::task::JoinHandle<Result<()>>| async move {
            let (end, mut ended) = tokio::sync::mpsc::unbounded_channel::<AetherError>();
            let mut chains = TaskGuard::new();
            watch_chain(name, chain, end, &mut chains);
            let reported = tokio::time::timeout(std::time::Duration::from_secs(2), ended.recv())
                .await
                .expect("the end is reported")
                .expect("with its reason");
            drop(chains);
            reported
        };

        let failed = tokio::spawn(async {
            Err(AetherError::Other(
                "the tor socks5 listener cannot use 127.0.0.1:1".into(),
            ))
        });
        let reported = next("tor", failed).await;
        assert!(
            matches!(&reported, AetherError::Other(why) if why.starts_with("the tor socks5 listener")),
            "{reported:?}"
        );
        // The line the apps read: an error, under the target "aether", "[-] tor: " and the words.
        let line = format!("[-] tor: {reported}");
        assert!(
            logged_errors()
                .iter()
                .any(|(target, message)| target == "aether" && *message == line),
            "{line} is not among {:?}",
            logged_errors()
        );

        // One that returns without an error has ended all the same.
        let returned = tokio::spawn(async { Ok(()) });
        let reported = next("psiphon", returned).await;
        assert!(
            matches!(&reported, AetherError::Other(why) if why == "psiphon inside the tunnel stopped"),
            "{reported:?}"
        );

        // A panic, which tokio keeps in the task's handle.
        let panicked = tokio::spawn(async {
            if std::hint::black_box(true) {
                panic!("a bug in the chain");
            }
            Ok(())
        });
        let reported = next("tor", panicked).await;
        assert!(
            matches!(&reported, AetherError::Other(why) if why.starts_with("tor inside the tunnel failed")),
            "{reported:?}"
        );
    }

    #[tokio::test]
    async fn a_chain_ends_with_the_core_and_is_not_reported_as_ended_by_itself() {
        capture_errors();
        let (alive, gone) = tokio::sync::oneshot::channel::<()>();
        let chain = tokio::spawn(async move {
            let _alive = alive;
            std::future::pending::<Result<()>>().await
        });
        let (end, mut ended) = tokio::sync::mpsc::unbounded_channel::<AetherError>();
        let mut chains = TaskGuard::new();
        watch_chain("ended-with-the-core", chain, end, &mut chains);

        // The core ends.
        drop(chains);
        tokio::time::timeout(std::time::Duration::from_secs(2), gone)
            .await
            .expect("the chain is ended, letting go of what it held")
            .expect_err("dropped, not finished");
        let report = tokio::time::timeout(std::time::Duration::from_secs(2), ended.recv())
            .await
            .expect("the watch is ended as well");
        assert!(report.is_none(), "{report:?}");
        assert!(
            !logged_errors()
                .iter()
                .any(|(_, message)| message.contains("ended-with-the-core")),
            "{:?}",
            logged_errors()
        );
    }

    /// Whether the http proxy on `address` answers, here a request it cannot carry.
    async fn http_answers(address: SocketAddr) -> bool {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let Ok(mut sock) = tokio::net::TcpStream::connect(address).await else {
            return false;
        };
        if sock.write_all(b"NOT A REQUEST\r\n\r\n").await.is_err() {
            return false;
        }
        let mut head = [0u8; 12];
        let read = tokio::time::timeout(
            std::time::Duration::from_secs(2),
            sock.read_exact(&mut head),
        )
        .await;
        matches!(read, Ok(Ok(_))) && head.starts_with(b"HTTP/1.1 400")
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_listeners_answer_while_a_tunnel_serves_them_and_stay_held_between_tunnels() {
        let (_to_stack, stack_in) = tokio::sync::mpsc::channel(8);
        let (stack_out, _from_stack) = tokio::sync::mpsc::channel(8);
        let stack = netstack::spawn(
            "172.16.0.2",
            "2606:4700:110:8a36::1",
            1280,
            stack_in,
            stack_out,
        )
        .expect("a stack");
        let (broken, mut breaks) = tokio::sync::mpsc::unbounded_channel::<AetherError>();
        let listeners = Listeners {
            socks: socks::bind_listener("socks5", "127.0.0.1:0".parse().unwrap(), "--bind")
                .await
                .unwrap(),
            http: Some(
                socks::bind_listener("http proxy", "127.0.0.1:0".parse().unwrap(), "--http-proxy")
                    .await
                    .unwrap(),
            ),
            broken,
        };
        let socks_at = listeners.socks.local_addr().unwrap();
        let http_at = listeners.http.as_ref().unwrap().local_addr().unwrap();

        for tunnel in 1..=2 {
            // A tunnel serves them: both answer.
            tokio::select! {
                never = listeners.serve(&stack) => match never {},
                () = async {
                    assert!(
                        socks::answers(socks_at, std::time::Duration::from_secs(2)).await,
                        "tunnel {tunnel}: the socks5 server"
                    );
                    assert!(http_answers(http_at).await, "tunnel {tunnel}: the http proxy");
                } => {}
            }
            // That tunnel has ended: both stay held, so no other program binds them, and the
            // socks5 one says nothing until the next tunnel serves it.
            assert!(std::net::TcpListener::bind(socks_at).is_err());
            assert!(std::net::TcpListener::bind(http_at).is_err());
            assert!(!socks::answers(socks_at, std::time::Duration::from_millis(300)).await);
        }
        // Neither listener broke, so nothing would end the core.
        assert!(breaks.try_recv().is_err());
    }

    #[tokio::test]
    async fn tor_or_psiphon_alone_keeps_the_socks5_listener_and_frees_the_http_proxy() {
        let (broken, _breaks) = tokio::sync::mpsc::unbounded_channel::<AetherError>();
        let listeners = Listeners {
            socks: socks::bind_listener("socks5", "127.0.0.1:0".parse().unwrap(), "--bind")
                .await
                .unwrap(),
            http: Some(
                socks::bind_listener("http proxy", "127.0.0.1:0".parse().unwrap(), "--http-proxy")
                    .await
                    .unwrap(),
            ),
            broken,
        };
        let socks_at = listeners.socks.local_addr().unwrap();
        let http_at = listeners.http.as_ref().unwrap().local_addr().unwrap();

        let socks = listeners.into_socks();
        assert_eq!(socks.local_addr().unwrap(), socks_at);
        // Still held, as Tor or Psiphon alone serves it.
        assert!(std::net::TcpListener::bind(socks_at).is_err());
        // No alone mode serves the core's own http proxy: its port is free again at once.
        assert!(std::net::TcpListener::bind(http_at).is_ok());
    }

    fn env(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let values: BTreeMap<String, String> = pairs
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect();
        move |key: &str| values.get(key).cloned()
    }

    #[test]
    fn tor_and_psiphon_around_the_tunnel_refuse_only_what_needs_udp() {
        assert!(needs_udp_to_warp(Protocol::WireGuard, false));
        assert!(needs_udp_to_warp(Protocol::WarpInWarp, true));
        // Gool over masque reaches WARP by MASQUE alone; its WireGuard rides inside that tunnel.
        assert!(!needs_udp_to_warp(Protocol::WarpInWarp, false));
        assert!(!needs_udp_to_warp(Protocol::Masque, true));
        assert!(!needs_udp_to_warp(Protocol::MasqueInMasque, false));
    }

    #[test]
    fn a_gool_device_is_enabled_again_unless_the_api_refused_it() {
        assert!(worth_enabling_again(&AetherError::Api(
            "enabling warp: timed out".into()
        )));
        assert!(!worth_enabling_again(&AetherError::IdentityRefused(
            "enabling warp: 401".into()
        )));
    }

    /// How long a tunnel lasted that ended soon after it started.
    const SOON: std::time::Duration = std::time::Duration::from_secs(10);

    #[test]
    fn a_masque_gateway_is_left_once_its_tunnel_ended_twice_in_a_row() {
        let gateway: SocketAddr = "162.159.198.1:443".parse().unwrap();
        let now = Instant::now();
        let rest = std::time::Duration::from_secs(300);
        let mut strikes = GatewayStrikes::default();

        strikes.using(gateway);
        strikes.ended(SOON);
        assert_eq!(strikes.give_up(now, rest), None);
        // The reconnect goes back to it, and it ends again.
        strikes.using(gateway);
        strikes.ended(SOON);
        assert_eq!(strikes.give_up(now, rest), Some(gateway));
        // Out of the scans on any port until it has rested; the count starts over.
        assert!(strikes.left_out(now).contains(&gateway.ip()));
        assert_eq!(strikes.give_up(now, rest), None);
        assert!(strikes.left_out(now + rest).is_empty());
    }

    #[test]
    fn another_masque_gateway_starts_with_no_strikes() {
        let first: SocketAddr = "162.159.198.1:443".parse().unwrap();
        let second: SocketAddr = "162.159.198.2:443".parse().unwrap();
        let now = Instant::now();
        let rest = std::time::Duration::from_secs(300);
        let mut strikes = GatewayStrikes::default();

        strikes.using(first);
        strikes.ended(SOON);
        strikes.using(second);
        strikes.ended(SOON);
        assert_eq!(strikes.give_up(now, rest), None);
        assert!(strikes.left_out(now).is_empty());
        // A tunnel that has not ended yet counts for nothing either.
        strikes.using(second);
        assert_eq!(strikes.give_up(now, rest), None);
    }

    #[test]
    fn a_tunnel_that_stayed_up_clears_the_ends_before_it() {
        let gateway: SocketAddr = "162.159.198.1:443".parse().unwrap();
        let now = Instant::now();
        let rest = std::time::Duration::from_secs(300);
        let mut strikes = GatewayStrikes::default();

        // Hours on the gateway, a network change, hours again, another one: it is not left.
        strikes.using(gateway);
        strikes.ended(TUNNEL_STAYED_UP * 36);
        strikes.using(gateway);
        strikes.ended(TUNNEL_STAYED_UP * 24);
        assert_eq!(strikes.give_up(now, rest), None);
        // One that ends soon, then one that stays up: still not.
        strikes.using(gateway);
        strikes.ended(SOON);
        strikes.using(gateway);
        strikes.ended(TUNNEL_STAYED_UP);
        assert_eq!(strikes.give_up(now, rest), None);
        // Two that end soon, one after the other, are what leaves it.
        strikes.using(gateway);
        strikes.ended(SOON);
        assert_eq!(strikes.give_up(now, rest), None);
        strikes.using(gateway);
        strikes.ended(SOON);
        assert_eq!(strikes.give_up(now, rest), Some(gateway));
    }

    #[test]
    fn only_a_tunnel_that_ended_soon_counts_against_its_gateway_or_endpoint() {
        // The WireGuard, classic gool and masque-in-masque runs count their ends with it too.
        let almost = TUNNEL_STAYED_UP - std::time::Duration::from_secs(1);
        assert_eq!(count_tunnel_end(0, std::time::Duration::ZERO), 1);
        assert_eq!(count_tunnel_end(1, almost), 2);
        assert_eq!(count_tunnel_end(1, TUNNEL_STAYED_UP), 0);
        assert_eq!(count_tunnel_end(7, TUNNEL_STAYED_UP * 2), 0);
        assert_eq!(count_tunnel_end(u32::MAX, SOON), u32::MAX);
    }

    #[test]
    fn register_names_the_identities_of_one_protocol_or_all_five() {
        let all = RegisterSet::parse("all").expect("all");
        assert!(all.wireguard && all.wireguard_inner && all.masque && all.masque_inner && all.gool);

        let masque = RegisterSet::parse(" MASQUE ").expect("masque");
        assert!(masque.masque && !masque.masque_inner && !masque.gool);
        assert!(!masque.wireguard && !masque.wireguard_inner);

        let wireguard = RegisterSet::parse("wg").expect("wg");
        assert!(wireguard.wireguard && !wireguard.wireguard_inner);
        assert!(!wireguard.masque && !wireguard.masque_inner && !wireguard.gool);

        // What --gool runs: masque, and the wireguard identity it carries inside.
        for word in ["gool", "GOOL", "wg-over-masque"] {
            let gool = RegisterSet::parse(word).expect(word);
            assert!(gool.masque && gool.gool, "{word}");
            assert!(
                !gool.masque_inner && !gool.wireguard && !gool.wireguard_inner,
                "{word}"
            );
        }

        // What --gool-classic runs: both wireguard hops.
        for word in ["gool-classic", "wiw", "warp-in-warp"] {
            let classic = RegisterSet::parse(word).expect(word);
            assert!(classic.wireguard && classic.wireguard_inner, "{word}");
            assert!(
                !classic.masque && !classic.masque_inner && !classic.gool,
                "{word}"
            );
        }

        let mim = RegisterSet::parse("mim").expect("mim");
        assert!(mim.masque && mim.masque_inner && !mim.gool);
        assert!(!mim.wireguard && !mim.wireguard_inner);

        assert!(RegisterSet::parse("everything").is_err());
    }

    #[test]
    fn gool_keeps_its_wireguard_identity_beside_the_masque_one() {
        assert_eq!(
            gool_identity_path("/data/files/aether/aether-masque.toml"),
            "/data/files/aether/aether-masque-gool.toml"
        );
        assert_eq!(
            gool_identity_path(r"C:\keys\aether-masque.toml"),
            r"C:\keys\aether-masque-gool.toml"
        );
    }

    #[test]
    fn masque_in_masque_is_selected_by_name() {
        for written in ["mim", "m2", "masque-in-masque", "MIM", "MasqueInMasque"] {
            assert_eq!(
                Protocol::parse(written),
                Protocol::MasqueInMasque,
                "{written}"
            );
        }
    }

    #[test]
    fn the_masque_hops_can_be_named_by_hand() {
        let chosen = mim_endpoints_of(&env(&[
            ("AETHER_MIM_OUTER_PEER", "162.159.192.1:443"),
            ("AETHER_MIM_INNER_PEER", "188.114.96.1:443"),
        ]))
        .expect("both hops");

        assert_eq!(chosen.outer, Some("162.159.192.1:443".parse().unwrap()));
        assert_eq!(chosen.inner, Some("188.114.96.1:443".parse().unwrap()));
    }

    #[test]
    fn the_same_masque_edge_cannot_serve_as_both_hops() {
        assert!(mim_endpoints_of(&env(&[(
            "AETHER_MIM_PEERS",
            "162.159.192.1:443,162.159.192.1:8443"
        )]))
        .is_err());
    }

    #[test]
    fn an_inner_quic_datagram_fits_inside_the_outer_link() {
        let inner: SocketAddr = "162.159.192.1:443".parse().unwrap();
        let (datagram, mtu) = mim_inner_budget(MASQUE_MTU, inner, false);

        assert!(
            datagram + 28 <= MASQUE_MTU,
            "a {datagram} byte datagram plus headers must fit {MASQUE_MTU}"
        );
        assert!(
            datagram >= quic::MIN_DATAGRAM_SIZE,
            "quic needs at least 1200"
        );
        assert!(
            mtu < datagram,
            "the inner link has to leave room for quic itself"
        );
    }

    #[test]
    fn an_ipv6_inner_hop_leaves_room_for_the_bigger_header() {
        let v4: SocketAddr = "162.159.192.1:443".parse().unwrap();
        let v6: SocketAddr = "[2606:4700:d0::a29f:c001]:443".parse().unwrap();

        let (over_v4, _) = mim_inner_budget(MASQUE_MTU, v4, false);
        let (over_v6, _) = mim_inner_budget(MASQUE_MTU, v6, false);
        assert!(
            over_v6 < over_v4,
            "{over_v6} should leave more room than {over_v4}"
        );
        assert!(over_v6 + 48 <= MASQUE_MTU);
    }

    #[test]
    fn an_http2_pair_keeps_the_full_datagram_budget() {
        let inner: SocketAddr = "162.159.192.1:443".parse().unwrap();
        let (datagram, mtu) = mim_inner_budget(H2_TUNNEL_MTU, inner, true);

        assert_eq!(datagram, quic::MAX_DATAGRAM_SIZE);
        assert!(
            mtu < H2_TUNNEL_MTU,
            "the inner link stays under the outer one"
        );
    }

    /// The inner hop asks where its device lives before it guesses.
    ///
    /// Reproduces the reported failure: the outer hop validated end to end and
    /// then six inner attempts died identically with TLS alert 40, because
    /// every one of them was a guessed neighbour of the outer edge rather than
    /// the address Cloudflare had assigned to the inner device.
    /// The inner hop does not obfuscate, because warp-in-warp does not either.
    ///
    /// Its wire is the outer tunnel: already encrypted, already carrying
    /// opaque traffic, with nothing on it that could be fooled. Warp-in-warp
    /// reached that conclusion and turns its inner obfuscation off; the nested
    /// MASQUE path was sending two junk datagrams and a fake first flight to a
    /// real Cloudflare gateway ahead of every inner handshake.
    /// Every hop can hold its own first packet, whatever it is nested under.
    ///
    /// quiche pads a client Initial only as far as the buffer it is handed, so
    /// a hop sized from a tunnel above it can send a short first flight and
    /// nothing says so. The inner hop of a nested pair is exactly that case:
    /// its budget is the outer link minus headers, and on an IPv6 edge under a
    /// 1280-byte link that is 1232 -- ten bytes under what an Initial needs.
    /// The inner hop gets both framings; the outer keeps the one that worked.
    ///
    /// Which framing gets through is the thing that differs between networks,
    /// and the nested path used to make it one decision for both hops -- so an
    /// inner edge that refused a QUIC handshake ended the whole attempt without
    /// TCP ever being tried. The outer hop is not retried because it has
    /// already reached an edge: its framing is known to work here.
    #[test]
    fn the_inner_hop_tries_the_other_framing_before_giving_up() {
        assert_eq!(
            inner_framings(false),
            [false, true],
            "quic profile: quic, then tcp"
        );
        assert_eq!(
            inner_framings(true),
            [true, false],
            "tcp profile: tcp, then quic"
        );
        for profile in [true, false] {
            let order = inner_framings(profile);
            assert_eq!(order[0], profile, "the profile's own choice goes first");
            assert_ne!(order[0], order[1], "and the other one is actually tried");
        }
    }

    /// A hop's framing is its own, not the process's.
    #[test]
    fn a_nested_pair_can_carry_a_different_framing_on_each_hop() {
        let peer: SocketAddr = "162.159.192.2:443".parse().unwrap();
        assert!(MasqueShape::mim_inner(1280, peer, true).h2);
        assert!(!MasqueShape::mim_inner(1280, peer, false).h2);
    }

    #[test]
    fn every_hop_can_send_a_whole_client_initial() {
        for (outer_mtu, peer) in [
            (1280usize, "162.159.192.2:443"),
            (1280, "[2606:4700:d0::a29f:c002]:443"),
            (1200, "162.159.192.2:443"),
            (900, "162.159.192.2:443"),
        ] {
            let inner = MasqueShape::mim_inner(outer_mtu, peer.parse().unwrap(), false);
            let cfg = quic::TunnelConfig {
                peer: peer.parse().unwrap(),
                sni: String::new(),
                authority: String::new(),
                path: String::new(),
                cert_pem: Vec::new(),
                key_pem: Vec::new(),
                ech_config_list: None,
                noize: noize::NoizeConfig::off(),
                local_ipv4: std::net::Ipv4Addr::LOCALHOST,
                quiet: true,
                max_datagram: inner.datagram,
                version_bait: inner.version_bait,
            };
            assert!(
                cfg.datagram_budget() >= quic::MIN_INITIAL_BUDGET,
                "an outer link of {outer_mtu} to {peer} left {} for the inner handshake",
                cfg.datagram_budget()
            );
        }
    }

    #[test]
    fn only_the_hop_that_touches_the_network_is_obfuscated() {
        let single = MasqueShape::single();
        assert!(single.obfuscate, "a single hop is the one being watched");
        assert!(single.version_bait);

        let inner = MasqueShape::mim_inner(1280, "162.159.192.2:443".parse().unwrap(), false);
        assert!(!inner.obfuscate, "the inner hop is already inside cover");
        assert!(
            !inner.version_bait,
            "and does not repeat the bait either -- the same argument"
        );
    }

    #[test]
    fn the_inner_hop_tries_the_endpoint_cloudflare_assigned_first() {
        let outer: SocketAddr = "162.159.198.104:443".parse().unwrap();
        let assigned: SocketAddr = "162.159.192.2:443".parse().unwrap();
        let derived = inner_masque_candidates(outer, MIM_INNER_TRIES, false);

        let ordered = order_inner_candidates(vec![assigned], outer, derived.clone());

        let first = ordered.first().expect("a candidate");
        assert_eq!(first.addr, assigned, "the assigned endpoint is tried first");
        assert_eq!(
            first.origin,
            EdgeOrigin::Assigned,
            "and the log says where it came from, so a failing report can be read"
        );
        assert_eq!(
            ordered.len(),
            1 + derived.len(),
            "the guesses remain behind it"
        );
        assert!(
            ordered[1..]
                .iter()
                .all(|edge| edge.origin == EdgeOrigin::Derived),
            "a guess is never reported as an assignment"
        );
    }

    /// Two answers about one device, and the outer edge, are not candidates.
    #[test]
    fn the_inner_hop_does_not_try_the_same_edge_twice_or_the_outer_one() {
        let outer: SocketAddr = "162.159.198.104:443".parse().unwrap();
        let moved: SocketAddr = "162.159.192.2:443".parse().unwrap();

        // `assigned_masque_peers` returns the stored endpoint and the current
        // one, which are often the same address; and Cloudflare can perfectly
        // well have assigned the inner device to the edge the outer hop is
        // already using, which is not a second hop.
        let ordered = order_inner_candidates(
            vec![moved, moved, outer],
            outer,
            vec![moved, "162.159.198.7:443".parse().unwrap()],
        );

        assert_eq!(
            ordered.iter().map(|edge| edge.addr).collect::<Vec<_>>(),
            vec![moved, "162.159.198.7:443".parse().unwrap()],
            "each edge appears once, and never the outer one"
        );
    }

    #[test]
    fn the_inner_edges_come_from_the_range_that_answered() {
        let outer: SocketAddr = "162.159.198.104:8443".parse().unwrap();
        let candidates = inner_masque_candidates(outer, MIM_INNER_TRIES, false);

        assert_eq!(candidates.len(), MIM_INNER_TRIES);
        for candidate in &candidates {
            assert_ne!(
                candidate.ip(),
                outer.ip(),
                "the inner hop needs its own edge"
            );
            assert_eq!(candidate.port(), MASQUE_INNER_PORT);
            match candidate.ip() {
                IpAddr::V4(v4) => {
                    let outer_octets = match outer.ip() {
                        IpAddr::V4(ip) => ip.octets(),
                        IpAddr::V6(_) => unreachable!(),
                    };
                    assert_eq!(
                        v4.octets()[..3],
                        outer_octets[..3],
                        "same /24 as the edge that worked"
                    );
                }
                IpAddr::V6(_) => unreachable!(),
            }
        }

        let all: HashSet<SocketAddr> = candidates.iter().copied().collect();
        assert_eq!(all.len(), candidates.len(), "candidates must be distinct");
    }

    #[test]
    fn the_verified_ladder_leaves_the_outer_range_first() {
        let outer: SocketAddr = "162.159.198.1:443".parse().unwrap();
        let candidates = inner_masque_candidates(outer, MIM_INNER_TRIES, true);

        assert!(!candidates.is_empty());
        assert!(candidates.iter().all(|peer| peer.ip() != outer.ip()));

        let first = candidates[0];
        assert_ne!(
            edge_network(first.ip()),
            edge_network(outer.ip()),
            "the first inner candidate must leave the outer range"
        );

        let verified: HashSet<IpAddr> = prober::MASQUE_VERIFIED_GATEWAYS
            .iter()
            .filter_map(|entry| entry.parse().ok())
            .collect();
        let measured = candidates
            .iter()
            .take_while(|peer| verified.contains(&peer.ip()))
            .count();
        assert!(measured > 0, "the measured edges must come first");
        assert!(
            candidates.len() > measured,
            "a guessed neighbour must stay on the end: a verified address is only \
             verified for the path it was measured on"
        );
    }

    #[test]
    fn a_verified_ladder_is_not_used_for_an_ipv6_outer() {
        let outer: SocketAddr = "[2606:4700:d0::a29f:c601]:443".parse().unwrap();
        let candidates = inner_masque_candidates(outer, 4, true);

        assert!(candidates.iter().all(|candidate| candidate.is_ipv6()));
    }

    #[test]
    fn spreading_hops_puts_a_different_range_second() {
        let mut found: Vec<SocketAddr> = vec![
            "162.159.192.1:2408".parse().unwrap(),
            "162.159.192.9:2408".parse().unwrap(),
            "188.114.96.1:2408".parse().unwrap(),
        ];
        spread_hops(&mut found);

        assert_eq!(edge_network(found[0].ip()), Some([162, 159, 192]));
        assert_ne!(
            edge_network(found[1].ip()),
            edge_network(found[0].ip()),
            "the second hop must sit in another range"
        );
    }

    #[test]
    fn an_ipv6_outer_edge_yields_ipv6_inner_candidates() {
        let outer: SocketAddr = "[2606:4700:d0::a29f:c601]:443".parse().unwrap();
        let candidates = inner_masque_candidates(outer, 4, false);

        assert_eq!(candidates.len(), 4);
        assert!(candidates.iter().all(|candidate| candidate.is_ipv6()));
        assert!(candidates
            .iter()
            .all(|candidate| candidate.ip() != outer.ip()));
    }

    #[test]
    fn an_address_and_a_port_are_read_together() {
        let peer = parse_endpoint("162.159.192.1:894").expect("address and port");
        assert_eq!(peer, "162.159.192.1:894".parse().unwrap());
    }

    #[test]
    fn an_address_without_a_port_is_refused_rather_than_guessed_at() {
        let message = parse_endpoint("162.159.192.1")
            .expect_err("the port carries too much meaning to be assumed")
            .to_string();
        assert!(
            message.contains("162.159.192.1:2408"),
            "the error should spell out the shape wanted, got: {message}"
        );
    }

    #[test]
    fn an_ipv6_address_without_a_port_is_refused_the_same_way() {
        for written in ["2606:4700:d0::a29f:c001", "[2606:4700:d0::a29f:c001]"] {
            let message = parse_endpoint(written).expect_err(written).to_string();
            assert!(
                message.contains("[2606:4700:d0::a29f:c001]:2408"),
                "the error should bracket the address it suggests, got: {message}"
            );
        }
    }

    #[test]
    fn surrounding_whitespace_is_forgiven() {
        let peer = parse_endpoint("  162.159.192.1:2408  ").expect("padded");
        assert_eq!(peer, "162.159.192.1:2408".parse().unwrap());
    }

    #[test]
    fn ipv6_is_read_when_it_is_bracketed_and_carries_its_port() {
        let peer = parse_endpoint("[2606:4700:d0::a29f:c001]:2408").expect("ipv6 endpoint");
        assert_eq!(peer, "[2606:4700:d0::a29f:c001]:2408".parse().unwrap());
    }

    #[test]
    fn nonsense_is_reported_with_an_example_to_copy() {
        let message = parse_endpoint("not-an-address")
            .expect_err("a hostname is not an address")
            .to_string();
        assert!(
            message.contains("162.159.192.1:2408"),
            "the error should show the shape expected, got: {message}"
        );
    }

    #[test]
    fn an_impossible_port_is_rejected_rather_than_wrapped() {
        assert!(parse_endpoint("162.159.192.1:70000").is_err());
    }

    #[test]
    fn a_pair_may_be_written_with_a_comma_or_a_space() {
        for written in [
            "162.159.192.1:2408,162.159.195.1:500",
            "162.159.192.1:2408, 162.159.195.1:500",
            "162.159.192.1:2408 162.159.195.1:500",
        ] {
            let peers = parse_endpoint_list(written).expect(written);
            assert_eq!(peers.len(), 2, "{written} names two hops");
            assert_eq!(peers[0], "162.159.192.1:2408".parse().unwrap());
            assert_eq!(peers[1], "162.159.195.1:500".parse().unwrap());
        }
    }

    #[test]
    fn a_third_address_is_refused_because_there_are_only_two_hops() {
        let outcome = parse_endpoint_list("1.1.1.1:2408,2.2.2.2:2408,3.3.3.3:2408");
        assert!(outcome.is_err());
    }

    #[test]
    fn the_pair_setting_fills_the_outer_hop_first() {
        let chosen = wiw_endpoints_of(&env(&[(
            "AETHER_WIW_PEERS",
            "162.159.192.1:2408,162.159.195.1:500",
        )]))
        .expect("a usable pair");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, Some("162.159.195.1:500".parse().unwrap()));
    }

    #[test]
    fn one_address_pins_the_outer_hop_and_leaves_the_inner_one_to_the_scan() {
        let chosen = wiw_endpoints_of(&env(&[("AETHER_WIW_PEERS", "162.159.192.1:894")]))
            .expect("a single hop");
        assert_eq!(chosen.outer, Some("162.159.192.1:894".parse().unwrap()));
        assert_eq!(chosen.inner, None);
    }

    #[test]
    fn a_hop_named_on_its_own_wins_over_the_pair() {
        let chosen = wiw_endpoints_of(&env(&[
            ("AETHER_WIW_PEERS", "162.159.192.1:2408,162.159.195.1:500"),
            ("AETHER_WIW_INNER_PEER", "188.114.96.1:1701"),
        ]))
        .expect("the inner override");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, Some("188.114.96.1:1701".parse().unwrap()));
    }

    #[test]
    fn only_the_inner_hop_may_be_pinned() {
        let chosen = wiw_endpoints_of(&env(&[("AETHER_WIW_INNER_PEER", "188.114.96.1:2408")]))
            .expect("the inner hop");
        assert_eq!(chosen.outer, None);
        assert_eq!(chosen.inner, Some("188.114.96.1:2408".parse().unwrap()));
    }

    #[test]
    fn one_address_cannot_serve_as_both_hops() {
        let outcome = wiw_endpoints_of(&env(&[
            ("AETHER_WIW_OUTER_PEER", "162.159.192.1:2408"),
            ("AETHER_WIW_INNER_PEER", "162.159.192.1:894"),
        ]));

        let message = outcome
            .expect_err("the same edge twice is not warp-in-warp")
            .to_string();
        assert!(
            message.contains("162.159.192.1"),
            "the error should name the address, got: {message}"
        );
    }

    #[test]
    fn asking_for_a_scan_leaves_both_hops_open() {
        for written in ["auto", "scan", "off", "none", "0"] {
            let lookup = env(&[("AETHER_WIW_PEERS", written)]);
            assert!(wiw_scan_requested(&lookup), "{written} means scan");
            assert!(
                wiw_endpoints_of(&lookup).expect(written).is_empty(),
                "{written} should pin nothing"
            );
        }
    }

    #[test]
    fn nothing_set_pins_nothing() {
        assert!(wiw_endpoints_of(&env(&[])).expect("empty").is_empty());
    }

    #[test]
    fn a_malformed_address_is_an_error_rather_than_a_silent_scan() {
        assert!(wiw_endpoints_of(&env(&[("AETHER_WIW_OUTER_PEER", "162.159.192")])).is_err());
    }

    #[test]
    fn a_hop_set_without_a_port_is_an_error_rather_than_a_silent_scan() {
        assert!(wiw_endpoints_of(&env(&[("AETHER_WIW_OUTER_PEER", "162.159.192.1")])).is_err());
    }

    #[test]
    fn the_older_wg_peer_setting_still_names_the_outer_hop() {
        let chosen = wiw_endpoints_with_fallback(&env(&[("AETHER_WG_PEER", "162.159.192.1:2408")]))
            .expect("the documented --gool --wg-peer pairing");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, None);
    }

    #[test]
    fn the_generic_peer_setting_is_the_last_fallback() {
        let chosen = wiw_endpoints_with_fallback(&env(&[("AETHER_PEER", "162.159.192.1:2408")]))
            .expect("--peer names the outer hop too");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
    }

    #[test]
    fn a_hop_chosen_for_warp_in_warp_beats_the_older_setting() {
        let chosen = wiw_endpoints_with_fallback(&env(&[
            ("AETHER_WG_PEER", "162.159.192.1:2408"),
            ("AETHER_WIW_OUTER_PEER", "188.114.96.1:2408"),
        ]))
        .expect("the warp-in-warp setting is the specific one");
        assert_eq!(chosen.outer, Some("188.114.96.1:2408".parse().unwrap()));
    }

    #[test]
    fn the_older_setting_may_carry_both_hops_at_once() {
        let chosen = wiw_endpoints_with_fallback(&env(&[(
            "AETHER_WG_PEER",
            "162.159.192.1:2408,188.114.96.1:2408",
        )]))
        .expect("a pair");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, Some("188.114.96.1:2408".parse().unwrap()));
    }

    #[test]
    fn the_older_setting_does_not_overwrite_a_pinned_inner_hop() {
        let chosen = wiw_endpoints_with_fallback(&env(&[
            ("AETHER_WG_PEER", "162.159.192.1:2408,188.114.96.1:2408"),
            ("AETHER_WIW_INNER_PEER", "162.159.195.1:2408"),
        ]))
        .expect("the inner hop stays where it was put");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, Some("162.159.195.1:2408".parse().unwrap()));
    }

    /// What reached a MASQUE server on this machine first: the server name of the ClientHello,
    /// and the :authority of the request.
    type Reached = tokio::task::JoinHandle<(Option<String>, String)>;

    /// A MASQUE server over HTTP/2 on this machine, see `Reached`.
    async fn h2_masque_server() -> (SocketAddr, Reached) {
        let (address, listener, acceptor) = crate::https::test_server(b"\x02h2").await;
        let reached = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.expect("a connection");
            let tls = tokio_boring::accept(&acceptor, tcp)
                .await
                .expect("a handshake");
            let name = tls
                .ssl()
                .servername(boring::ssl::NameType::HOST_NAME)
                .map(str::to_string);
            let mut connection = h2::server::handshake(tls).await.expect("h2");
            let (request, _answer) = connection
                .accept()
                .await
                .expect("a request")
                .expect("a whole request");
            let authority = request.uri().authority().expect("an authority").to_string();
            (name, authority)
        });
        (address, reached)
    }

    /// A MASQUE server over HTTP/3 on this machine, see `Reached`. It passes over what comes
    /// before the first QUIC v1 Initial: the version bait and the junk of the obfuscation.
    async fn h3_masque_server() -> (SocketAddr, Reached) {
        use quiche::h3::NameValue;

        let socket = tokio::net::UdpSocket::bind("127.0.0.1:0")
            .await
            .expect("a port");
        let address = socket.local_addr().expect("its address");
        let reached = tokio::spawn(async move {
            let pair = account::generate_masque_keypair().expect("a key and a certificate");
            let mut tls =
                boring::ssl::SslContextBuilder::new(boring::ssl::SslMethod::tls()).expect("tls");
            tls.set_certificate(
                &boring::x509::X509::from_pem(&pair.cert_pem).expect("a certificate"),
            )
            .expect("the certificate");
            tls.set_private_key(
                &boring::pkey::PKey::private_key_from_pem(&pair.key_pem).expect("a key"),
            )
            .expect("the key");
            let mut config =
                quiche::Config::with_boring_ssl_ctx_builder(quiche::PROTOCOL_VERSION, tls)
                    .expect("a configuration");
            config
                .set_application_protos(&[consts::ALPN_H3])
                .expect("h3");
            config.set_initial_max_data(1 << 20);
            config.set_initial_max_stream_data_bidi_remote(1 << 20);
            config.set_initial_max_stream_data_uni(1 << 20);
            config.set_initial_max_streams_bidi(8);
            config.set_initial_max_streams_uni(8);
            config.enable_dgram(true, 64, 64);
            let mut h3_config = quiche::h3::Config::new().expect("h3");
            h3_config.enable_extended_connect(true);

            let mut conn: Option<quiche::Connection> = None;
            let mut h3: Option<quiche::h3::Connection> = None;
            let mut packet = vec![0u8; 65535];
            let mut out = vec![0u8; 1500];
            loop {
                let (read, from) = socket.recv_from(&mut packet).await.expect("a packet");
                if conn.is_none() {
                    // A long header of the Initial type, and version 1.
                    if read <= 5 || packet[0] & 0xf0 != 0xc0 || packet[1..5] != [0, 0, 0, 1] {
                        continue;
                    }
                    let scid = [7u8; 16];
                    let scid = quiche::ConnectionId::from_ref(&scid);
                    conn = Some(
                        quiche::accept(&scid, None, address, from, &mut config)
                            .expect("a connection"),
                    );
                }
                let conn = conn.as_mut().expect("the connection");
                let _ = conn.recv(&mut packet[..read], quiche::RecvInfo { from, to: address });
                if conn.is_established() && h3.is_none() {
                    h3 =
                        Some(quiche::h3::Connection::with_transport(conn, &h3_config).expect("h3"));
                }
                if let Some(h3) = h3.as_mut() {
                    while let Ok((_, event)) = h3.poll(conn) {
                        if let quiche::h3::Event::Headers { list, .. } = event {
                            let authority = list
                                .iter()
                                .find(|header| header.name() == b":authority")
                                .map(|header| String::from_utf8_lossy(header.value()).into_owned())
                                .expect("an authority");
                            return (conn.server_name().map(str::to_string), authority);
                        }
                    }
                }
                while let Ok((written, sent)) = conn.send(&mut out) {
                    socket
                        .send_to(&out[..written], sent.to)
                        .await
                        .expect("a packet sent");
                }
            }
        });
        (address, reached)
    }

    #[tokio::test]
    async fn the_masque_handshakes_send_the_masque_sni_and_keep_the_authority() {
        let _setting = upstream::hold_setting().await;
        // The handshakes take --masque-sni, the carrier and the fingerprint from the options.
        let _options = tls::hold_options().await;
        let identity = account::handshake_identity();
        let startup = std::time::Duration::from_secs(10);

        // Without --masque-sni, www.cloudflare.com; with it, the name it gives.
        for (option, sni) in [
            (None, "www.cloudflare.com"),
            (Some(consts::CONNECT_SNI), consts::CONNECT_SNI),
        ] {
            match option {
                Some(name) => std::env::set_var("AETHER_MASQUE_SNI", name),
                None => std::env::remove_var("AETHER_MASQUE_SNI"),
            }
            for h2 in [false, true] {
                if h2 {
                    std::env::set_var("AETHER_MASQUE_HTTP2", "1");
                } else {
                    std::env::remove_var("AETHER_MASQUE_HTTP2");
                }
                // The check of a gateway, and the tunnel.
                for tunnel in [false, true] {
                    let case = format!("--masque-sni {option:?}, h2 {h2}, tunnel {tunnel}");
                    let (address, reached) = if h2 {
                        h2_masque_server().await
                    } else {
                        h3_masque_server().await
                    };
                    let client = async {
                        if tunnel {
                            let mtu = if h2 { H2_TUNNEL_MTU } else { MASQUE_MTU };
                            let datagram = quic::MAX_DATAGRAM_SIZE;
                            let hop = establish_masque(
                                &identity, address, None, h2, mtu, datagram, false, true, startup, "test",
                            );
                            let _ = hop.await;
                        } else {
                            quick_verify_masque_peer(&identity, address, None).await;
                        }
                    };
                    let (name, authority) = tokio::select! {
                        biased;
                        reached = reached => reached.expect("the server"),
                        () = client => panic!("{case}: the request did not reach the server"),
                    };
                    assert_eq!(name.as_deref(), Some(sni), "{case}");
                    let host = if h2 {
                        "cloudflareaccess.com:443"
                    } else {
                        "cloudflareaccess.com"
                    };
                    assert_eq!(authority, host, "{case}");
                }
            }
        }
    }

    /// The ClientHello a QUIC server on this machine reads first, as it reads it. It passes
    /// over what comes before the first QUIC v1 Initial, as `h3_masque_server` does.
    async fn h3_hello_server() -> (
        SocketAddr,
        tokio::task::JoinHandle<tls::client_hello::ClientHello>,
    ) {
        let socket = tokio::net::UdpSocket::bind("127.0.0.1:0")
            .await
            .expect("a port");
        let address = socket.local_addr().expect("its address");
        let seen = std::sync::Arc::new(std::sync::Mutex::new(
            None::<tls::client_hello::ClientHello>,
        ));
        let caught = seen.clone();
        let hello = tokio::spawn(async move {
            let pair = account::generate_masque_keypair().expect("a key and a certificate");
            let mut tls =
                boring::ssl::SslContextBuilder::new(boring::ssl::SslMethod::tls()).expect("tls");
            tls.set_certificate(
                &boring::x509::X509::from_pem(&pair.cert_pem).expect("a certificate"),
            )
            .expect("the certificate");
            tls.set_private_key(
                &boring::pkey::PKey::private_key_from_pem(&pair.key_pem).expect("a key"),
            )
            .expect("the key");
            tls.set_select_certificate_callback(move |hello| {
                *caught.lock().expect("the ClientHello") =
                    Some(tls::client_hello::parse_body(hello.as_bytes()));
                Ok(())
            });
            let mut config =
                quiche::Config::with_boring_ssl_ctx_builder(quiche::PROTOCOL_VERSION, tls)
                    .expect("a configuration");
            config
                .set_application_protos(&[consts::ALPN_H3])
                .expect("h3");

            let mut conn: Option<quiche::Connection> = None;
            let mut packet = vec![0u8; 65535];
            let mut out = vec![0u8; 1500];
            loop {
                let (read, from) = socket.recv_from(&mut packet).await.expect("a packet");
                if conn.is_none() {
                    // A long header of the Initial type, and version 1.
                    if read <= 5 || packet[0] & 0xf0 != 0xc0 || packet[1..5] != [0, 0, 0, 1] {
                        continue;
                    }
                    let scid = [7u8; 16];
                    let scid = quiche::ConnectionId::from_ref(&scid);
                    conn = Some(
                        quiche::accept(&scid, None, address, from, &mut config)
                            .expect("a connection"),
                    );
                }
                let conn = conn.as_mut().expect("the connection");
                let _ = conn.recv(&mut packet[..read], quiche::RecvInfo { from, to: address });
                // A ClientHello with a post-quantum key share takes more than one packet.
                if let Some(seen) = seen.lock().expect("the ClientHello").take() {
                    return seen;
                }
                while let Ok((written, sent)) = conn.send(&mut out) {
                    socket
                        .send_to(&out[..written], sent.to)
                        .await
                        .expect("a packet sent");
                }
            }
        });
        (address, hello)
    }

    /// The check of the MASQUE gateway at `address`, or the tunnel through it, over HTTP/2 or
    /// HTTP/3 as AETHER_MASQUE_HTTP2 has it: what the test runs as the client.
    async fn masque_client(
        identity: &account::Identity,
        address: SocketAddr,
        h2: bool,
        tunnel: bool,
    ) {
        if tunnel {
            let mtu = if h2 { H2_TUNNEL_MTU } else { MASQUE_MTU };
            let datagram = quic::MAX_DATAGRAM_SIZE;
            let startup = std::time::Duration::from_secs(10);
            let hop = establish_masque(
                identity, address, None, h2, mtu, datagram, false, true, startup, "test",
            );
            let _ = hop.await;
        } else {
            quick_verify_masque_peer(identity, address, None).await;
        }
    }

    #[tokio::test]
    async fn the_masque_handshakes_take_the_tls13_order_and_the_key_shares_of_the_options() {
        let _setting = upstream::hold_setting().await;
        let _options = tls::hold_options().await;
        // Firefox's: its TLS 1.3 order, and key shares for three of its groups.
        std::env::set_var(
            "AETHER_TLS13_CIPHERS",
            "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_256_GCM_SHA384",
        );
        std::env::set_var(
            "AETHER_TLS_GROUPS",
            "X25519MLKEM768:X25519:P-256:P-384:P-521",
        );
        std::env::set_var("AETHER_TLS_KEY_SHARES", "X25519MLKEM768:X25519:P-256");
        let identity = account::handshake_identity();
        for h2 in [false, true] {
            // The check of a gateway, and the tunnel.
            for tunnel in [false, true] {
                let case = format!("h2 {h2}, tunnel {tunnel}");
                let hello = masque_hello(&identity, h2, tunnel, &case).await;
                assert_eq!(hello.key_shares(), [0x11ec, 0x001d, 0x0017], "{case}");
                // QUIC carries TLS 1.3 alone; over HTTP/2 the TLS 1.2 suites follow.
                assert_eq!(hello.tls13_suites(), [0x1301, 0x1303, 0x1302], "{case}");
            }
        }
    }

    /// The ClientHello of a MASQUE handshake, over HTTP/2 or HTTP/3, of the check of a gateway
    /// or of the tunnel, as a server on this machine reads it.
    async fn masque_hello(
        identity: &account::Identity,
        h2: bool,
        tunnel: bool,
        case: &str,
    ) -> tls::client_hello::ClientHello {
        if h2 {
            std::env::set_var("AETHER_MASQUE_HTTP2", "1");
        } else {
            std::env::remove_var("AETHER_MASQUE_HTTP2");
        }
        let (address, hello) = if h2 {
            tls::client_hello::catch().await
        } else {
            h3_hello_server().await
        };
        tokio::select! {
            biased;
            hello = hello => hello.expect("the ClientHello"),
            () = masque_client(identity, address, h2, tunnel) => {
                panic!("{case}: no ClientHello reached the server")
            }
        }
    }

    #[tokio::test]
    async fn the_masque_handshakes_carry_the_extensions_of_the_options() {
        let _setting = upstream::hold_setting().await;
        let _options = tls::hold_options().await;
        // Chrome's, as its ClientHello of October 2026 has them, with fewer trust anchors.
        std::env::set_var("AETHER_ENABLE_GREASE_SIGALGS", "1");
        std::env::set_var(
            "AETHER_TLS_SIGALGS",
            "mldsa44:mldsa65:mldsa87:ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256",
        );
        std::env::set_var("AETHER_TLS_CERT_COMPRESSION", "brotli");
        std::env::set_var("AETHER_TLS_ALPS", "new");
        std::env::set_var("AETHER_TLS_TRUST_ANCHORS", "11129.9.1");
        std::env::set_var("AETHER_TLS_SERVER_PADDING", "6000");
        std::env::set_var("AETHER_ENABLE_ECH_GREASE", "1");
        let identity = account::handshake_identity();
        for h2 in [false, true] {
            for tunnel in [false, true] {
                let case = format!("h2 {h2}, tunnel {tunnel}");
                let hello = masque_hello(&identity, h2, tunnel, &case).await;
                assert!(hello.has_grease_signature_algorithm(), "{case}");
                assert_eq!(
                    hello.signature_algorithms()[1..],
                    [0x0904, 0x0905, 0x0906, 0x0403, 0x0804],
                    "{case}"
                );
                assert_eq!(hello.cert_compression(), [2], "{case}");
                // ALPS for the protocol the carrier offers: HTTP/2, which comes with HTTP/1.1,
                // or HTTP/3.
                let protocol = if h2 { b"h2".to_vec() } else { b"h3".to_vec() };
                assert_eq!(hello.alps(0x44cd), Some(vec![protocol]), "{case}");
                assert!(hello.offers_ech(), "{case}");
                assert_eq!(
                    hello.extension(0xca34),
                    Some(&[0, 5, 4, 0xd6, 0x79, 0x09, 0x01][..]),
                    "{case}"
                );
                assert_eq!(hello.server_padding(), Some(6000), "{case}");
            }
        }

        // Python's: neither OCSP nor signed certificate timestamps, over either carrier.
        for variable in [
            "AETHER_ENABLE_GREASE_SIGALGS",
            "AETHER_TLS_SIGALGS",
            "AETHER_TLS_CERT_COMPRESSION",
            "AETHER_TLS_ALPS",
            "AETHER_TLS_TRUST_ANCHORS",
            "AETHER_TLS_SERVER_PADDING",
            "AETHER_ENABLE_ECH_GREASE",
        ] {
            std::env::remove_var(variable);
        }
        std::env::set_var("AETHER_DISABLE_OCSP_STAPLING", "1");
        std::env::set_var("AETHER_DISABLE_SCT", "1");
        for h2 in [false, true] {
            let case = format!("h2 {h2}");
            let hello = masque_hello(&identity, h2, false, &case).await;
            assert!(
                !hello.has_extension(5) && !hello.has_extension(18),
                "{case}"
            );
            assert!(!hello.offers_ech(), "{case}");
            assert_eq!(hello.alps(0x44cd), None, "{case}");
        }
    }
}

pub mod identity;

mod socketprotect;

use std::sync::atomic::AtomicBool;

pub fn set_socket_protector(
    protector: Option<std::sync::Arc<dyn Fn(i32) -> bool + Send + Sync + 'static>>,
) {
    socketprotect::set(protector);
}

/// What MASQUE can carry, and it is not ours to choose.
///
/// Cloudflare caps the payload, so this is a limit the tunnel is given rather
/// than one it picks.
const MASQUE_MTU: usize = 1280;

/// What WireGuard can carry, which is a different question.
///
/// The 1280 above was applied to both for no better reason than sharing a
/// constant, and it cost hysteria2 and tuic nodes behind the tunnel: they need
/// a 1280-byte UDP payload, and 1280 inner leaves 1252 after the 28-byte outer
/// header. WireGuard adds 32 bytes of its own, so 1340 inner goes out as a 1400
/// byte datagram -- under the 1500 any ordinary path carries -- and leaves 1312
/// for the payload, which is enough.
///
/// MASQUE cannot follow it there. The cap is Cloudflare's, so QUIC-based nodes
/// stay impossible on that transport and the app says so.
const WIREGUARD_MTU: usize = 1340;

pub const fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

#[derive(Debug, Clone)]
pub struct EmbeddedConfig {
    pub config_path: String,
    pub listen: SocketAddr,
    pub peer: Option<SocketAddr>,
    pub peer_fallback: bool,
    pub scan_mode: String,
    pub ip_scan: String,
    /// Which tunnel to build: `masque` or `wireguard`.
    ///
    /// Defaults to MASQUE when empty, because that is what every embedded
    /// caller wanted before there was a choice.
    pub protocol: String,
    /// Who may use the SOCKS5 listener.
    ///
    /// Carried here rather than derived from [`Self::listen`]: whether a
    /// password is demanded is the caller's decision, and a listener on the
    /// local network is a deliberate one either way.
    pub access: socks::Access,
}

impl EmbeddedConfig {
    fn protocol(&self) -> Protocol {
        match self.protocol.trim() {
            "" => Protocol::Masque,
            other => Protocol::parse(other),
        }
    }

    /// A copy that pins [`peer`], so the run path re-establishes the profile for
    /// the endpoint prepare already chose rather than scanning again.
    fn clone_with_peer(&self, peer: SocketAddr) -> Self {
        Self {
            peer: Some(peer),
            ..self.clone()
        }
    }

    /// Where this protocol's identity lives.
    ///
    /// MASQUE and WARP provision separate accounts against different Cloudflare
    /// APIs, so they cannot share a file. Switching protocol keeps both.
    fn identity_path(&self) -> String {
        match self.protocol() {
            Protocol::Masque | Protocol::MasqueInMasque => masque_config_path(&self.config_path),
            Protocol::WireGuard | Protocol::WarpInWarp => warp_config_path(&self.config_path),
        }
    }

    /// The second account, for the inner hop of a nested tunnel.
    ///
    /// Two accounts, not one used twice: the inner tunnel handshakes through the
    /// outer one, and Cloudflare would see the same device connecting to itself.
    fn secondary_identity_path(&self) -> String {
        derive_sibling_path(&self.identity_path(), "secondary")
    }

    /// The outer account's slot, and where this install keeps it.
    fn identity_site(&self) -> IdentitySite {
        identity_site(
            &self.config_path,
            match self.protocol() {
                Protocol::Masque | Protocol::MasqueInMasque => identity::Slot::Masque,
                Protocol::WireGuard | Protocol::WarpInWarp => identity::Slot::Wireguard,
            },
        )
    }

    /// The inner account's slot, for a nested tunnel.
    fn secondary_identity_site(&self) -> IdentitySite {
        identity_site(
            &self.config_path,
            match self.protocol() {
                Protocol::Masque | Protocol::MasqueInMasque => identity::Slot::MasqueInner,
                Protocol::WireGuard | Protocol::WarpInWarp => identity::Slot::WireguardInner,
            },
        )
    }
}

#[derive(Debug, Clone)]
pub struct EmbeddedPrepared {
    pub ipv4: String,
    pub ipv6: String,
    pub peer: SocketAddr,
}

#[derive(Debug, Clone, Copy)]
pub struct EmbeddedScanResult {
    pub peer: SocketAddr,
    pub rtt: std::time::Duration,
}

pub enum EmbeddedEndpoint {
    Socks,
    Tun {
        device_to_tunnel: tokio::sync::mpsc::Receiver<Vec<u8>>,
        tunnel_to_device: tokio::sync::mpsc::Sender<Vec<u8>>,
    },
}

/// The envelope an exported identity travels in.
///
/// Versioned because it leaves the device and can come back into a build that
/// did not write it. Refusing an unknown version is the honest failure; guessing
/// at its shape and writing the result over a working identity is not.
/// Format 2 carries every identity an install holds; format 1 carried the two
/// WireGuard ones and silently left MASQUE's behind -- which is the only one a
/// default install has, so for most people the backup was refused as empty.
/// Both are read; 2 is written.
const IDENTITY_EXPORT_VERSION: u32 = 2;

/// Every identity file an install can hold, and the slot each one fills.
///
/// One definition of where these live, used by the backup, by the migration
/// into the identity store, and by the file each slot is still written to
/// while both exist.
fn identity_slots(base_config: &str) -> [(identity::Slot, String); 4] {
    let warp = warp_config_path(base_config);
    let masque = masque_config_path(base_config);
    [
        (identity::Slot::Wireguard, warp.clone()),
        (
            identity::Slot::WireguardInner,
            derive_sibling_path(&warp, "secondary"),
        ),
        (identity::Slot::Masque, masque.clone()),
        (
            identity::Slot::MasqueInner,
            derive_sibling_path(&masque, "secondary"),
        ),
    ]
}

/// What the backup format calls each slot.
///
/// The format's names, not the store's: `identity` stays what format 1 called
/// the WireGuard account rather than being tidied into something better,
/// because a file somebody exported last month has to keep importing.
fn export_key(slot: identity::Slot) -> &'static str {
    match slot {
        identity::Slot::Wireguard => "identity",
        identity::Slot::WireguardInner => "secondary",
        identity::Slot::Masque => "masque",
        identity::Slot::MasqueInner => "masque_secondary",
    }
}

/// Where this install keeps the store, beside the files it supersedes.
fn store_path(base_config: &str) -> String {
    derive_sibling_path(&warp_config_path(base_config), "store")
}

/// Everything the provisioning path needs for one identity.
///
/// Which role it fills, the store that answers for it, and the file that role
/// is still written to -- plus every other file, because the store is built
/// from them the first time it is read.
#[derive(Debug, Clone)]
struct IdentitySite {
    slot: identity::Slot,
    /// The file this slot still gets written to, for one release.
    path: String,
    store_path: String,
    legacy: Vec<(identity::Slot, String)>,
}

fn identity_site(base_config: &str, slot: identity::Slot) -> IdentitySite {
    let legacy = identity_slots(base_config).to_vec();
    let path = legacy
        .iter()
        .find(|(candidate, _)| *candidate == slot)
        .map(|(_, path)| path.clone())
        .unwrap_or_else(|| warp_config_path(base_config));
    IdentitySite {
        slot,
        path,
        store_path: store_path(base_config),
        legacy,
    }
}

/// Records an identity as the device filling this slot.
///
/// In the store, which is what this build reads, *and* in the file an earlier
/// build reads. Both for one release: writing only the store would cost a user
/// who goes back to 1.8.1 their registration, and writing only the file is what
/// the store exists to stop.
fn record(
    store: &mut identity::Store,
    site: &IdentitySite,
    identity: &account::Identity,
) -> Result<()> {
    let id = identity.device_id.clone();
    let mut device = identity::device_from(identity, account::now_unix());

    if let Some(existing) = store.device(&id) {
        // When the device was first registered is a fact about the device, not
        // about this write.
        if existing.registered_at != 0 {
            device.registered_at = existing.registered_at;
        }
        device.refused_at = existing.refused_at;
        // A certificate that has arrived is the answer to the enrolment that
        // was in flight. One that has not leaves the question standing.
        device.enrolment_pending_since = if device.has_certificate() {
            0
        } else {
            existing.enrolment_pending_since
        };
    }

    store.put(&id, device);
    store.assign(site.slot, &id)?;
    identity::save(&site.store_path, store)?;
    config::save(&site.path, identity)
}

/// Packages this install's identities so they survive a reinstall.
///
/// Cloudflare rate-limits device registrations per address, and uninstalling
/// discards the identity -- so a few reinstalls can leave an address refused
/// outright. Carrying the registration across is the difference between that and
/// connecting immediately.
///
/// Every account goes in. Each nested tunnel needs a second one for its inner
/// hop, and MASQUE's is a separate registration from WireGuard's -- leaving any
/// of them behind has the user pay for it again, which is the cost this exists
/// to avoid.
///
/// Format 1 exported only the two WireGuard files. A default install has never
/// run WireGuard -- the transport starts on MASQUE -- so it had no such file,
/// and the one defence against losing an identity answered "there is no
/// identity to export yet" to the people who most needed it.
///
/// Read with [`config::peek`]: a backup is a read, and [`config::load`] would
/// set aside a file it could not parse. Refusing to back up the rest because
/// one slot is damaged is the wrong way round.
pub fn export_identity(base_config: &str) -> Result<String> {
    let site = identity_site(base_config, identity::Slot::Wireguard);
    let loaded = identity::load(&site.store_path, &site.legacy)?;

    // From the store, which is what this build dials with -- so what leaves the
    // device is what it would have used, rather than what the files beside it
    // happen to say. A slot the invariants cleared is a slot with nothing worth
    // carrying: restoring a device whose key Cloudflare overwrote would hand
    // the next install the same three-minute search that found nothing.
    let held: Vec<(identity::Slot, account::Identity)> = identity::Slot::ALL
        .into_iter()
        .filter_map(|slot| {
            let id = loaded.store.device_id(slot)?;
            let device = loaded.store.device(id)?;
            identity::identity_from(id, device)
                .ok()
                .map(|identity| (slot, identity))
        })
        .collect();

    let Some((_, first)) = held.first() else {
        return Err(AetherError::Other(
            "there is no identity to export yet".into(),
        ));
    };

    let mut out = String::new();
    out.push_str(&format!("version = {IDENTITY_EXPORT_VERSION}\n"));
    out.push_str(&format!("device_id = {:?}\n", first.device_id));
    for (slot, identity) in &held {
        out.push_str(&format!("\n[{}]\n", export_key(*slot)));
        out.push_str(&config::to_text(identity)?);
    }
    Ok(out)
}

/// Restores identities produced by [`export_identity`].
///
/// Everything is parsed and checked before a single byte is written. Half an
/// import is worse than none: it would leave the device holding an identity
/// Cloudflare does not recognise, with the working one already gone.
pub fn import_identity(base_config: &str, payload: &str) -> Result<()> {
    let envelope: ExportEnvelope = toml::from_str(payload).map_err(|e| {
        AetherError::Other(format!("this is not a WhiteAesther identity file: {e}"))
    })?;

    // Older formats are read, not refused: a backup is written once and
    // restored much later, and the whole point of it is the moment when
    // registering again is not an option. Only a format from the future is
    // refused, because guessing at a shape this build has never seen and
    // writing the result over a working identity is worse than saying no.
    if envelope.version == 0 || envelope.version > IDENTITY_EXPORT_VERSION {
        return Err(AetherError::Other(format!(
            "this file was written by a different version of WhiteAesther (format {}, this build reads {IDENTITY_EXPORT_VERSION})",
            envelope.version
        )));
    }

    // Everything is parsed before a single byte is written, so a file that is
    // half readable cannot leave the device half restored.
    let carried = [
        envelope.identity,
        envelope.secondary,
        envelope.masque,
        envelope.masque_secondary,
    ];
    let mut ready: Vec<(identity::Slot, String, account::Identity)> = Vec::new();
    for ((slot, path), value) in identity_slots(base_config).into_iter().zip(carried) {
        let Some(value) = value else { continue };
        let text = toml::to_string(&value).map_err(|e| {
            AetherError::Other(format!(
                "the {} identity is malformed: {e}",
                export_key(slot)
            ))
        })?;
        ready.push((slot, path, config::parse(&text)?));
    }

    if ready.is_empty() {
        return Err(AetherError::Other(
            "this file is a WhiteAesther identity backup but carries no identity".into(),
        ));
    }

    let site = identity_site(base_config, identity::Slot::Wireguard);
    let mut loaded = identity::load(&site.store_path, &site.legacy)?;
    for (slot, _, identity) in &ready {
        let incoming = identity::device_from(identity, account::now_unix());
        // The same device can arrive twice, once per slot -- which is exactly
        // what a backup of an install 1.8.0 had broken looks like. Whichever
        // copy shows an enrolment is the one that knows what Cloudflare holds;
        // a copy that does not is silence, and silence is not evidence that a
        // key came back. Merged rather than overwritten so the answer does not
        // depend on which section the file happened to list first.
        let merged = match loaded.store.device(&identity.device_id) {
            Some(existing)
                if existing.tunnel_type == identity::TunnelType::Masque
                    && !incoming.has_certificate() =>
            {
                identity::Device {
                    tunnel_type: identity::TunnelType::Masque,
                    cert_pem: existing.cert_pem.clone(),
                    key_pem: existing.key_pem.clone(),
                    cert_issued_at: existing.cert_issued_at,
                    ..incoming
                }
            }
            _ => incoming,
        };
        loaded.store.put(&identity.device_id, merged);
        loaded.store.assign(*slot, &identity.device_id)?;
    }

    // Checked like anything else. A backup taken from an install that 1.8.0
    // had already broken carries one device in two slots, and restoring that
    // faithfully would restore the breakage with it.
    let repairs = loaded.store.repair();
    identity::save(&site.store_path, &loaded.store)?;

    let mut restored = 0usize;
    for (slot, path, identity) in &ready {
        if loaded.store.device_id(*slot) != Some(identity.device_id.as_str()) {
            log::warn!(
                "[-] the {} identity in this backup was not restored: its device is not one                  Cloudflare still holds a key for",
                export_key(*slot)
            );
            continue;
        }
        config::write_identity(path, identity)?;
        restored += 1;
    }

    log::info!(
        "[+] imported {restored} identit{}, the first for device {}",
        if restored == 1 { "y" } else { "ies" },
        ready[0].2.device_id
    );
    if !repairs.is_empty() {
        log::info!("[!] some of the backup was set aside: {repairs:?}");
    }
    Ok(())
}

#[derive(serde::Deserialize)]
struct ExportEnvelope {
    version: u32,
    /// The WireGuard account. Named `identity` because format 1 named it that,
    /// back when it was the only one a backup carried.
    #[serde(default)]
    identity: Option<toml::Value>,
    #[serde(default)]
    secondary: Option<toml::Value>,
    #[serde(default)]
    masque: Option<toml::Value>,
    #[serde(default)]
    masque_secondary: Option<toml::Value>,
}

/// Provisions the identities this configuration needs, and nothing else.
///
/// No endpoint search and no tunnel. Preparation does both, and they are the
/// slow half -- several thousand probes -- which is exactly what makes them the
/// wrong thing to do here. This runs while some other carrier is already
/// carrying the user's traffic, for one purpose: to buy the registration that
/// carrier has made reachable, so the *next* connect can be the engine's own
/// direct one.
///
/// Every request it makes goes out through whatever `AETHER_UPSTREAM` names, so
/// with a working carrier's SOCKS listener there, registration leaves by the
/// route that works instead of the one that does not. On a network where
/// `api.cloudflareclient.com` is unreachable in every direction this is the
/// only way an identity is ever obtained at all.
///
/// Costs nothing when the identities are already held: both loaders answer from
/// the store without a round trip.
pub async fn provision_embedded(config: &EmbeddedConfig) -> Result<Vec<String>> {
    let nested = matches!(
        config.protocol(),
        Protocol::MasqueInMasque | Protocol::WarpInWarp
    );

    let mut devices = vec![
        match config.protocol() {
            Protocol::Masque | Protocol::MasqueInMasque => {
                load_or_provision_masque(&config.identity_site()).await?
            }
            Protocol::WireGuard | Protocol::WarpInWarp => {
                load_or_provision_warp(&config.identity_site()).await?
            }
        }
        .device_id,
    ];

    if nested {
        devices.push(
            match config.protocol() {
                Protocol::MasqueInMasque => {
                    load_or_provision_masque(&config.secondary_identity_site()).await?
                }
                _ => load_or_provision_warp(&config.secondary_identity_site()).await?,
            }
            .device_id,
        );
    }

    Ok(devices)
}

pub async fn prepare_embedded(config: &EmbeddedConfig) -> Result<EmbeddedPrepared> {
    quic::masque_sni()?;
    account::check_api_settings()?;
    upstream::check_setting()?;
    // Before anything is registered or searched for: a promise that cannot be
    // kept should be refused at the start, not discovered at the handshake.
    if matches!(
        config.protocol(),
        Protocol::Masque | Protocol::MasqueInMasque
    ) {
        ech_policy_satisfied().await?;
    }
    let config_path = config.identity_path();
    // Matched exhaustively rather than "MASQUE, otherwise WireGuard". 2.0.0
    // added a fourth protocol, and under the old shape it would have gone down
    // the WireGuard path -- provisioning a WARP account and building a tunnel
    // nobody asked for, without saying so. Now the next protocol upstream adds
    // is a compile error here instead of a silent one.
    let identity = match config.protocol() {
        Protocol::Masque | Protocol::MasqueInMasque => {
            load_or_provision_masque(&config.identity_site()).await?
        }
        Protocol::WireGuard | Protocol::WarpInWarp => {
            load_or_provision_warp(&config.identity_site()).await?
        }
    };
    // Nested, this is the outer edge. The inner ones are derived from it when
    // the tunnel is built rather than chosen here, so preparing a nested tunnel
    // costs exactly what preparing a single one does.
    let peer = match config.protocol() {
        Protocol::Masque | Protocol::MasqueInMasque => {
            select_embedded_peer(&identity, config, &config_path).await?
        }
        Protocol::WireGuard | Protocol::WarpInWarp => {
            select_embedded_wg_peer(&identity, config, &config_path)
                .await?
                .0
        }
    };

    // Nested, the interface is addressed for the inner account: that is the one
    // whose packets reach the internet, and addressing it as the outer would
    // give every connection the wrong source.
    if config.protocol() == Protocol::MasqueInMasque {
        let secondary = load_or_provision_masque(&config.secondary_identity_site()).await?;
        return Ok(EmbeddedPrepared {
            ipv4: secondary.ipv4,
            ipv6: secondary.ipv6,
            peer,
        });
    }

    if config.protocol() == Protocol::WarpInWarp {
        let secondary = load_or_provision_warp(&config.secondary_identity_site()).await?;
        return Ok(EmbeddedPrepared {
            ipv4: secondary.ipv4,
            ipv6: secondary.ipv6,
            peer,
        });
    }

    Ok(EmbeddedPrepared {
        ipv4: identity.ipv4,
        ipv6: identity.ipv6,
        peer,
    })
}

/// Picks a WireGuard endpoint, and the obfuscation profile that reached it.
///
/// The profile travels with the address because for WireGuard they are one
/// answer, not two: an endpoint verified under one profile does not necessarily
/// answer under another, so carrying the address alone would lose half of what
/// the scan established.
async fn select_embedded_wg_peer(
    identity: &account::Identity,
    config: &EmbeddedConfig,
    config_path: &str,
) -> Result<(SocketAddr, aethernoize::AetherNoizeConfig, String)> {
    let candidates = wg_profile_candidates();

    if let Some(peer) = config.peer {
        for (name, profile) in &candidates {
            if verify_wg_peer(identity, peer, profile).await.is_ok() {
                return Ok((peer, profile.clone(), name.clone()));
            }
        }
        if !config.peer_fallback {
            return Err(AetherError::Other(format!(
                "custom endpoint {peer} failed WireGuard validation"
            )));
        }
        log::warn!("[-] custom endpoint {peer} failed; falling back to automatic discovery");
    }

    if let Some(cached) = lastconn::load(&lastconn_path(config_path)) {
        if cached.applies_to(&attempt_proof("wg")) {
            for peer in lastconn::usable_peers(&cached, lastconn::CARRIER_WIREGUARD) {
                let profile = aethernoize::from_profile(&cached.profile);
                if verify_wg_peer(identity, peer, &profile).await.is_ok() {
                    log::info!("[+] cached WireGuard endpoint {peer} still works");
                    return Ok((peer, profile, cached.profile.clone()));
                }
            }
        }
    }

    hunt_wg_peer(
        identity,
        &candidates,
        &config.scan_mode,
        prober::IpScan::parse(&config.ip_scan),
        &HashSet::new(),
    )
    .await
}

async fn verify_wg_peer(
    identity: &account::Identity,
    peer: SocketAddr,
    profile: &aethernoize::AetherNoizeConfig,
) -> Result<std::time::Duration> {
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;
    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;
    wireguard::verify_endpoint(
        peer,
        private_key,
        peer_public,
        identity.client_id,
        ipv4,
        profile,
        std::time::Duration::from_secs(8),
        None,
    )
    .await
}

pub async fn scan_embedded(
    config: &EmbeddedConfig,
    limit: usize,
    cancelled: &AtomicBool,
) -> Result<Vec<EmbeddedScanResult>> {
    if !matches!(config.protocol(), Protocol::Masque) {
        return scan_embedded_wg(config, limit, cancelled).await;
    }
    let identity = load_or_provision_masque(&config.identity_site()).await?;
    let probe = masque_probe(&identity, prober::IpScan::parse(&config.ip_scan)).await?;
    let results = prober::scan_gateways(
        &probe,
        prober::ScanMode::parse(&config.scan_mode),
        limit,
        cancelled,
    )
    .await?;
    Ok(results
        .into_iter()
        .map(|result| EmbeddedScanResult {
            peer: SocketAddr::new(result.ip, result.port),
            rtt: result.rtt,
        })
        .collect())
}

pub async fn test_embedded_peer(config: &EmbeddedConfig) -> Result<EmbeddedScanResult> {
    let peer = config
        .peer
        .ok_or_else(|| AetherError::Other("custom endpoint is required".into()))?;
    if !matches!(config.protocol(), Protocol::Masque) {
        let identity = load_or_provision_warp(&config.identity_site()).await?;
        // Every profile, because an endpoint that refuses one may answer
        // another, and reporting the first refusal as "dead" would be wrong.
        let mut last = AetherError::NoCleanEndpoint;
        for (_, profile) in wg_profile_candidates() {
            match verify_wg_peer(&identity, peer, &profile).await {
                Ok(rtt) => return Ok(EmbeddedScanResult { peer, rtt }),
                Err(error) => last = error,
            }
        }
        return Err(last);
    }
    let identity = load_or_provision_masque(&config.identity_site()).await?;
    let rtt = verify_masque_peer(&identity, peer).await?;
    Ok(EmbeddedScanResult { peer, rtt })
}

pub async fn run_embedded(
    config: EmbeddedConfig,
    endpoint: EmbeddedEndpoint,
    mut ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    // Before any listener is bound. Every tunnel below reaches socks::serve by
    // its own path, and none of them carries this.
    socks::configure_access(config.access.clone());

    let peer = config
        .peer
        .ok_or_else(|| AetherError::Other("embedded peer is required".into()))?;
    let config_path = config.identity_path();

    if matches!(
        config.protocol(),
        Protocol::WireGuard | Protocol::WarpInWarp
    ) {
        let identity = load_or_provision_warp(&config.identity_site()).await?;
        // Which profile reaches this endpoint is part of what the scan found,
        // and it is not recorded anywhere the caller could hand back -- so it is
        // established again here rather than guessed.
        let (peer, profile, name) =
            select_embedded_wg_peer(&identity, &config.clone_with_peer(peer), &config_path).await?;
        log::info!("[+] WireGuard endpoint {peer} using aethernoize profile '{name}'");
        let ready = record_on_ready(
            lastconn_path(&config_path),
            peer,
            lastconn::Proof {
                profile: name.clone(),
                ..attempt_proof("wg")
            },
            &mut ready,
        );

        if config.protocol() == Protocol::WarpInWarp {
            let secondary = load_or_provision_warp(&config.secondary_identity_site()).await?;
            return run_warp_in_warp_embedded(
                &identity,
                &secondary,
                peer,
                config.listen,
                endpoint,
                ready,
            )
            .await;
        }

        return run_wireguard_tunnel_embedded(
            &identity,
            peer,
            profile,
            config.listen,
            endpoint,
            ready,
        )
        .await;
    }

    let identity = load_or_provision_masque(&config.identity_site()).await?;
    let ech = attempt_ech().await;
    if env_value("SPICY_AETHER_GOOL").as_deref() == Some("1") {
        if !matches!(endpoint, EmbeddedEndpoint::Socks) {
            return Err(AetherError::Other(
                "Gool requires the SOCKS VPN frontend".into(),
            ));
        }
        let inner_path = derive_sibling_path(&config.config_path, "gool-inner");
        let (ended, _end_receiver) = tokio::sync::mpsc::unbounded_channel();
        let listeners = Listeners::bind(config.listen, ended).await?;
        return run_gool_tunnel(&identity, peer, ech, &listeners, &inner_path, ready).await;
    }
    let mut endpoint = Some(endpoint);
    // The endpoint is remembered when the tunnel confirms it carries data, not
    // when it is chosen. Nested, this is the outer edge -- the one that came
    // out of the pool and is worth leading with next time.
    let mut ready = record_on_ready(
        lastconn_path(&config_path),
        peer,
        attempt_proof(masque_framing()),
        &mut ready,
    );

    if config.protocol() == Protocol::MasqueInMasque {
        // A second account, not the same one twice: the inner tunnel
        // handshakes through the outer, and Cloudflare would otherwise see one
        // device connecting to itself.
        let secondary = load_or_provision_masque(&config.secondary_identity_site()).await?;
        return run_masque_in_masque_embedded(
            &identity,
            &secondary,
            peer,
            ech,
            config.listen,
            &mut endpoint,
            &mut ready,
        )
        .await;
    }

    // Translated here rather than inside, because the inner hop of a nested
    // pair dials a local forwarder that must not be translated again.
    let dial = if masque_h2::enabled() {
        masque_h2::h2_peer(peer)
    } else {
        peer
    };
    run_masque_tunnel_embedded(
        &identity,
        dial,
        ech,
        MasqueShape::single(),
        config.listen,
        &mut endpoint,
        &mut ready,
    )
    .await
}

/// Two nested MASQUE hops behind the embedded endpoint abstraction.
///
/// The shape of `run_warp_in_warp_embedded`, with MASQUE on both hops: the
/// outer one keeps a userspace network stack because the forwarder needs
/// somewhere to open a socket, and the inner one's packets are the user's, so
/// they go to whatever the caller asked for.
///
/// For a network that has learnt to recognise a single MASQUE hop. The inner
/// edge is the one Cloudflare named for the inner device, with the derived
/// neighbours behind it as a fallback -- see [`order_inner_candidates`], and
/// note that this used to be the derivation alone, which is why nesting failed
/// on networks where a single hop worked.
async fn run_masque_in_masque_embedded(
    primary: &account::Identity,
    secondary: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    listen: SocketAddr,
    endpoint: &mut Option<EmbeddedEndpoint>,
    ready: &mut Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    let h2 = masque_h2::enabled();
    let outer_mtu = masque_tunnel_mtu();
    let outer_dial = if h2 { masque_h2::h2_peer(peer) } else { peer };

    log::info!("[*] establishing outer MASQUE tunnel to {peer}...");
    let outer = establish_masque(
        primary,
        outer_dial,
        ech,
        h2,
        outer_mtu,
        quic::MAX_DATAGRAM_SIZE,
        true,
        true,
        masque_startup_timeout(),
        "outer",
    )
    .await?;

    let candidates = order_inner_candidates(
        assigned_masque_peers(secondary).await,
        peer,
        inner_masque_candidates(peer, MIM_INNER_TRIES, false),
    );
    if candidates.is_empty() {
        outer.exit.abort();
        return Err(AetherError::Other(
            "no second masque edge is known for the inner hop".into(),
        ));
    }

    let mut last: Option<AetherError> = None;
    // QUIC first, then the same edges over TCP.
    //
    // The single-hop path has had both framings for as long as it has existed,
    // because which one gets through is exactly what differs between one
    // network and the next. The nested path had one, for both hops, whatever
    // the profile said -- so an inner edge that would not complete a QUIC
    // handshake ended the attempt, and the framing that might have worked was
    // never tried. On the mode whose whole reason to exist is the network that
    // has refused everything simpler.
    for framing in inner_framings(h2) {
        for edge in candidates.iter().copied() {
            let inner_peer = edge.addr;
            let shape = MasqueShape::mim_inner(outer_mtu, inner_peer, framing);
            if !framing && shape.datagram + 28 > outer_mtu {
                log::warn!(
                    "[-] the outer link carries {outer_mtu} bytes, too little for an inner                      quic datagram; raise AETHER_MASQUE_MTU"
                );
            }

            // Through the outer tunnel, so the inner handshake never touches
            // the network directly -- the whole point of nesting them.
            let (forwarder, _forwarder_guard) = if framing {
                spawn_tcp_forwarder(&outer.stack, masque_h2::h2_peer(inner_peer)).await?
            } else {
                spawn_udp_forwarder(&outer.stack, inner_peer).await?
            };
            log::info!(
                "[*] trying inner MASQUE edge {inner_peer} ({}) over {} through the outer                  tunnel via {forwarder}",
                edge.origin.label(),
                if framing { "HTTP/2" } else { "QUIC" }
            );

            let outcome = run_masque_tunnel_embedded(
                secondary, forwarder, None, shape, listen, endpoint, ready,
            )
            .await;

            match outcome {
                Ok(()) => return Ok(()),
                // It took the endpoint, so it was carrying the session: this is
                // the session ending rather than a candidate refusing, and
                // there is nothing left to offer the next one.
                Err(error) if endpoint.is_none() => {
                    outer.exit.abort();
                    return Err(error);
                }
                Err(error) => {
                    log::info!(
                        "[-] inner edge {inner_peer} does not serve masque from inside the                          tunnel: {error}"
                    );
                    last = Some(error);
                }
            }
        }
    }

    outer.exit.abort();
    Err(last.unwrap_or_else(|| {
        AetherError::Other("no inner masque edge answered from inside the outer tunnel".into())
    }))
}

/// A WARP-in-WARP tunnel behind the embedded endpoint abstraction.
///
/// Two WireGuard tunnels, the inner one handshaking through the outer. What an
/// observer sees is a single WARP session carrying opaque UDP; what actually
/// reaches the internet leaves the inner account, one hop further in.
///
/// The outer tunnel keeps a userspace network stack because the forwarder needs
/// somewhere to open a socket. The inner one does not: its packets are the
/// user's, so they go straight to whatever the caller asked for.
async fn run_warp_in_warp_embedded(
    primary: &account::Identity,
    secondary: &account::Identity,
    peer: SocketAddr,
    listen: SocketAddr,
    endpoint: EmbeddedEndpoint,
    ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    log::info!("[*] establishing outer WARP tunnel to {peer}...");
    let (outer_stack, mut outer_exit) =
        establish_wg(primary, peer, WIREGUARD_MTU, true, 5, "outer").await?;

    let (forwarder, _forwarder_guard) = spawn_udp_forwarder(&outer_stack, peer).await?;
    log::info!("[+] inner endpoint tunneled through outer warp via {forwarder}");

    // Obfuscation off and a slower keepalive on the inner hop: it is already
    // inside an obfuscated tunnel, so a second layer costs bytes and hides
    // nothing that the outer one has not hidden already.
    log::info!("[*] establishing inner WARP tunnel (warp-in-warp)...");
    let inner = establish_wg_channels(secondary, forwarder, false, 20, "inner").await?;
    let mut inner_exit = inner.exit;

    // Both hops are up and have each carried a packet, so this is the first
    // point at which the tunnel is genuinely usable.
    if let Some(ready) = ready {
        let _ = ready.send(());
    }

    let mut endpoint_task = match endpoint {
        EmbeddedEndpoint::Socks => {
            let stack = netstack::spawn(
                &secondary.ipv4,
                &secondary.ipv6,
                INNER_MTU,
                inner.inbound_rx,
                inner.outbound_tx,
            )?;
            // Bound out here rather than inside the task, so that a port
            // already in use is an error this call returns instead of one that
            // disappears into a spawned future. bind_listener also says why.
            let listener = socks::bind_listener("socks5", listen, "--bind").await?;
            tokio::spawn(async move { socks::serve(listener, stack).await })
        }
        EmbeddedEndpoint::Tun {
            mut device_to_tunnel,
            tunnel_to_device,
        } => {
            let outbound_tx = inner.outbound_tx;
            let mut inbound_rx = inner.inbound_rx;
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        packet = device_to_tunnel.recv() => match packet {
                            Some(packet) => outbound_tx.send(packet).await.map_err(|_| {
                                AetherError::Other("embedded tunnel outbound channel closed".into())
                            })?,
                            None => return Ok(()),
                        },
                        packet = inbound_rx.recv() => match packet {
                            Some(packet) => tunnel_to_device.send(packet).await.map_err(|_| {
                                AetherError::Other("Android TUN writer channel closed".into())
                            })?,
                            None => return Ok(()),
                        },
                    }
                }
            })
        }
    };

    #[derive(PartialEq)]
    enum Finished {
        Outer,
        Inner,
        Endpoint,
    }

    let (outcome, finished) = tokio::select! {
        result = &mut outer_exit => (join_outcome("outer wireguard tunnel", result), Finished::Outer),
        result = &mut inner_exit => (join_outcome("inner wireguard tunnel", result), Finished::Inner),
        result = &mut endpoint_task => (
            match result {
                Ok(result) => result,
                Err(error) => {
                    Err(AetherError::Other(format!("embedded endpoint task failed: {error}")))
                }
            },
            Finished::Endpoint,
        ),
    };

    // The handle that just resolved above must not be awaited again. A
    // JoinHandle polled after it has yielded Ready panics, and a panic here
    // unwinds into the JNI boundary, which Android turns into an abort with no
    // Java stack trace -- a session that ends by vanishing rather than
    // stopping. Aborting a task that has already finished is harmless; awaiting
    // its handle a second time is not.
    if finished != Finished::Outer {
        outer_exit.abort();
        let _ = outer_exit.await;
    }
    if finished != Finished::Inner {
        inner_exit.abort();
        let _ = inner_exit.await;
    }
    if finished != Finished::Endpoint {
        endpoint_task.abort();
        let _ = endpoint_task.await;
    }
    drop(outer_stack);

    outcome
}

/// The same endpoint scan as [`scan_embedded`], for WireGuard.
async fn scan_embedded_wg(
    config: &EmbeddedConfig,
    limit: usize,
    cancelled: &AtomicBool,
) -> Result<Vec<EmbeddedScanResult>> {
    let identity = load_or_provision_warp(&config.identity_site()).await?;
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;

    let probe = wg_prober::WgProbe {
        private_key: std::sync::Arc::new(private_key),
        peer_public_key: std::sync::Arc::new(peer_public),
        client_id: identity.client_id,
        local_ipv4: identity
            .ipv4
            .parse()
            .map_err(|_| AetherError::Other("invalid ipv4".into()))?,
        aethernoize: aethernoize_config(),
        ports: wireguard::WG_PORTS.to_vec(),
        ip: prober::IpScan::parse(&config.ip_scan),
        excluded: HashSet::new(),
    };

    let results = wg_prober::scan_wg_endpoints(
        &probe,
        wg_prober::WgScanMode::parse(&config.scan_mode),
        limit,
        cancelled,
    )
    .await?;

    Ok(results
        .into_iter()
        .map(|result| EmbeddedScanResult {
            peer: SocketAddr::new(result.ip, result.port),
            rtt: result.rtt,
        })
        .collect())
}

/// A WireGuard tunnel behind the embedded endpoint abstraction.
///
/// The MASQUE equivalent has to wait for the far end to assign an address; here
/// the address came with the account, so there is nothing to wait for and the
/// handshake itself is the readiness signal.
async fn run_wireguard_tunnel_embedded(
    identity: &account::Identity,
    peer: SocketAddr,
    aethernoize: aethernoize::AetherNoizeConfig,
    listen: SocketAddr,
    endpoint: EmbeddedEndpoint,
    ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;
    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;

    log::info!("[*] validating WireGuard tunnel with {peer} (handshake + data-plane)...");
    let (_, session) = wireguard::verify_endpoint_keep_session(
        peer,
        private_key,
        peer_public,
        identity.client_id,
        ipv4,
        &aethernoize,
        wg_tunnel_validate_timeout(),
        Some(wg_keepalive_secs()),
    )
    .await
    .map_err(|error| AetherError::Other(format!("tunnel failed validation: {error}")))?;
    log::info!("[+] wireguard tunnel validated (end-to-end data confirmed)");

    // Only now, because unlike MASQUE this is the first point at which the far
    // end has actually carried a packet.
    if let Some(ready) = ready {
        let _ = ready.send(());
    }

    let (outbound_tx, outbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());
    let (inbound_tx, inbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());

    let tunnel = wireguard::WgTunnel::from_established(
        session,
        std::sync::Arc::new(aethernoize),
        inbound_tx,
        ipv4,
    );

    let mut endpoint_task = match endpoint {
        EmbeddedEndpoint::Socks => {
            let stack = netstack::spawn(
                &identity.ipv4,
                &identity.ipv6,
                WIREGUARD_MTU,
                inbound_rx,
                outbound_tx,
            )?;
            // Bound out here rather than inside the task, so that a port
            // already in use is an error this call returns instead of one that
            // disappears into a spawned future. bind_listener also says why.
            let listener = socks::bind_listener("socks5", listen, "--bind").await?;
            tokio::spawn(async move { socks::serve(listener, stack).await })
        }
        EmbeddedEndpoint::Tun {
            mut device_to_tunnel,
            tunnel_to_device,
        } => tokio::spawn(async move {
            let mut inbound_rx = inbound_rx;
            loop {
                tokio::select! {
                    packet = device_to_tunnel.recv() => match packet {
                        Some(packet) => outbound_tx.send(packet).await.map_err(|_| {
                            AetherError::Other("embedded tunnel outbound channel closed".into())
                        })?,
                        None => return Ok(()),
                    },
                    packet = inbound_rx.recv() => match packet {
                        Some(packet) => tunnel_to_device.send(packet).await.map_err(|_| {
                            AetherError::Other("Android TUN writer channel closed".into())
                        })?,
                        None => return Ok(()),
                    },
                }
            }
        }),
    };

    let mut tunnel_task = tokio::spawn(tunnel.run(outbound_rx));

    tokio::select! {
        result = &mut tunnel_task => {
            endpoint_task.abort();
            embedded_tunnel_result(result, "wireguard tunnel exited")
        }
        result = &mut endpoint_task => {
            tunnel_task.abort();
            let _ = tunnel_task.await;
            match result {
                Ok(result) => result,
                Err(error) => Err(AetherError::Other(format!("embedded endpoint task failed: {error}"))),
            }
        }
    }
}

async fn select_embedded_peer(
    identity: &account::Identity,
    config: &EmbeddedConfig,
    config_path: &str,
) -> Result<SocketAddr> {
    if let Some(peer) = config.peer {
        if quick_verify_masque_peer(identity, peer, tls::session_ech()).await {
            return Ok(peer);
        }
        if !config.peer_fallback {
            return Err(AetherError::Other(format!(
                "custom endpoint {peer} failed MASQUE validation"
            )));
        }
        log::warn!("[-] custom endpoint {peer} failed; falling back to automatic discovery");
    }

    if let Some(assigned) = std::env::var("AETHER_TEAM_ENDPOINT")
        .ok()
        .and_then(|value| value.parse::<SocketAddr>().ok())
    {
        if quick_verify_masque_peer(identity, assigned, tls::session_ech()).await {
            return Ok(assigned);
        }
    }

    if let Some(cached) = lastconn::load(&lastconn_path(config_path)) {
        // Only when it was earned by the attempt being made now. A gateway that
        // answered over TCP says nothing about a QUIC attempt, and leading with
        // it costs a check at the front of every connect.
        if cached.applies_to(&attempt_proof(masque_framing())) {
            for peer in lastconn::usable_peers(&cached, masque_carrier()) {
                if quick_verify_masque_peer(identity, peer, tls::session_ech()).await {
                    return Ok(peer);
                }
            }
        } else {
            log::debug!(
                "[*] the remembered endpoint was proven over {}, not this attempt's shape",
                cached.proof.transport
            );
        }
    }

    // The endpoint Cloudflare assigned this device, before any searching.
    //
    // It is in every registration answer -- `config.peers[0].endpoint` -- and
    // until now it was read, stored, and then used only by Zero Trust. So a
    // consumer account ignored the one address the server had just named and
    // brute-forced three thousand candidates instead: sixteen at a time, six
    // seconds each, a hundred and twenty second budget. That is roughly a tenth
    // of the pool, which is why MASQUE took minutes when it worked at all,
    // while WireGuard -- which does have a documented-anchor pass -- connects in
    // about three seconds.
    //
    // Measured against a live account: the assigned endpoint answered in 253ms
    // over H2 and 543ms over QUIC, while an address from the hard-coded pool
    // refused every protocol.
    for peer in assigned_masque_peers(identity).await {
        if quick_verify_masque_peer(identity, peer, tls::session_ech()).await {
            log::info!("[+] the endpoint Cloudflare assigned this device answered: {peer}");
            return Ok(peer);
        }
        log::info!("[-] the assigned endpoint {peer} did not answer");
    }

    hunt_masque_peer(
        identity,
        &config.scan_mode,
        prober::IpScan::parse(&config.ip_scan),
        &HashSet::new(),
    )
    .await
}

/// The MASQUE endpoints Cloudflare has named for this device, in order.
///
/// Two of them, because the answer moves. The address stored at registration is
/// free to try and is usually right; Cloudflare also reassigns a device between
/// edges, and `refresh_profile` has always had a line for noticing it. Measured
/// on one account minutes apart: registration said 162.159.192.2 and the device
/// record said 162.159.198.2.
///
/// So the stored one goes first because it costs nothing, and the current one
/// is asked for only when that fails -- one API call, skipped silently when it
/// cannot be answered, because the search still works afterwards. Slowly, which
/// is the whole reason this exists.
async fn assigned_masque_peers(identity: &account::Identity) -> Vec<SocketAddr> {
    fn on_443(host: &str) -> Option<SocketAddr> {
        let host = host.trim();
        if host.is_empty() {
            return None;
        }
        let candidate = if host.contains(':') && !host.starts_with('[') {
            format!("[{host}]:443")
        } else {
            format!("{host}:443")
        };
        candidate.parse::<SocketAddr>().ok()
    }

    let mut peers: Vec<SocketAddr> = Vec::new();
    if let Some(stored) = on_443(&identity.assigned_endpoint) {
        peers.push(stored);
    }

    // Bounded, because this runs on the path to a connect. Asking costs a round
    // trip to an API that some of these networks black-hole, and a stall here
    // would delay the search that still works -- so it gets a few seconds and
    // then we get on with it.
    let asked = tokio::time::timeout(
        ASSIGNED_ENDPOINT_LOOKUP,
        account::fetch_device(&identity.device_id, &identity.access_token),
    )
    .await;

    match asked {
        Err(_) => log::debug!("[-] no answer in time about which endpoint is assigned"),
        Ok(Ok(reg)) => {
            if let Some(current) = on_443(&account::endpoint_from(&reg)) {
                if !peers.contains(&current) {
                    if peers.is_empty() {
                        log::info!("[+] Cloudflare names {current} for this device");
                    } else {
                        log::info!("[+] Cloudflare has moved this device to {current}");
                    }
                    peers.push(current);
                }
            }
        }
        Ok(Err(error)) => log::debug!("[-] could not ask which endpoint is assigned: {error}"),
    }

    peers
}

/// How long the assigned-endpoint question may take before it is abandoned.
///
/// Short on purpose: it is an optimisation on the way to a connect, and the
/// search behind it works without an answer.
const ASSIGNED_ENDPOINT_LOOKUP: std::time::Duration = std::time::Duration::from_secs(4);

/// How one MASQUE hop is sized, and what it may skip.
///
/// A single hop, and the outer hop of a nested pair, get the whole budget. The
/// inner hop of a nested pair has to fit inside whatever the outer one carries,
/// and does not repeat the version bait its outer hop has already sent -- that
/// packet is for the network watching the outside of the tunnel, and inside
/// there is nobody to fool.
///
/// The same is now true of the junk packets and the fake first flight. They
/// exist to make the handshake unrecognisable to whatever is watching the wire,
/// and the inner hop's wire is the outer tunnel: already encrypted, already
/// carrying opaque traffic, and with nothing on it that could be fooled. They
/// were sent inside anyway, which was at best waste -- two junk datagrams and a
/// fake initial ahead of every inner attempt -- and at worst the reason a real
/// gateway would not complete the handshake.
struct MasqueShape {
    mtu: usize,
    datagram: usize,
    version_bait: bool,
    obfuscate: bool,
    /// TCP and HTTP/2 instead of QUIC, for this hop alone.
    ///
    /// It used to be read from the process-wide setting, which made the two
    /// hops of a nested pair one decision: either both QUIC or both TCP. They
    /// do not face the same thing. The outer hop faces the network the user is
    /// on; the inner one faces a Cloudflare edge through a tunnel that is
    /// already up, and whether that edge accepts a QUIC handshake arriving from
    /// inside WARP is not something the outer hop's answer can tell us.
    h2: bool,
    startup: std::time::Duration,
    label: &'static str,
}

impl MasqueShape {
    /// One hop straight to the edge.
    fn single() -> Self {
        Self {
            mtu: masque_tunnel_mtu(),
            datagram: quic::MAX_DATAGRAM_SIZE,
            version_bait: true,
            obfuscate: true,
            h2: masque_h2::enabled(),
            startup: masque_startup_timeout(),
            label: "masque",
        }
    }

    /// The inner hop of masque-in-masque, sized to fit the outer one.
    fn mim_inner(outer_mtu: usize, inner_peer: SocketAddr, h2: bool) -> Self {
        let (datagram, mtu) = mim_inner_budget(outer_mtu, inner_peer, h2);
        Self {
            mtu,
            datagram,
            version_bait: false,
            obfuscate: false,
            h2,
            startup: mim_inner_startup(),
            label: "inner",
        }
    }
}

/// One MASQUE hop behind the embedded endpoint abstraction.
///
/// \nparam peer already dialable: for HTTP/2 the caller translates the edge to
///   its H2 port, exactly as the desktop path does, because the inner hop of a
///   nested pair dials a local forwarder that must not be translated again.
/// \nparam endpoint taken only once this hop is up. Nested, the caller tries
///   inner edges until one answers, and an endpoint moved into a hop that
///   failed could not be offered to the next one. `ready` follows it for the
///   same reason, and because signalling it earlier would tell the app a route
///   is open while an inner edge is still being chosen.
async fn run_masque_tunnel_embedded(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    shape: MasqueShape,
    listen: SocketAddr,
    endpoint: &mut Option<EmbeddedEndpoint>,
    ready: &mut Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    let (chans, internals) = quic::channels();
    let cfg = quic::TunnelConfig {
        peer,
        sni: quic::masque_sni()?,
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: identity.cert_pem.clone(),
        key_pem: identity.key_pem.clone(),
        ech_config_list: ech.clone(),
        noize: if shape.obfuscate {
            noize_config()
        } else {
            noize::NoizeConfig::off()
        },
        local_ipv4: parse_local_v4(&identity.ipv4),
        quiet: false,
        max_datagram: shape.datagram,
        // The QUIC v2 version-negotiation bait that goes out ahead of the real
        // handshake. Gated again at run time, so this is the setting rather
        // than the decision.
        version_bait: shape.version_bait,
    };

    let quic::Channels {
        outbound_tx,
        inbound_rx,
        ctrl_tx,
    } = chans;
    // Reachable from outside for as long as this tunnel runs, so a phone that
    // changes network can move it rather than rebuild it.
    let _control = hold_tunnel_control(&ctrl_tx);
    let (addr_tx, mut addr_rx) = tokio::sync::mpsc::channel::<quic::AssignedAddr>(8);
    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel::<()>();

    let mut tunnel_task = if shape.h2 {
        let h2cfg = masque_h2::H2TunnelConfig {
            peer,
            sni: quic::masque_sni()?,
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: false,
            pin_endpoint: true,
            expected_pins: consts::masque_pins()
                .iter()
                .map(|pin| pin.to_vec())
                .collect(),
            ech_config_list: ech.clone(),
        };
        log::info!(
            "[+] [{}] MASQUE transport: HTTP/2 to {peer} (inner mtu {})",
            shape.label,
            shape.mtu
        );
        tokio::spawn(masque_h2::run(
            h2cfg,
            internals,
            Some(addr_tx),
            Some(ready_tx),
        ))
    } else {
        // Said here as well as in establish_masque, because until now only the
        // hop that dials the network printed its sizing. A nested pair whose
        // inner hop failed gave no way to tell whether it had room for its own
        // handshake, which is a question every report of one asked.
        let budget = cfg.datagram_budget();
        log::info!(
            "[+] [{}] MASQUE transport: HTTP/3 (QUIC) to {peer} (inner mtu {}, datagram {budget})",
            shape.label,
            shape.mtu
        );
        if budget > shape.datagram {
            log::warn!(
                "[-] [{}] the link above this hop leaves {} bytes, less than the {budget} a                  client handshake needs; it is being sent at {budget} and may not fit",
                shape.label,
                shape.datagram
            );
        }
        tokio::spawn(quic::run(cfg, internals, Some(addr_tx), Some(ready_tx)))
    };

    let label = shape.label;
    match tokio::time::timeout(shape.startup, ready_rx).await {
        Ok(Ok(())) => {}
        Ok(Err(_)) => {
            return embedded_tunnel_result(
                tunnel_task.await,
                &format!("[{label}] tunnel exited before validation"),
            )
        }
        Err(_) => {
            tunnel_task.abort();
            let _ = tunnel_task.await;
            return Err(AetherError::Other(format!(
                "[{label}] embedded tunnel startup timed out"
            )));
        }
    }
    // Taken only now. Everything above can fail and be retried against another
    // edge; from here the endpoint belongs to this hop.
    let endpoint = endpoint
        .take()
        .ok_or_else(|| AetherError::Other(format!("[{label}] has no endpoint to carry")))?;
    if let Some(ready) = ready.take() {
        let _ = ready.send(());
    }

    let mut endpoint_task = match endpoint {
        EmbeddedEndpoint::Socks => {
            // Sized by framing, as of 2.0.0: a MASQUE tunnel carried over
            // HTTP/2 is a TCP stream, where nothing has to fit inside one UDP
            // datagram, so the 1280 that keeps a QUIC datagram whole only buys
            // the netstack more segments to cut. Nested, this is smaller again,
            // because the inner hop's packets travel inside the outer one's.
            let stack = netstack::spawn(
                &identity.ipv4,
                &identity.ipv6,
                shape.mtu,
                inbound_rx,
                outbound_tx,
            )?;
            let bridge_stack = stack.clone();
            tokio::spawn(async move {
                while let Some(address) = addr_rx.recv().await {
                    let result = match address.ip {
                        IpAddr::V4(v4) => {
                            bridge_stack
                                .set_addrs(Some((v4, address.prefix)), None)
                                .await
                        }
                        IpAddr::V6(v6) => {
                            bridge_stack
                                .set_addrs(None, Some((v6, address.prefix)))
                                .await
                        }
                    };
                    if let Err(error) = result {
                        log::warn!("failed to update embedded netstack address: {error}");
                    }
                }
            });
            let listener = socks::bind_listener("socks5", listen, "--bind").await?;
            tokio::spawn(async move { socks::serve(listener, stack).await })
        }
        EmbeddedEndpoint::Tun {
            mut device_to_tunnel,
            tunnel_to_device,
        } => {
            tokio::spawn(async move {
                while let Some(address) = addr_rx.recv().await {
                    log::debug!(
                        "edge assigned embedded TUN address {}/{}",
                        address.ip,
                        address.prefix,
                    );
                }
            });
            tokio::spawn(async move {
                let mut inbound_rx = inbound_rx;
                loop {
                    tokio::select! {
                        packet = device_to_tunnel.recv() => match packet {
                            Some(packet) => outbound_tx.send(packet).await.map_err(|_| {
                                AetherError::Other("embedded tunnel outbound channel closed".into())
                            })?,
                            None => return Ok(()),
                        },
                        packet = inbound_rx.recv() => match packet {
                            Some(packet) => tunnel_to_device.send(packet).await.map_err(|_| {
                                AetherError::Other("Android TUN writer channel closed".into())
                            })?,
                            None => return Ok(()),
                        },
                    }
                }
            })
        }
    };

    tokio::select! {
        result = &mut tunnel_task => {
            endpoint_task.abort();
            embedded_tunnel_result(result, "embedded tunnel exited")
        }
        result = &mut endpoint_task => {
            let _ = ctrl_tx.send(quic::Control::Close).await;
            tunnel_task.abort();
            let _ = tunnel_task.await;
            match result {
                Ok(result) => result,
                Err(error) => Err(AetherError::Other(format!("embedded endpoint task failed: {error}"))),
            }
        }
    }
}

fn embedded_tunnel_result(
    result: std::result::Result<Result<()>, tokio::task::JoinError>,
    context: &str,
) -> Result<()> {
    match result {
        Ok(Ok(())) => Ok(()),
        Ok(Err(error)) => Err(AetherError::Other(format!("{context}: {error}"))),
        Err(error) => Err(AetherError::Other(format!("{context}: {error}"))),
    }
}

/// The inverse of [`derive_sibling_path`]: the file a sibling was derived from.
///
/// `None` when the path is not a sibling of that kind, which is what an
/// override through `AETHER_MASQUE_CONFIG` looks like -- and an override points
/// somewhere deliberate, so nothing should be migrated into it.
fn strip_sibling_path(path: &str, suffix: &str) -> Option<String> {
    let dir_end = path
        .rfind(|c| c == '/' || c == '\\')
        .map(|i| i + 1)
        .unwrap_or(0);
    let marker = format!("-{suffix}");
    match path[dir_end..].rfind('.') {
        Some(rel) => {
            let dot = dir_end + rel;
            let stem = &path[..dot];
            stem.strip_suffix(&marker)
                .map(|trimmed| format!("{trimmed}{}", &path[dot..]))
        }
        None => path.strip_suffix(&marker).map(|t| t.to_string()),
    }
}

/// Serialises everything that can cost a Cloudflare registration.
///
/// One lock for all identity files rather than one per file, because what it
/// guards is not a file -- it is the per-address registration quota behind
/// them. Two provisionings that never touch the same file still spend the same
/// allowance, and an address that has spent it is refused outright, for hours,
/// on every version of this app the user might fall back to.
///
/// Held across the whole cycle: register, enrol, and the writes that record
/// them. The app has a guard of its own, but it covers one preparation against
/// another -- not a preparation against the run that follows a cancelled one,
/// which is the pair that could actually overlap.
fn provisioning_lock() -> &'static tokio::sync::Mutex<()> {
    static LOCK: std::sync::OnceLock<tokio::sync::Mutex<()>> = std::sync::OnceLock::new();
    LOCK.get_or_init(|| tokio::sync::Mutex::new(()))
}

/// Every file beside `path` that holds an identity, `path` itself excluded.
///
/// Read through [`config::peek`], which does not set aside what it cannot
/// parse. [`config::load`] would: the cache of the last working endpoint lives
/// in this directory under a name of the same shape, and quarantining it for
/// failing to be an identity would throw away a working cache on every connect.
///
/// Names that no longer end in `.toml` are skipped, which is what a quarantined
/// file and a half-written temporary both look like.
fn identity_siblings(path: &str) -> Vec<(String, account::Identity)> {
    let dir_end = path
        .rfind(|c| c == '/' || c == '\\')
        .map(|i| i + 1)
        .unwrap_or(0);
    let dir = if dir_end == 0 { "." } else { &path[..dir_end] };

    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(_) => return Vec::new(),
    };

    let mut found = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !name.ends_with(".toml") {
            continue;
        }
        let sibling = if dir_end == 0 {
            name
        } else {
            format!("{}{name}", &path[..dir_end])
        };
        if sibling == path {
            continue;
        }
        if let Some(identity) = config::peek(&sibling) {
            found.push((sibling, identity));
        }
    }
    found
}

/// Where this device was enrolled for MASQUE, if it was enrolled anywhere else.
///
/// Enrolment is `PATCH /reg/{id}`, and it overwrites the same `key` field that
/// registration filled with the Curve25519 public key. Cloudflare then holds no
/// WireGuard key for the device at all, so every endpoint everywhere meets the
/// key on disk with silence -- which is indistinguishable from a network that
/// drops UDP, and sends the endpoint search off to blame the network for three
/// minutes and then report it as dead.
///
/// The guard meant to catch this asked the WireGuard file whether it carried a
/// certificate, and the enrolment that shipped happens on a *copy*: MASQUE
/// adopts a WireGuard-only identity into its own file and enrols it there, so
/// the WireGuard file goes on looking untouched for the rest of the install's
/// life. The question has to be put to the directory, not to one file.
///
/// A certificate at all, rather than a usable one. An expired certificate still
/// means the device's WireGuard key was overwritten; expiry says the MASQUE
/// credential needs renewing, not that WireGuard came back.
fn device_enrolled_elsewhere(config_path: &str, device_id: &str) -> Option<String> {
    identity_siblings(config_path)
        .into_iter()
        .find(|(_, other)| other.device_id == device_id && !other.cert_pem.is_empty())
        .map(|(path, _)| path)
}

/// Records an enrolment on every other file holding the same device.
///
/// So that the next read of that file can see from the file alone that the
/// WireGuard key in it is not one Cloudflare recognises any more -- rather than
/// finding out by searching several thousand endpoints that will never answer.
///
/// The certificate travels; the private key does not. What the mark has to
/// carry is the fact of the enrolment, and that file is not the one that will
/// present the certificate -- copying the secret into a second file would widen
/// its reach for no use.
///
/// Best effort on purpose. This runs after an enrolment that has already
/// succeeded, and failing to annotate a sibling must not fail the connect that
/// earned it; [`device_enrolled_elsewhere`] reaches the same conclusion from
/// the other direction if it does.
fn record_enrolment_beside(config_path: &str, identity: &account::Identity) {
    if identity.cert_pem.is_empty() {
        return;
    }
    for (path, other) in identity_siblings(config_path) {
        if other.device_id != identity.device_id || !other.cert_pem.is_empty() {
            continue;
        }
        match config::save_masque_creds(&path, &identity.cert_pem, &[], identity.cert_issued_at) {
            Ok(()) => log::info!(
                "[!] {path} holds the same device, whose WireGuard key this enrolment revoked; \
                 marked it so that protocol provisions an account of its own"
            ),
            Err(error) => log::warn!("[-] could not mark {path} as enrolled: {error}"),
        }
    }
}

/// Registers an account, unless this address still has a wait to serve.
///
/// The budget lives in the store because a wait a restart forgets is not a
/// wait: the failure it exists to stop is a phone asking again every few
/// seconds against an address Cloudflare has already refused, which is how an
/// allowance goes from spent to spent for a very long time.
///
/// Recorded whichever way it goes, and saved on the failing path too -- writing
/// down what it cost is the whole point of having asked.
async fn provision_within_budget(
    store: &mut identity::Store,
    site: &IdentitySite,
) -> Result<account::Identity> {
    // Through a proxy, the registration leaves from the proxy's address, so a
    // wait this device's own address has earned is not the one that applies.
    let through_proxy = crate::upstream::configured().is_some();
    registration_may_go(store, through_proxy, account::now_unix())?;

    match provision_account().await {
        Ok(identity) => {
            store
                .registration_budget(through_proxy)
                .succeeded(account::now_unix());
            Ok(identity)
        }
        Err(error) => {
            store.registration_budget(through_proxy).failed(
                account::now_unix(),
                &error.to_string(),
                error.retry_after(),
            );
            if let Err(write) = identity::save(&site.store_path, store) {
                log::warn!("[-] could not record what the registration attempt cost: {write}");
            }
            Err(error)
        }
    }
}

/// Whether a registration may be sent now, by the route it would leave by.
///
/// Refused here, with the reason the last attempt on that route was given,
/// while that route's wait runs: an attempt made then spends an allowance to
/// learn something already on disk.
fn registration_may_go(store: &mut identity::Store, through_proxy: bool, now: u64) -> Result<()> {
    let budget = store.registration_budget(through_proxy);
    if let Err(wait) = budget.may_attempt(now) {
        log::warn!(
            "[-] not registering{}: {wait}s of the wait from the last attempt is still to run ({})",
            if through_proxy {
                " through the proxy"
            } else {
                ""
            },
            budget.last_reason
        );
        return Err(AetherError::RegistrationOnHold {
            reason: budget.last_reason.clone(),
            wait,
        });
    }
    Ok(())
}

/// Enrols a MASQUE key onto `identity`, recording the attempt before it is made.
///
/// The marker is written and flushed first, and cleared by the write that
/// stores the certificate. Without that order there is a third outcome nothing
/// can see: Cloudflare accepted the PATCH, the answer never reached disk, and
/// the device is a MASQUE device that every file here still describes as a
/// WireGuard one. A file cannot record a change that was never written, so the
/// record has to come before the change.
async fn enrol_and_record(
    store: &mut identity::Store,
    site: &IdentitySite,
    identity: account::Identity,
) -> Result<account::Identity> {
    if let Some(device) = store.device_mut(&identity.device_id) {
        device.enrolment_pending_since = account::now_unix();
    }
    identity::save(&site.store_path, store)?;

    let enrollment = account::ensure_masque_enrolled(&identity).await?;
    let identity = account::Identity {
        cert_pem: enrollment.cert_pem,
        key_pem: enrollment.key_pem,
        cert_issued_at: enrollment.issued_at,
        ..identity
    };
    let identity = adopt_team_profile(identity).await;
    record(store, site, &identity)?;
    enable_recorded_warp(site, &identity, true).await?;
    // The file an earlier build reads has no store to tell it any of this, and
    // it decides by sweeping for a sibling holding the same device. Nothing
    // here shares a device across families any more, so this is almost always a
    // no-op -- almost being the reason it still runs.
    record_enrolment_beside(&site.path, &identity);
    Ok(identity)
}

/// The ECHConfigList this attempt uses, resolved once and shared.
///
/// Everything that validates an endpoint and the thing that finally dials it
/// have to agree. They did not: the scanner, the endpoint hunt and every quick
/// verification passed `None`, while the connection resolved ECH and used it.
/// So the scanner blessed gateways the connection could not use, and the
/// failure arrived long after the check that was supposed to prevent it -- on
/// exactly the networks where ECH is the tactic that gets through.
///
/// Resolved once because it is a DNS lookup, and held briefly so that probe and
/// connect cannot disagree merely because the lookup succeeded once and failed
/// once. Short enough that a rotated configuration is picked up on the next
/// connect rather than the next launch.
///
/// Nothing on the HTTP/2 framing: that transport carries no ECH, so fetching
/// one there is a DNS round trip spent on something nothing reads.
pub async fn attempt_ech() -> Option<Vec<u8>> {
    if masque_h2::enabled() {
        return None;
    }

    let mut held = ech_cache().lock().await;

    if let Some((at, value)) = held.as_ref() {
        if at.elapsed() < ECH_CACHE_FOR {
            return value.clone();
        }
    }

    let resolved = resolve_ech().await;
    *held = Some((std::time::Instant::now(), resolved.clone()));
    resolved
}

/// How long one resolved ECHConfigList is reused across probes and the connect.
const ECH_CACHE_FOR: std::time::Duration = std::time::Duration::from_secs(600);

type EchCache = tokio::sync::Mutex<Option<(std::time::Instant, Option<Vec<u8>>)>>;

fn ech_cache() -> &'static EchCache {
    static CACHED: std::sync::OnceLock<EchCache> = std::sync::OnceLock::new();
    CACHED.get_or_init(|| tokio::sync::Mutex::new(None))
}

/// Forgets the resolved ECHConfigList, so a test can set the policy and ask again.
#[cfg(test)]
async fn forget_ech() {
    *ech_cache().lock().await = None;
}

/// Whether ECH is a requirement rather than a preference.
///
/// `off` (or unset) never asks for one; `auto` asks and carries on without when
/// the answer does not come; `require` refuses to connect instead. The third is
/// the only one that keeps a promise: without it a resolver that quietly fails
/// downgrades a user who asked for their SNI to be hidden, and tells them
/// nothing.
fn ech_required() -> bool {
    matches!(
        std::env::var("AETHER_ECH").as_deref(),
        Ok(v) if v.eq_ignore_ascii_case("require")
    )
}

/// Fails when ECH was required and could not be had.
///
/// Checked once, where a connect begins, rather than at each of the places that
/// would otherwise carry on regardless.
pub async fn ech_policy_satisfied() -> Result<()> {
    if !ech_required() || attempt_ech().await.is_some() {
        return Ok(());
    }
    Err(AetherError::Ech(if masque_h2::enabled() {
        "ECH was required, and the HTTP/2 framing does not carry it; choose H3 or set          AETHER_ECH=auto"
            .into()
    } else {
        "ECH was required and no ECHConfigList could be resolved; refusing to send the SNI in          cleartext"
            .into()
    }))
}

/// The shape this attempt is being made with.
///
/// Built from what the engine is actually about to do, so a remembered endpoint
/// can be judged against it rather than offered to every attempt regardless.
fn attempt_proof(transport: &str) -> lastconn::Proof {
    lastconn::Proof {
        transport: transport.to_string(),
        profile: std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "firewall".to_string()),
        fragment_tls: std::env::var("AETHER_MASQUE_H2_FRAGMENT").is_ok(),
        ech: !matches!(
            std::env::var("AETHER_ECH").as_deref(),
            Err(_) | Ok("") | Ok("off")
        ),
    }
}

/// The framing a MASQUE attempt is using, as the cache names it.
fn masque_framing() -> &'static str {
    if masque_h2::enabled() {
        "h2"
    } else {
        "h3"
    }
}

/// Forwards a readiness signal, and records the endpoint that earned it.
///
/// The cache used to be written the moment an endpoint was *chosen*, which is
/// before anything has gone through it -- so an address that passed every check
/// and then failed to build a session was stored as the last good one, and the
/// next connect led with it. This fires instead when the tunnel has confirmed
/// end-to-end data, which is the only moment the word "good" is earned.
fn record_on_ready(
    path: String,
    peer: SocketAddr,
    proof: lastconn::Proof,
    onward: &mut Option<tokio::sync::oneshot::Sender<()>>,
) -> Option<tokio::sync::oneshot::Sender<()>> {
    let forward = onward.take();
    let (tx, rx) = tokio::sync::oneshot::channel();
    tokio::spawn(async move {
        // Dropped without firing means the tunnel never carried anything, and
        // there is nothing to remember.
        if rx.await.is_err() {
            return;
        }
        lastconn::save(&path, &peer.to_string(), &proof);
        log::debug!("[+] remembered {peer} as an endpoint that carried traffic");
        if let Some(tx) = forward {
            let _ = tx.send(());
        }
    });
    Some(tx)
}

/// The control channel of the QUIC tunnel that is running, if one is.
///
/// A migration has to be asked for from outside: the phone is what notices it
/// has changed network, and the tunnel is several layers below anything that
/// hears about it. quiche has done the work -- probe the new path, move the
/// source -- and nothing has ever called it on Android, so a roam costs a
/// reconnect and the endpoint search that comes with it.
///
/// Only the QUIC path has one. H2 rides TCP and a moved interface closes the
/// connection outright; WireGuard rebinds by its own route.
fn tunnel_control() -> &'static parking_lot::Mutex<Option<tokio::sync::mpsc::Sender<quic::Control>>>
{
    static CONTROL: std::sync::OnceLock<
        parking_lot::Mutex<Option<tokio::sync::mpsc::Sender<quic::Control>>>,
    > = std::sync::OnceLock::new();
    CONTROL.get_or_init(|| parking_lot::Mutex::new(None))
}

/// Holds the channel for as long as the tunnel that owns it runs.
struct TunnelControlGuard;

impl Drop for TunnelControlGuard {
    fn drop(&mut self) {
        *tunnel_control().lock() = None;
    }
}

fn hold_tunnel_control(ctrl_tx: &tokio::sync::mpsc::Sender<quic::Control>) -> TunnelControlGuard {
    *tunnel_control().lock() = Some(ctrl_tx.clone());
    TunnelControlGuard
}

/// Moves the running tunnel to a socket on the network the phone is on now.
///
/// Answers whether there was anything to ask. A reconnect is the fallback and
/// the caller decides that; this only reports that no QUIC tunnel is running,
/// which is the ordinary case for H2 and WireGuard.
pub fn migrate_running_tunnel() -> bool {
    let held = tunnel_control().lock().clone();
    match held {
        Some(ctrl) => match ctrl.try_send(quic::Control::Migrate) {
            Ok(()) => {
                log::info!("[+] asking the tunnel to move to the network this phone is on now");
                true
            }
            Err(error) => {
                log::debug!("[-] could not ask the tunnel to migrate: {error}");
                false
            }
        },
        None => false,
    }
}

async fn masque_probe(
    identity: &account::Identity,
    ip: prober::IpScan,
) -> Result<prober::MasqueProbe> {
    Ok(prober::MasqueProbe {
        sni: quic::masque_sni()?,
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: std::sync::Arc::from(identity.cert_pem.clone()),
        key_pem: std::sync::Arc::from(identity.key_pem.clone()),
        ech_config_list: attempt_ech().await.map(std::sync::Arc::from),
        noize: noize_config(),
        ports: prober::MASQUE_PORTS.to_vec(),
        ip,
        local_ipv4: parse_local_v4(&identity.ipv4),
    })
}

/// The parameters [`verify_masque_peer`] would dial with, without dialling.
#[cfg(test)]
async fn verify_params_for_test(
    identity: &account::Identity,
    peer: SocketAddr,
) -> quic::VerifyParams {
    quic::VerifyParams {
        peer,
        sni: quic::masque_sni().expect("valid test SNI"),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: identity.cert_pem.clone(),
        key_pem: identity.key_pem.clone(),
        ech_config_list: attempt_ech().await,
        noize: noize_config(),
        timeout: std::time::Duration::from_secs(5),
        local_ipv4: parse_local_v4(&identity.ipv4),
    }
}

async fn verify_masque_peer(
    identity: &account::Identity,
    peer: SocketAddr,
) -> Result<std::time::Duration> {
    verify_masque_peer_with_ech(identity, peer, attempt_ech().await).await
}

async fn verify_masque_peer_with_ech(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
) -> Result<std::time::Duration> {
    let vp = quic::VerifyParams {
        peer,
        sni: quic::masque_sni()?,
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: identity.cert_pem.clone(),
        key_pem: identity.key_pem.clone(),
        ech_config_list: ech.clone(),
        noize: noize_config(),
        timeout: std::time::Duration::from_secs(5),
        local_ipv4: parse_local_v4(&identity.ipv4),
    };

    if masque_h2::enabled() {
        let cfg = masque_h2::H2TunnelConfig {
            peer: masque_h2::h2_peer(peer),
            sni: quic::masque_sni()?,
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: true,
            pin_endpoint: true,
            expected_pins: consts::masque_pins().iter().map(|p| p.to_vec()).collect(),
            ech_config_list: ech,
        };
        return masque_h2::verify_h2(&cfg, std::time::Duration::from_secs(5))
            .await
            .map_err(|error| {
                AetherError::Other(format!("HTTP/2 endpoint validation failed: {error}"))
            });
    }

    quic::verify_masque(&vp)
        .await
        .map_err(|error| AetherError::Other(format!("HTTP/3 endpoint validation failed: {error}")))
}

/// The framings to try for the inner hop, in order.
///
/// The profile's own choice first, because a user who picked one picked it for
/// a reason, then the other. Only the inner hop gets two passes: the outer hop
/// has already reached an edge, so its framing is known to work on this
/// network and there is nothing to learn by changing it.
fn inner_framings(profile_h2: bool) -> [bool; 2] {
    [profile_h2, !profile_h2]
}

/// The inner edges of a nested pair, in the order they should be tried.
///
/// The endpoints Cloudflare named for the *inner* device come first, then the
/// derived neighbours. The derivation is a guess -- it keeps the outer edge's
/// first three octets and randomises the last -- and a guess at a Cloudflare
/// address lands on an ordinary edge that does not serve MASQUE for this
/// device, which closes the handshake with TLS alert 40. Six of those in a row
/// is what "MIM does not connect" looked like, on a network where the outer hop
/// had just been validated end to end.
///
/// It is the same defect as the one fixed for the single-hop path: the server
/// names the address in every registration answer and the engine went looking
/// for one instead. The fix did not reach here because the inner edge was
/// derived rather than discovered, so nothing in the nested path ever asked.
///
/// The outer edge's own address is dropped, because nesting a hop onto the edge
/// it is already tunnelled through is not a second hop.
///
/// Each candidate carries where it came from, and the log says so. Without
/// that, a run in which every candidate failed looked identical whether the
/// assigned endpoint had been tried first or not — which is exactly the
/// question a failing report needs answered, and a log that cannot answer it
/// costs another round trip to the person holding the phone.
fn order_inner_candidates(
    assigned: Vec<SocketAddr>,
    outer: SocketAddr,
    derived: Vec<SocketAddr>,
) -> Vec<InnerEdge> {
    let mut out: Vec<InnerEdge> = Vec::new();
    let named = assigned.len();
    for (index, addr) in assigned.into_iter().chain(derived).enumerate() {
        if addr.ip() == outer.ip() || out.iter().any(|seen| seen.addr == addr) {
            continue;
        }
        out.push(InnerEdge {
            addr,
            origin: if index < named {
                EdgeOrigin::Assigned
            } else {
                EdgeOrigin::Derived
            },
        });
    }
    out
}

/// An inner edge to try, and where the address came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct InnerEdge {
    addr: SocketAddr,
    origin: EdgeOrigin,
}

/// Where an inner edge's address came from, which the log prints.
///
/// Three different things, and a failure means something different for each:
/// a refused *assigned* edge is Cloudflare declining this device, a refused
/// *guess* is an address that was never ours, and a refused *pinned* one is the
/// user's own answer not working.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum EdgeOrigin {
    Assigned,
    Derived,
    Pinned,
}

impl EdgeOrigin {
    fn label(self) -> &'static str {
        match self {
            EdgeOrigin::Assigned => "assigned to this device",
            EdgeOrigin::Derived => "guessed near the outer edge",
            EdgeOrigin::Pinned => "pinned by you",
        }
    }
}

/// How long the whole hunt may take before it gives up and says so.
///
/// Five obfuscation profiles, each scanning the pool for its own full budget,
/// added up to the better part of seven minutes before one attempt reported
/// failure -- and the service then retried the whole thing. From outside that is
/// indistinguishable from a hang, and the user has no way to tell whether
/// waiting longer would help. Bounded, so the answer arrives while they are
/// still looking at it.
fn wg_hunt_budget() -> std::time::Duration {
    std::env::var("AETHER_WG_HUNT_BUDGET_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .map(std::time::Duration::from_secs)
        .unwrap_or_else(|| std::time::Duration::from_secs(210))
}

/// Tries Cloudflare's documented WireGuard endpoints under every obfuscation
/// profile, before any sampling.
///
/// The sweep below finishes an entire pool scan under one profile before trying
/// the next, so an endpoint that answers only under a later profile is reached
/// after every profile ahead of it has spent its full budget. The anchors are a
/// few dozen probes; running them across all profiles first turns the ordinary
/// case from minutes into seconds, and costs nothing when they are blocked.
async fn hunt_wg_anchors(
    identity: &account::Identity,
    candidates: &[(String, aethernoize::AetherNoizeConfig)],
    ip: prober::IpScan,
) -> Option<(SocketAddr, aethernoize::AetherNoizeConfig, String)> {
    use futures::StreamExt;

    let private_key = identity.private_key_bytes().ok()?;
    let peer_public = identity.peer_public_key_bytes().ok()?;
    let local_ipv4: std::net::Ipv4Addr = identity.ipv4.parse().ok()?;

    let mut anchors: Vec<IpAddr> = Vec::new();
    if ip.want_v4() {
        anchors.extend(
            wireguard::wg_seeds_v4()
                .iter()
                .filter_map(|s| s.parse::<Ipv4Addr>().ok())
                .map(IpAddr::V4),
        );
    }
    if ip.want_v6() {
        anchors.extend(
            wireguard::WG_SEEDS_V6
                .iter()
                .filter_map(|s| s.parse::<std::net::Ipv6Addr>().ok())
                .map(IpAddr::V6),
        );
    }
    if anchors.is_empty() {
        return None;
    }

    let ports: Vec<u16> = wireguard::WG_PORTS.iter().copied().take(4).collect();

    // Every combination, flattened, so they can all be in flight at once. Run
    // one at a time this is nine anchors by four ports by five profiles at three
    // seconds each -- nine minutes of waiting on a network where none of them
    // answer, which is worse than the sweep it was meant to shortcut.
    let mut probes: Vec<(SocketAddr, &String, &aethernoize::AetherNoizeConfig)> = Vec::new();
    for (name, profile) in candidates {
        for port in &ports {
            for anchor in &anchors {
                probes.push((SocketAddr::new(*anchor, *port), name, profile));
            }
        }
    }

    let probes = interleave_groups(&probes, ports.len() * anchors.len());

    let concurrency = sysprofile::cap_concurrency(24);
    let deadline = Instant::now() + ANCHOR_BUDGET;
    log::info!(
        "[*] trying {} endpoints across {} profiles ({} probes), {} at a time",
        anchors.len() * ports.len(),
        candidates.len(),
        probes.len(),
        concurrency,
    );

    wg_prober::begin_probe_pass();
    let stream =
        futures::stream::iter(probes.into_iter().map(|(peer, name, profile)| async move {
            let reached = match wireguard::verify_endpoint(
                peer,
                private_key,
                peer_public,
                identity.client_id,
                local_ipv4,
                profile,
                ANCHOR_PROBE_TIMEOUT,
                None,
            )
            .await
            {
                Ok(_) => true,
                Err(error) => {
                    wg_prober::record_probe_failure(&error);
                    false
                }
            };
            (reached, peer, name, profile)
        }))
        .buffer_unordered(concurrency);
    tokio::pin!(stream);

    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            log::info!(
                "[-] documented endpoints did not answer in time ({}); sampling the pool",
                wg_prober::probe_pass_summary(),
            );
            return None;
        }
        match tokio::time::timeout(remaining, stream.next()).await {
            Err(_) | Ok(None) => break,
            Ok(Some((false, ..))) => continue,
            Ok(Some((true, peer, name, profile))) => {
                log::info!("[+] documented endpoint {peer} answered under profile '{name}'");
                return Some((peer, profile.clone(), name.clone()));
            }
        }
    }

    log::info!(
        "[-] no documented endpoint answered ({}); sampling the address pool",
        wg_prober::probe_pass_summary(),
    );
    None
}

/// Reorders equal-sized groups so they are sampled in parallel, not in turn.
///
/// The anchor pass is deliberately given less time than it needs -- it is a
/// shortcut, and every second it spends is one the real scan does not get. So
/// the deadline always cuts something. Laid out group by group, what it cuts is
/// not a slice off each profile: it is the whole of the last one, every time.
/// A network that answers only under the fifth obfuscation would never see it
/// tried, and would be indistinguishable from a network that answers under
/// none. Interleaved, the same deadline takes an even bite out of all of them.
fn interleave_groups<T: Copy>(items: &[T], stride: usize) -> Vec<T> {
    if stride == 0 {
        return items.to_vec();
    }
    let groups = items.len().div_ceil(stride);
    let mut out = Vec::with_capacity(items.len());
    for offset in 0..stride {
        for group in 0..groups {
            if let Some(item) = items.get(group * stride + offset) {
                out.push(*item);
            }
        }
    }
    out
}

/// How long the documented endpoints get before the pool sweep takes over.
///
/// They are a shortcut, not the search. Where they answer it is in a second or
/// two; where they are blocked, every extra second here is one the real scan
/// does not get.
const ANCHOR_BUDGET: std::time::Duration = std::time::Duration::from_secs(20);

const ANCHOR_PROBE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);

async fn hunt_wg_peer_sweep(
    identity: &account::Identity,
    candidates: &[(String, aethernoize::AetherNoizeConfig)],
    mode_str: &str,
    ip: prober::IpScan,
    excluded: &HashSet<SocketAddr>,
) -> Result<(SocketAddr, aethernoize::AetherNoizeConfig, String)> {
    let multi = candidates.len() > 1;
    for (name, profile) in candidates {
        log::info!(
            "[*] hunting for a working WireGuard endpoint (handshake + data-plane verification, aethernoize='{name}')"
        );
        match hunt_wg_peer_with_profile(identity, mode_str, ip, profile.clone(), excluded).await {
            Ok(peer) => {
                log::info!(
                    "[+] selected WireGuard endpoint {peer} using aethernoize profile '{name}'"
                );
                return Ok((peer, profile.clone(), name.clone()));
            }
            Err(e) => {
                if multi {
                    log::warn!("[-] profile '{name}' found no data-plane endpoint: {e}; trying next profile");
                } else {
                    log::warn!("[-] profile '{name}' found no data-plane endpoint: {e}");
                }
            }
        }
    }
    Err(no_wireguard_endpoint())
}

/// Named rather than generic. WireGuard is UDP, and a network that blocks UDP
/// outright cannot be scanned around -- telling the user to keep waiting would
/// be advice that never comes good.
fn no_wireguard_endpoint() -> AetherError {
    AetherError::Other(
        "no WireGuard endpoint answered on this network. WireGuard needs UDP, which some \
         networks block entirely -- MASQUE H2 runs over TCP and works where it does."
            .into(),
    )
}

struct WgChannels {
    /// Packets arriving from the far end.
    inbound_rx: tokio::sync::mpsc::Receiver<Vec<u8>>,
    /// Packets to send to it.
    outbound_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    exit: TunnelExit,
}

async fn establish_wg_channels(
    identity: &account::Identity,
    peer: SocketAddr,
    obfuscate: bool,
    keepalive: u16,
    label: &'static str,
) -> Result<WgChannels> {
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;

    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;

    let profile = if obfuscate {
        aethernoize_config()
    } else {
        aethernoize::from_profile("off")
    };

    log::info!("[*] [{label}] validating WireGuard tunnel with {peer} (handshake + data-plane)...");
    let (_, session) = wireguard::verify_endpoint_keep_session(
        peer,
        private_key,
        peer_public,
        identity.client_id,
        ipv4,
        &profile,
        wg_tunnel_validate_timeout(),
        Some(keepalive),
    )
    .await
    .map_err(|e| AetherError::Other(format!("[{label}] tunnel failed validation: {e}")))?;
    log::info!("[+] [{label}] wireguard tunnel validated (end-to-end data confirmed)");

    let (outbound_tx, outbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());
    let (inbound_tx, inbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());

    let tunnel = wireguard::WgTunnel::from_established(
        session,
        std::sync::Arc::new(profile),
        inbound_tx,
        ipv4,
    );

    let exit = tokio::spawn(async move {
        match tunnel.run(outbound_rx).await {
            Ok(()) => {
                log::warn!("[-] [{label}] wireguard tunnel closed");
                Ok(())
            }
            Err(e) => {
                log::warn!("[-] [{label}] wireguard tunnel exited: {e}");
                Err(AetherError::Other(format!("[{label}] {e}")))
            }
        }
    });

    Ok(WgChannels {
        inbound_rx,
        outbound_tx,
        exit,
    })
}

#[cfg(test)]
mod identity_tests {
    use super::*;

    #[tokio::test]
    async fn cached_devices_do_not_repeat_warp_activation() {
        let dir = scratch("activation-held");
        let base = dir.join("aether.toml");
        let site = identity_site(base.to_str().unwrap(), identity::Slot::Wireguard);
        let identity = sample_identity("held-device");
        tokio::time::timeout(
            std::time::Duration::from_secs(1),
            enable_recorded_warp(&site, &identity, false),
        ).await.unwrap().unwrap();
        let pending = format!("{}.warp-enable-pending", site.path);
        std::fs::write(&pending, "different-device").unwrap();
        enable_recorded_warp(&site, &identity, false).await.unwrap();
        assert_eq!(std::fs::read_to_string(&pending).unwrap(), "different-device");
        std::fs::remove_dir_all(dir).unwrap();
    }

    /// Clears the environment these paths read, so a developer's own settings
    /// cannot decide whether the suite passes.
    fn isolated() {
        std::env::remove_var("AETHER_MASQUE_CONFIG");
        std::env::remove_var("AETHER_WG_CONFIG");
        std::env::remove_var("CF_TEAM");
        std::env::remove_var("AETHER_TEAM");
    }

    fn sample_identity(device: &str) -> account::Identity {
        account::Identity {
            // Upstream now records whether Cloudflare has refused this identity.
            refused: false,
            device_id: device.into(),
            access_token: "token".into(),
            cert_pem: b"-----BEGIN CERTIFICATE-----".to_vec(),
            key_pem: b"-----BEGIN PRIVATE KEY-----".to_vec(),
            cert_issued_at: 1_700_000_000,
            ipv4: "172.16.0.2".into(),
            ipv6: "2606:4700:110::1".into(),
            wg_private_key: [3u8; 32],
            wg_peer_public_key: [5u8; 32],
            client_id: [9, 8, 7],
            organization: String::new(),
            gateway_proxy: String::new(),
            assigned_endpoint: String::new(),
        }
    }

    /// A device that has never been enrolled, which is what a WireGuard slot
    /// holds. A certificate on one of those is a contradiction now: it is the
    /// mark of an enrolment, and an enrolment is what takes the WireGuard key
    /// away.
    fn wireguard_identity(device: &str) -> account::Identity {
        account::Identity {
            cert_pem: Vec::new(),
            key_pem: Vec::new(),
            cert_issued_at: 0,
            ..sample_identity(device)
        }
    }

    fn scratch(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("aether-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn an_exported_identity_comes_back_as_the_same_device() {
        isolated();
        let dir = scratch("export");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        config::save(base, &wireguard_identity("device-one")).unwrap();

        let payload = export_identity(base).unwrap();

        // The point of the whole feature: a reinstall starts here instead of at
        // a registration Cloudflare may refuse.
        let restored = scratch("import").join("aether.toml");
        let restored = restored.to_str().unwrap();
        import_identity(restored, &payload).unwrap();

        let identity = config::load(restored).unwrap().unwrap();
        assert_eq!("device-one", identity.device_id);
        assert_eq!([3u8; 32], identity.wg_private_key);
        assert_eq!([5u8; 32], identity.wg_peer_public_key);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_nested_tunnels_second_account_travels_too() {
        isolated();
        let dir = scratch("export-two");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        config::save(base, &wireguard_identity("outer")).unwrap();
        config::save(
            &derive_sibling_path(base, "secondary"),
            &wireguard_identity("inner"),
        )
        .unwrap();

        let payload = export_identity(base).unwrap();

        let target = scratch("import-two").join("aether.toml");
        let target = target.to_str().unwrap();
        import_identity(target, &payload).unwrap();

        // Leaving it behind would have the user buy it again on the first
        // WARP-in-WARP connect, which is the cost this exists to avoid.
        let inner = config::load(&derive_sibling_path(target, "secondary"))
            .unwrap()
            .expect("the second identity did not travel");
        assert_eq!("inner", inner.device_id);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The backup the default install could not take.
    ///
    /// The transport starts on MASQUE, so an install that was never switched to
    /// WireGuard has only `aether-masque.toml` -- and format 1 looked at
    /// `aether.toml` alone and reported that there was nothing to export. The
    /// one defence against losing a registration was missing from exactly the
    /// configuration almost everybody runs.
    #[test]
    fn an_install_that_has_only_ever_run_masque_can_be_backed_up() {
        isolated();
        let dir = scratch("export-masque-only");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        config::save(&masque_config_path(base), &sample_identity("masque-one")).unwrap();

        let payload = export_identity(base).expect("a masque install has an identity to export");

        let target = scratch("import-masque-only").join("aether.toml");
        let target = target.to_str().unwrap();
        import_identity(target, &payload).unwrap();

        assert_eq!(
            "masque-one",
            config::load(&masque_config_path(target))
                .unwrap()
                .expect("the masque identity did not travel")
                .device_id,
        );
        assert!(
            config::peek(target).is_none(),
            "a wireguard file was invented out of a masque backup",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// All four accounts travel, and land back in their own files.
    #[test]
    fn every_account_an_install_holds_travels_in_the_backup() {
        isolated();
        let dir = scratch("export-all");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        let masque = masque_config_path(base);

        config::save(base, &wireguard_identity("warp-outer")).unwrap();
        config::save(
            &derive_sibling_path(base, "secondary"),
            &wireguard_identity("warp-inner"),
        )
        .unwrap();
        config::save(&masque, &sample_identity("masque-outer")).unwrap();
        config::save(
            &derive_sibling_path(&masque, "secondary"),
            &sample_identity("masque-inner"),
        )
        .unwrap();

        let payload = export_identity(base).unwrap();

        let target = scratch("import-all").join("aether.toml");
        let target = target.to_str().unwrap();
        import_identity(target, &payload).unwrap();

        let target_masque = masque_config_path(target);
        for (path, expected) in [
            (target.to_string(), "warp-outer"),
            (derive_sibling_path(target, "secondary"), "warp-inner"),
            (target_masque.clone(), "masque-outer"),
            (
                derive_sibling_path(&target_masque, "secondary"),
                "masque-inner",
            ),
        ] {
            assert_eq!(
                expected,
                config::load(&path)
                    .unwrap()
                    .unwrap_or_else(|| panic!("{expected} did not travel"))
                    .device_id,
            );
        }

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A backup written by an older build still restores.
    ///
    /// Someone exported before updating precisely because they were about to
    /// reinstall. Refusing their file on the way back in would waste the
    /// registration the backup existed to carry.
    #[test]
    fn a_backup_in_the_older_format_is_still_read() {
        isolated();
        let dir = scratch("import-v1");
        let target = dir.join("aether.toml");
        let target = target.to_str().unwrap();

        let mut payload = String::from("version = 1\ndevice_id = \"old-backup\"\n\n[identity]\n");
        payload.push_str(&config::to_text(&wireguard_identity("old-backup")).unwrap());

        import_identity(target, &payload).expect("a format 1 backup must still import");
        assert_eq!(
            "old-backup",
            config::load(target).unwrap().unwrap().device_id,
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_install_with_no_identity_says_so_rather_than_exporting_nothing() {
        isolated();
        let dir = scratch("export-empty");
        let base = dir.join("aether.toml");
        let error = export_identity(base.to_str().unwrap()).unwrap_err();
        assert!(error.to_string().contains("no identity"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_that_is_not_an_identity_is_refused_before_anything_is_written() {
        isolated();
        let dir = scratch("import-junk");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        let existing = sample_identity("still-here");
        config::save(base, &existing).unwrap();

        for junk in ["", "hello", "version = 1", "{\"json\": true}"] {
            assert!(import_identity(base, junk).is_err(), "accepted {junk:?}");
        }

        // Half an import is worse than none: it would leave the device holding
        // an identity Cloudflare does not know, with the working one gone.
        assert_eq!(
            "still-here",
            config::load(base).unwrap().unwrap().device_id,
            "a refused import damaged the identity in use",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_from_a_future_format_is_refused_by_name() {
        isolated();
        let dir = scratch("import-future");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        config::save(base, &wireguard_identity("current")).unwrap();

        let payload = export_identity(base).unwrap().replace(
            &format!("version = {IDENTITY_EXPORT_VERSION}"),
            "version = 99",
        );
        let error = import_identity(base, &payload).unwrap_err().to_string();

        // Guessing at an unknown shape and writing the result over a working
        // identity is the one outcome worse than refusing.
        assert!(error.contains("different version"), "{error}");
        assert_eq!("current", config::load(base).unwrap().unwrap().device_id);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The arithmetic that made WireGuard look broken.
    ///
    /// Everything here is a bound the search has to satisfy for a user to get an
    /// answer while they are still looking at the screen. Each was violated at
    /// some point, and each failure looked like a hang rather than a refusal.
    #[test]
    fn the_documented_endpoint_pass_cannot_outlast_the_search_it_shortcuts() {
        let anchors = wireguard::wg_seeds_v4().len() + wireguard::WG_SEEDS_V6.len();
        let probes = anchors * 4 * 5; // four ports, five obfuscation profiles

        // Run one at a time this was nine minutes of waiting on a network where
        // none of them answer -- worse than the sweep it was meant to shortcut.
        let sequential = ANCHOR_PROBE_TIMEOUT * probes as u32;
        assert!(
            sequential > ANCHOR_BUDGET * 4,
            "the sequential cost is no longer worth bounding; check this still runs concurrently",
        );
        assert!(
            ANCHOR_BUDGET <= std::time::Duration::from_secs(30),
            "the shortcut may not become the search",
        );
    }

    /// What the deadline takes when it cannot take everything.
    #[test]
    fn a_truncated_anchor_pass_still_tries_every_obfuscation_profile() {
        // Five profiles, thirty-six endpoints each, in the order the pass
        // builds them: all of profile 0, then all of profile 1, and so on.
        let stride = 36;
        let profiles = 5;
        let probes: Vec<usize> = (0..stride * profiles).collect();
        let profile_of = |probe: usize| probe / stride;

        let ordered = interleave_groups(&probes, stride);

        assert_eq!(ordered.len(), probes.len(), "no probe may be dropped");
        let mut sorted = ordered.clone();
        sorted.sort_unstable();
        assert_eq!(sorted, probes, "no probe may be duplicated or invented");

        // Roughly what fits in the budget: twenty seconds, twenty-four at a
        // time, three seconds each.
        let reached = 20 / 3 * 24;
        let tried: Vec<usize> = ordered
            .iter()
            .take(reached)
            .map(|p| profile_of(*p))
            .collect();

        for profile in 0..profiles {
            let count = tried.iter().filter(|p| **p == profile).count();
            assert!(
                count > 0,
                "profile {profile} was never tried before the deadline; \
                 a network that only answers under it would look dead",
            );
        }

        // And evenly, not merely non-zero.
        let counts: Vec<usize> = (0..profiles)
            .map(|profile| tried.iter().filter(|p| **p == profile).count())
            .collect();
        let spread = counts.iter().max().unwrap() - counts.iter().min().unwrap();
        assert!(
            spread <= 1,
            "the profiles were sampled unevenly: {counts:?}"
        );
    }

    #[test]
    fn the_whole_search_is_bounded_by_something_a_user_would_wait_for() {
        let total = ANCHOR_BUDGET + wg_hunt_budget();
        assert!(
            total <= std::time::Duration::from_secs(300),
            "a search this long is indistinguishable from a hang: {total:?}",
        );
    }

    #[test]
    fn wireguard_searches_at_least_as_hard_as_masque_does() {
        // WireGuard covers fifty-four ports where MASQUE covers a handful, and
        // was given half the concurrency and two thirds the budget -- about one
        // candidate in forty before giving up. Failing was arithmetic, not
        // evidence that anything was blocked.
        let wg = wg_prober::WgScanMode::parse("balanced").strategy_for_test();
        let masque = prober::ScanMode::parse("balanced").strategy_for_test();
        assert!(
            wg.0 >= masque.0,
            "wireguard concurrency {} is below masque's {}",
            wg.0,
            masque.0,
        );
        assert!(
            wg.1 >= masque.1,
            "wireguard budget {:?} is below masque's {:?}",
            wg.1,
            masque.1,
        );
    }

    #[test]
    fn each_protocol_remembers_its_own_endpoint() {
        isolated();
        let base = "/data/aether.toml";
        // A MASQUE gateway and a WireGuard endpoint are different addresses on
        // different ports. Sharing this file would offer each the other's, and
        // waste a validation on every connect.
        assert_ne!(
            lastconn_path(&masque_config_path(base)),
            lastconn_path(&warp_config_path(base)),
        );
    }

    #[test]
    fn remembered_endpoints_keep_the_names_existing_installs_wrote() {
        isolated();
        let base = "/data/aether.toml";
        // Renaming these would not break anything visibly -- it would just
        // silently discard the cached endpoint and make the next connect scan
        // from scratch, which is the slow path this file exists to avoid.
        assert!(lastconn_path(&masque_config_path(base)).ends_with("aether-masque-lastconn.toml"));
        assert!(lastconn_path(&warp_config_path(base)).ends_with("aether-lastconn.toml"));
    }

    /// A wait that has not run out is served here, not spent at Cloudflare.
    ///
    /// The attempt is what costs the allowance, so an attempt made while the
    /// last one's wait is still running spends it to learn something already
    /// on disk. No network is reached in this test, which is the point.
    #[tokio::test]
    async fn a_registration_is_refused_here_while_the_wait_runs() {
        isolated();
        let dir = scratch("budget");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        let mut store = identity::Store::default();
        store.registration.failed(
            account::now_unix(),
            "registration: too many registrations from this address",
            Some(1_800),
        );
        identity::save(&store_path(base), &store).unwrap();

        let site = identity_site(base, identity::Slot::Wireguard);
        let error = load_or_provision_warp(&site).await.unwrap_err();

        assert!(
            matches!(error, AetherError::RegistrationOnHold { .. }),
            "expected the wait to be served here, got {error}",
        );
        assert!(
            error.to_string().contains("too many registrations"),
            "the reason Cloudflare gave has to survive: {error}",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A wait this device's address has earned does not hold back a
    /// registration that leaves from a carrier's.
    ///
    /// No network is reached: the rule is which wait applies, and the store
    /// is all it needs.
    #[test]
    fn a_hold_on_this_address_does_not_stop_a_registration_through_a_carrier() {
        let now = account::now_unix();
        let mut store = identity::Store::default();
        store.registration.failed(
            now,
            "registration: direct route -> timed out; camouflaged route -> timed out",
            None,
        );

        assert!(matches!(
            registration_may_go(&mut store, false, now),
            Err(AetherError::RegistrationOnHold { .. })
        ));
        assert!(registration_may_go(&mut store, true, now).is_ok());
    }

    /// And a wait earned through a carrier stays with that route.
    #[test]
    fn a_hold_through_a_carrier_is_kept_to_that_route() {
        let now = account::now_unix();
        let mut store = identity::Store::default();
        store.registration_via_proxy.failed(
            now,
            "registration: too many registrations from this address",
            Some(600),
        );

        let error = registration_may_go(&mut store, true, now).unwrap_err();
        assert!(
            error.to_string().contains("too many registrations"),
            "the reason Cloudflare gave has to survive: {error}",
        );
        assert!(registration_may_go(&mut store, false, now).is_ok());
    }

    /// A backup of an install 1.8.0 had already broken must not restore the break.
    ///
    /// One device in both halves, with the certificate on the MASQUE one: the
    /// WireGuard key it also carries was overwritten when that certificate was
    /// enrolled. Restoring it faithfully would hand the next install a key
    /// nothing answers, and the three-minute search that goes with it.
    #[test]
    fn a_backup_holding_one_device_in_both_families_is_repaired_on_the_way_in() {
        isolated();
        let dir = scratch("import-broken");
        let target = dir.join("aether.toml");
        let target = target.to_str().unwrap();

        let mut payload = format!(
            "version = {IDENTITY_EXPORT_VERSION}
device_id = \"dev-shared\"

[identity]
"
        );
        payload.push_str(&config::to_text(&wireguard_identity("dev-shared")).unwrap());
        payload.push_str(
            "
[masque]
",
        );
        payload.push_str(&config::to_text(&sample_identity("dev-shared")).unwrap());

        import_identity(target, &payload).unwrap();

        let site = identity_site(target, identity::Slot::Wireguard);
        let loaded = identity::load(&site.store_path, &site.legacy).unwrap();

        assert_eq!(
            Some("dev-shared"),
            loaded.store.device_id(identity::Slot::Masque),
            "the certificate is real and cost a registration; it is worth keeping",
        );
        assert_eq!(
            None,
            loaded.store.device_id(identity::Slot::Wireguard),
            "the wireguard half of that device is a key Cloudflare no longer holds",
        );
        assert!(
            config::peek(target).is_none(),
            "and it must not be written to the file an earlier build reads either",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The backup is taken from the store, not from the files beside it.
    #[test]
    fn the_backup_carries_what_the_engine_would_actually_dial_with() {
        isolated();
        let dir = scratch("export-from-store");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        let identity = wireguard_identity("dev-in-store");
        let mut store = identity::Store::default();
        store.put(
            "dev-in-store",
            identity::device_from(&identity, 1_789_000_000),
        );
        store
            .assign(identity::Slot::Wireguard, "dev-in-store")
            .unwrap();
        identity::save(&store_path(base), &store).unwrap();

        let payload = export_identity(base).expect("the store holds an identity");
        assert!(payload.contains("dev-in-store"), "{payload}");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Provisioning behind a carrier asks for nothing it already has.
    ///
    /// This runs while somebody else's tunnel is carrying the user's traffic,
    /// so the one thing it must not do is spend a registration to rediscover an
    /// identity that is already on disk -- or reach the network at all in that
    /// case, which is what makes this testable.
    #[tokio::test]
    async fn provisioning_behind_a_carrier_costs_nothing_when_the_identity_is_held() {
        isolated();
        let dir = scratch("provision-held");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        // Issued now, not at the fixture's fixed date. An expiring certificate
        // is a renewal, and a renewal is a request -- correct behaviour, and
        // not what this test is about.
        let identity = account::Identity {
            cert_issued_at: account::now_unix(),
            ..sample_identity("dev-masque")
        };
        let mut store = identity::Store::default();
        store.put(
            "dev-masque",
            identity::device_from(&identity, 1_789_000_000),
        );
        store.assign(identity::Slot::Masque, "dev-masque").unwrap();
        identity::save(&store_path(base), &store).unwrap();

        let config = EmbeddedConfig {
            config_path: base.to_string(),
            protocol: "masque".to_string(),
            listen: "127.0.0.1:0".parse().unwrap(),
            peer: None,
            peer_fallback: false,
            scan_mode: "balanced".to_string(),
            ip_scan: "v4".to_string(),
            access: socks::Access::default(),
        };
        let devices = provision_embedded(&config).await.unwrap();

        assert_eq!(vec!["dev-masque".to_string()], devices);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The store answers, and the older build's file is kept current.
    ///
    /// No network reaches this test, which is the point twice over: a usable
    /// device in the store is the whole answer, and the dual write beside it is
    /// what stops a user who goes back to 1.8.1 paying for a registration they
    /// already own.
    #[tokio::test]
    async fn a_device_in_the_store_is_used_without_asking_cloudflare() {
        isolated();
        let dir = scratch("store-answers");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        let identity = wireguard_identity("dev-wg");
        let mut store = identity::Store::default();
        store.put("dev-wg", identity::device_from(&identity, 1_789_000_000));
        store.assign(identity::Slot::Wireguard, "dev-wg").unwrap();
        identity::save(&store_path(base), &store).unwrap();

        let site = identity_site(base, identity::Slot::Wireguard);
        let found = load_or_provision_warp(&site).await.unwrap();

        assert_eq!("dev-wg", found.device_id);
        assert_eq!(
            "dev-wg",
            config::peek(&site.path)
                .expect("the file an earlier build reads was not written")
                .device_id,
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Adoption is gone, and its absence is the point.
    ///
    /// Filling MASQUE's file by copying WireGuard's saved a registration and
    /// cost the WireGuard key, because enrolling the copy revoked it on the
    /// device both files described. The identity store cannot express that --
    /// one device, one record, and no slot may hold another family's device --
    /// so each family registers its own account. The cost is the same as 1.8.1
    /// paid anyway: one extra registration, once, since there the WireGuard
    /// side re-provisioned instead.
    ///
    /// `identity::tests::migrating_an_install_broken_by_an_earlier_build_repairs_it`
    /// covers what happens to an install that was already adopted.
    #[test]
    fn nothing_adopts_another_protocols_identity_any_more() {
        isolated();
        let dir = scratch("no-adoption");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        // An install that has only ever run WireGuard, which is precisely what
        // adoption used to reach for.
        config::save(base, &wireguard_identity("wireguard-only")).unwrap();

        let legacy = identity_slots(base).to_vec();
        let loaded = identity::load(&store_path(base), &legacy).unwrap();

        assert_eq!(
            Some("wireguard-only"),
            loaded.store.device_id(identity::Slot::Wireguard),
        );
        assert_eq!(
            None,
            loaded.store.device_id(identity::Slot::Masque),
            "masque took over the wireguard device, which is what revokes its key",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The bug that shipped, in the form the code can check.
    #[test]
    fn the_two_protocol_families_never_share_an_identity_file() {
        isolated();
        // Enrolling MASQUE rewrites the device's key at Cloudflare and revokes
        // its WireGuard half. One file for both means connecting with MASQUE
        // silently destroys WireGuard for that install, and the endpoint search
        // then reports the network as dead.
        for base in ["/data/aether.toml", "/data/aether", "C:\\x\\aether.toml"] {
            assert_ne!(
                masque_config_path(base),
                warp_config_path(base),
                "masque and wireguard identities must live in separate files",
            );
        }
    }

    /// The bug that broke 1.8, in the form the code can check.
    ///
    /// Separate files were not enough. An install that had only ever run
    /// WireGuard has its identity *copied* into MASQUE's file and enrolled
    /// there, and the WireGuard file is never told -- so it goes on offering a
    /// key Cloudflare threw away, every endpoint answers with silence, and the
    /// search reports the network as dead. Downgrading does not help: the
    /// damage is on the account, and the older build reads the same file and
    /// reaches the same wrong conclusion.
    #[test]
    fn a_device_enrolled_in_another_file_is_not_a_wireguard_identity_any_more() {
        isolated();
        let dir = scratch("enrolled-elsewhere");
        let warp = dir.join("aether.toml");
        let warp = warp.to_str().unwrap();
        let masque = masque_config_path(warp);

        // What an install that only ever ran WireGuard looks like once MASQUE
        // has adopted its identity: the same device in both files, the
        // certificate only on MASQUE's.
        let mut wireguard_only = sample_identity("device-shared");
        wireguard_only.cert_pem = Vec::new();
        wireguard_only.key_pem = Vec::new();
        wireguard_only.cert_issued_at = 0;
        config::save(warp, &wireguard_only).unwrap();
        config::save(&masque, &sample_identity("device-shared")).unwrap();

        assert_eq!(
            Some(masque.clone()),
            device_enrolled_elsewhere(warp, "device-shared"),
            "the enrolment on the sibling has to be found from the wireguard file",
        );

        // And a different device in the same directory is somebody else's
        // enrolment, which says nothing about this one.
        assert_eq!(
            None,
            device_enrolled_elsewhere(warp, "some-other-device"),
            "an unrelated device must not be read as this one being revoked",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An expired certificate still means the WireGuard key is gone.
    ///
    /// The guard used to ask `has_masque_credentials`, which is false once the
    /// certificate is old enough to need renewing. Expiry is a statement about
    /// the MASQUE credential, not about the WireGuard key that enrolment
    /// overwrote a year earlier -- and reading it as one would hand the dead
    /// key back to the endpoint search.
    #[test]
    fn an_expired_certificate_still_marks_the_device_as_enrolled() {
        isolated();
        let dir = scratch("expired-mark");
        let warp = dir.join("aether.toml");
        let warp = warp.to_str().unwrap();
        let masque = masque_config_path(warp);

        let mut long_ago = sample_identity("device-old");
        long_ago.cert_issued_at = 1;
        config::save(&masque, &long_ago).unwrap();

        assert!(
            !long_ago.has_masque_credentials(),
            "this certificate is meant to be past renewal",
        );
        assert_eq!(
            Some(masque),
            device_enrolled_elsewhere(warp, "device-old"),
            "an expired certificate is still evidence of an enrolment",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The mark records the enrolment without spreading the private key.
    #[test]
    fn marking_a_sibling_writes_the_certificate_and_not_the_secret() {
        isolated();
        let dir = scratch("mark-sibling");
        let warp = dir.join("aether.toml");
        let warp = warp.to_str().unwrap();
        let masque = masque_config_path(warp);

        let mut wireguard_only = sample_identity("device-shared");
        wireguard_only.cert_pem = Vec::new();
        wireguard_only.key_pem = Vec::new();
        wireguard_only.cert_issued_at = 0;
        config::save(warp, &wireguard_only).unwrap();

        let enrolled = sample_identity("device-shared");
        config::save(&masque, &enrolled).unwrap();
        record_enrolment_beside(&masque, &enrolled);

        let marked = config::load(warp)
            .unwrap()
            .expect("the file is still there");
        assert!(
            !marked.cert_pem.is_empty(),
            "the wireguard file was not marked, so it will be used again",
        );
        assert!(
            marked.key_pem.is_empty(),
            "the private key does not belong in a file that will never present it",
        );
        assert_eq!(
            [3u8; 32], marked.wg_private_key,
            "marking must not disturb the rest of the file",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A sweep of the directory must not eat what is not an identity.
    ///
    /// `config::load` sets aside anything it cannot parse, and the cache of the
    /// last working endpoint lives right beside these files. Reading the
    /// directory with it would quarantine that cache on every connect, so the
    /// endpoint found last time would be searched for again from scratch.
    #[test]
    fn a_cache_beside_the_identities_is_left_alone() {
        isolated();
        let dir = scratch("sweep-safety");
        let warp = dir.join("aether.toml");
        let warp = warp.to_str().unwrap();
        config::save(warp, &sample_identity("device-one")).unwrap();

        let cache = dir.join("aether-lastconn.toml");
        std::fs::write(
            &cache,
            "peer = \"162.159.192.1:2408\"\nprofile = \"firewall\"\n",
        )
        .unwrap();

        let siblings = identity_siblings(warp);
        assert!(
            siblings.is_empty(),
            "a cache was read as an identity: {siblings:?}",
        );
        assert!(cache.exists(), "the cache was quarantined by a sweep");

        let _ = std::fs::remove_dir_all(&dir);
    }
}

/// What MASQUE enrollment does to the WireGuard half of the same registration.
///
/// These talk to Cloudflare and hold a real handshake open, so they are
/// `#[ignore]`d and additionally gated: a registration costs an entry against
/// the per-IP rate limit, and CI running this on every push would exhaust it
/// for everybody sharing the runner's address.
///
///     AETHER_LIVE_ENROLL_TEST=1 cargo test -p aether enrollment -- --ignored --nocapture
#[cfg(test)]
mod enrollment_tests {
    use super::*;
    use std::time::Duration;

    /// A documented WARP anchor. If a key is registered, this answers in well
    /// under a second from almost anywhere.
    const ANCHOR: &str = "162.159.192.1:2408";

    async fn shakes_hands(identity: &account::Identity) -> Result<Duration> {
        wireguard::verify_endpoint(
            ANCHOR.parse().unwrap(),
            identity.wg_private_key,
            identity.wg_peer_public_key,
            identity.client_id,
            identity
                .ipv4
                .parse()
                .map_err(|_| AetherError::Other("invalid ipv4".into()))?,
            &aethernoize::AetherNoizeConfig::off(),
            Duration::from_secs(6),
            None,
        )
        .await
    }

    /// Whether an identity a phone could not connect with works from here.
    ///
    /// This is the fork the diagnostics could not resolve. A device reports
    /// that no WireGuard endpoint anywhere answers; the same engine, the same
    /// anchors and a freshly made identity hand shake from a desktop in about a
    /// second. Either the credentials that device is holding are unusable, or
    /// they are fine and something on the device stops the packets being what
    /// Cloudflare expects. Those have nothing in common as fixes.
    ///
    /// Pull the file off the device and run it against the anchors from a
    /// machine known to work:
    ///
    ///     adb shell run-as <pkg> cat files/aether.toml > id.toml
    ///     AETHER_TEST_IDENTITY=id.toml cargo test -p aether identity_from -- \
    ///         --ignored --nocapture
    /// The 1.8.0 failure and its repair, end to end, against the live API.
    ///
    /// Everything else about this defect is checked on files. This checks the
    /// thing the files are about: that Cloudflare really does stop recognising
    /// the WireGuard key when the same device is enrolled for MASQUE, and that
    /// the engine now notices and replaces it instead of handing it back to an
    /// endpoint search that will never get an answer.
    ///
    /// Two registrations against the running address, which is why it is gated
    /// twice. Run it deliberately:
    ///
    ///     AETHER_LIVE_ENROLL_TEST=1 cargo test -p aether adopted_by_masque -- \
    ///         --ignored --nocapture
    #[tokio::test]
    #[ignore = "registers two real warp devices and waits out an edge propagation"]
    async fn a_wireguard_identity_adopted_by_masque_is_replaced_rather_than_reused() {
        if std::env::var("AETHER_LIVE_ENROLL_TEST").is_err() {
            eprintln!("set AETHER_LIVE_ENROLL_TEST=1 to run this");
            return;
        }
        for name in [
            "AETHER_MASQUE_CONFIG",
            "AETHER_WG_CONFIG",
            "AETHER_TEAM",
            "CF_TEAM",
        ] {
            std::env::remove_var(name);
        }

        let dir = std::env::temp_dir().join(format!("aether-repair-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        // An install that has only ever run WireGuard. Under the store this is
        // the slot rather than the file, which is the whole point of it -- and
        // the scenario below is the one the store makes unrepresentable.
        let warp_site = identity_site(base, identity::Slot::Wireguard);
        let first = load_or_provision_warp(&warp_site)
            .await
            .expect("the first registration");
        eprintln!("[test] wireguard device {}", first.device_id);
        assert!(
            shakes_hands(&first).await.is_ok(),
            "a freshly registered wireguard identity has to hand shake",
        );

        // The user taps a MASQUE profile. It adopts that identity rather than
        // paying for a second, and enrolling the adopted copy is what revokes
        // the WireGuard key on the device both files describe.
        let masque_site = identity_site(base, identity::Slot::Masque);
        let masque = load_or_provision_masque(&masque_site)
            .await
            .expect("the enrolment");
        assert_eq!(
            first.device_id, masque.device_id,
            "this scenario only exists when masque adopts rather than registers",
        );

        // The damage is not immediate: the change takes up to a minute to reach
        // the edge, which is long enough for a check made straight afterwards
        // to come back clean.
        tokio::time::sleep(Duration::from_secs(90)).await;
        assert!(
            shakes_hands(&first).await.is_err(),
            "cloudflare still answers the old key, so this test proves nothing yet",
        );

        // Back to WireGuard. In 1.8.0 this handed the revoked identity straight
        // back, and the endpoint search spent three minutes being met with
        // silence before reporting the network as dead.
        let second = load_or_provision_warp(&warp_site)
            .await
            .expect("the replacement registration");
        eprintln!("[test] replacement device {}", second.device_id);
        assert_ne!(
            first.device_id, second.device_id,
            "the revoked identity was handed back instead of replaced",
        );
        assert!(
            shakes_hands(&second).await.is_ok(),
            "the replacement has to be an identity that actually works",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    #[ignore = "needs an identity file pulled off a device"]
    async fn an_identity_from_a_device_hand_shakes_from_here() {
        let Ok(path) = std::env::var("AETHER_TEST_IDENTITY") else {
            eprintln!("set AETHER_TEST_IDENTITY to a device's aether.toml");
            return;
        };

        let identity = config::load(&path)
            .expect("read the identity file")
            .expect("the file held an identity");

        eprintln!("[test] device {}", identity.device_id);
        eprintln!("[test] ipv4 {}", identity.ipv4);
        eprintln!("[test] client_id {:?}", identity.client_id);
        eprintln!("[test] assigned endpoint {:?}", identity.assigned_endpoint);
        eprintln!(
            "[test] has masque credentials: {}",
            identity.has_masque_credentials()
        );

        // Both framings, because the engine tries a plain handshake alongside
        // the obfuscated ones and a difference between them is itself a result.
        let profiles = [
            ("off", aethernoize::AetherNoizeConfig::off()),
            ("firewall", aethernoize::from_profile("firewall")),
        ];

        let mut answered = Vec::new();
        for (name, profile) in &profiles {
            for seed in wireguard::wg_seeds_v4().iter().take(4) {
                for port in wireguard::WG_PORTS.iter().copied().take(2) {
                    let peer: SocketAddr = format!("{seed}:{port}").parse().unwrap();
                    let result = wireguard::verify_endpoint(
                        peer,
                        identity.wg_private_key,
                        identity.wg_peer_public_key,
                        identity.client_id,
                        identity.ipv4.parse().expect("ipv4"),
                        profile,
                        std::time::Duration::from_secs(4),
                        None,
                    )
                    .await;
                    match result {
                        Ok(rtt) => {
                            eprintln!("[test] {name:9} {peer} -> OK {rtt:?}");
                            answered.push((name.to_string(), peer));
                        }
                        Err(error) => eprintln!("[test] {name:9} {peer} -> {error}"),
                    }
                }
            }
        }

        assert!(
            !answered.is_empty(),
            "this identity got no answer from any anchor even from a machine \
             where a fresh one works, so the credentials are the problem, not \
             the device's network",
        );
        eprintln!("[test] {} endpoints answered", answered.len());
    }

    /// Enrolling MASQUE revokes the WireGuard key on the same account.
    ///
    /// Both write the same `key` field on the same device record: registration
    /// puts a Curve25519 public key there with `tunnel_type: wireguard`, and
    /// `enroll_key` PATCHes it to a secp256r1 SPKI with `tunnel_type: masque`.
    /// Afterwards Cloudflare has no WireGuard key for the device, and WireGuard
    /// meets an unrecognised peer with silence rather than a rejection -- so
    /// every endpoint everywhere stops answering, and the endpoint search
    /// reports the network as dead when the credentials are what died.
    ///
    /// The delay is the reason this needed a test rather than a reading of the
    /// source. The old key keeps working for roughly thirty to sixty seconds
    /// after the PATCH, so a check made straight afterwards comes back clean
    /// and the sharing looks safe. It is not, and a released version shipped
    /// believing it was.
    ///
    /// If this ever starts failing, Cloudflare has changed the model and the
    /// two protocols could share one registration again -- which would halve
    /// what a user spends against a per-IP rate limit, and is worth taking.
    async fn wireguard_dies_when_masque_is_enrolled_on_the_same_account() {
        if std::env::var("AETHER_LIVE_ENROLL_TEST").is_err() {
            eprintln!("set AETHER_LIVE_ENROLL_TEST=1 to run this");
            return;
        }

        let identity = account::provision_wg(consts::DEFAULT_MODEL, consts::DEFAULT_LOCALE, None)
            .await
            .expect("provision a fresh wireguard registration");
        eprintln!("[test] registered device {}", identity.device_id);

        let before = shakes_hands(&identity).await;
        eprintln!("[test] handshake before enrollment: {before:?}");
        assert!(
            before.is_ok(),
            "a freshly registered key should hand shake with {ANCHOR}; got {before:?}. \
             If this fails the network is blocking UDP and the test proves nothing.",
        );

        let keypair = account::generate_masque_keypair().expect("masque keypair");
        account::enroll_key(
            &identity.device_id,
            &identity.access_token,
            &keypair.spki_der,
            None,
        )
        .await
        .expect("enroll the masque key");
        eprintln!("[test] enrolled a masque key on the same device record");

        // Not immediately. Cloudflare does not propagate a key change to its
        // edge synchronously, so asking straight away gets an answer from an
        // edge that has not heard yet -- which is exactly how an earlier
        // version of this test concluded enrollment was harmless, and sent the
        // investigation off after the network instead.
        let mut after = shakes_hands(&identity).await;
        for elapsed in [0u64, 30, 60, 90, 120] {
            if elapsed > 0 {
                tokio::time::sleep(Duration::from_secs(30)).await;
                after = shakes_hands(&identity).await;
            }
            eprintln!("[test] handshake {elapsed:>3}s after enrollment: {after:?}");
        }

        assert!(
            after.is_err(),
            "enrolling a MASQUE key left the WireGuard key working. Cloudflare has \
             changed the model: the two families could share one registration \
             again, halving what a user spends against the per-IP rate limit. \
             Re-check before taking it -- the revocation used to take up to a \
             minute to reach the edge; got {after:?}",
        );
    }
}

/// What the checks and the connection agree about, and what the policy allows.
#[cfg(test)]
mod ech_tests {
    use super::*;

    /// Serialised: these set process-wide variables and share one cache.
    fn one_at_a_time() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
        LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    async fn with_policy(value: Option<&str>, h2: bool) {
        forget_ech().await;
        match value {
            Some(v) => std::env::set_var("AETHER_ECH", v),
            None => std::env::remove_var("AETHER_ECH"),
        }
        if h2 {
            std::env::set_var("AETHER_MASQUE_HTTP2", "1");
        } else {
            std::env::remove_var("AETHER_MASQUE_HTTP2");
        }
    }

    /// The framing that cannot carry ECH does not go looking for one.
    ///
    /// It was fetched regardless, which is a DNS round trip on the way to a
    /// connect, spent on something no part of the H2 path reads.
    #[tokio::test]
    async fn the_http2_framing_does_not_fetch_a_configuration_it_cannot_use() {
        let _serial = one_at_a_time();
        with_policy(Some("auto"), true).await;
        assert!(attempt_ech().await.is_none());
        with_policy(None, false).await;
    }

    /// Off is the default, and off means nothing is resolved.
    #[tokio::test]
    async fn ech_is_off_unless_it_is_asked_for() {
        let _serial = one_at_a_time();
        with_policy(None, false).await;
        assert!(attempt_ech().await.is_none());
        with_policy(Some("off"), false).await;
        assert!(attempt_ech().await.is_none());
        assert!(ech_policy_satisfied().await.is_ok());
    }

    /// Required means refused, not quietly downgraded.
    ///
    /// Someone who asks for their SNI to be hidden and is silently given a
    /// connection that sends it in the clear has been told nothing, which is the
    /// one outcome worse than failing.
    #[tokio::test]
    async fn requiring_ech_on_a_framing_that_has_none_is_refused_by_name() {
        let _serial = one_at_a_time();
        with_policy(Some("require"), true).await;

        let refused = ech_policy_satisfied().await.unwrap_err().to_string();
        assert!(refused.contains("HTTP/2"), "{refused}");

        with_policy(None, false).await;
    }

    /// The checks and the connection read the same value.
    ///
    /// The defect this exists for: the scanner, the endpoint hunt and every
    /// quick verification passed `None` while the connection resolved ECH and
    /// used it -- so an endpoint could pass validation and then fail to carry a
    /// session, and the failure arrived nowhere near the check meant to prevent
    /// it. One resolver, held for the attempt, is what makes them agree.
    #[tokio::test]
    async fn every_path_reads_one_resolved_value() {
        let _serial = one_at_a_time();
        // A configuration given directly, so the value is real and no lookup is
        // needed: with ECH off everything is None and this test would pass
        // against the defect it exists to catch.
        with_policy(Some("q83vAAAA"), false).await;

        let first = attempt_ech().await;
        assert!(first.is_some(), "the test needs a value to compare");
        let second = attempt_ech().await;
        assert_eq!(first, second);

        let identity = account::Identity {
            device_id: "d".into(),
            access_token: "t".into(),
            cert_pem: b"c".to_vec(),
            key_pem: b"k".to_vec(),
            cert_issued_at: 0,
            ipv4: "172.16.0.2".into(),
            ipv6: "2606:4700:110::1".into(),
            wg_private_key: [1u8; 32],
            wg_peer_public_key: [2u8; 32],
            client_id: [0, 0, 0],
            organization: String::new(),
            gateway_proxy: String::new(),
            assigned_endpoint: String::new(),
            refused: false,
        };
        let probe = masque_probe(&identity, prober::IpScan::V4).await.unwrap();
        assert_eq!(
            first.clone().map(std::sync::Arc::from),
            probe.ech_config_list,
            "the scanner has to probe with what the connection will dial with",
        );

        // And the quick check every assigned endpoint, cached endpoint and
        // custom endpoint goes through reads it too.
        let checked = verify_params_for_test(&identity, "162.159.198.2:443".parse().unwrap()).await;
        assert_eq!(first, checked.ech_config_list);

        with_policy(None, false).await;
    }
}

/// Why a MASQUE probe fails, made visible.
///
/// The prober turns every failure into `None` at trace level, and the Android
/// logger is capped at info -- so several thousand probes failing for one
/// common reason and several thousand failing because the network is hostile
/// look identical from outside: `no clean endpoint found`. This asks a handful
/// of documented gateways directly and prints what actually came back, with the
/// certificate pins on and then off.
///
///     AETHER_LIVE_PROBE_TEST=1 cargo test -p aether why_masque -- --ignored --nocapture
#[cfg(test)]
mod masque_reachability_tests {
    use super::*;
    use std::time::Duration;

    #[tokio::test]
    #[ignore = "probes the live cloudflare edge from this network"]
    async fn why_masque_probes_fail_here() {
        if std::env::var("AETHER_LIVE_PROBE_TEST").is_err() {
            eprintln!("set AETHER_LIVE_PROBE_TEST=1 to run this");
            return;
        }
        for name in [
            "AETHER_MASQUE_CONFIG",
            "AETHER_WG_CONFIG",
            "AETHER_TEAM",
            "CF_TEAM",
        ] {
            std::env::remove_var(name);
        }

        let dir = std::env::temp_dir().join(format!("aether-probe-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        std::env::set_var("AETHER_MASQUE_HTTP2", "1");
        let identity = load_or_provision_masque(&identity_site(base, identity::Slot::Masque))
            .await
            .expect("a masque identity");
        eprintln!("[test] identity device {}", identity.device_id);
        // The enrolment reaches the edge in something under a minute, so a
        // probe made straight afterwards can be refused for a reason that has
        // nothing to do with the endpoint.
        eprintln!("[test] waiting 90s for the enrolment to reach the edge");
        tokio::time::sleep(Duration::from_secs(90)).await;

        // What Cloudflare itself says about this device. AccountData drops every
        // field it was not written to know about, and the question here is
        // exactly whether there is a field we are not reading.
        let raw = reqwest::Client::builder()
            .user_agent(consts::UA_REGISTER)
            .timeout(Duration::from_secs(20))
            .build()
            .unwrap()
            .get(format!(
                "{}/{}/reg/{}",
                format!("https://{}", consts::API_HOST),
                consts::API_VERSION,
                identity.device_id
            ))
            .header("CF-Client-Version", consts::CF_CLIENT_VERSION)
            .bearer_auth(&identity.access_token)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        eprintln!(
            "[test] registration says:
{raw}"
        );

        // The endpoint Cloudflare assigned this device, which is the one the
        // engine should be trying first and currently never tries at all.
        let reg = account::fetch_device(&identity.device_id, &identity.access_token)
            .await
            .expect("the device record");
        let assigned = account::endpoint_from(&reg);
        eprintln!("[test] cloudflare assigned {assigned}");
        let assigned_443 = format!("{assigned}:443");

        // The selection the engine now makes, which is the fix this proves.
        let chosen = assigned_masque_peers(&identity).await;
        eprintln!("[test] engine would try {chosen:?} before searching");
        assert!(
            chosen.contains(&assigned_443.parse::<SocketAddr>().unwrap()),
            "the endpoint Cloudflare names has to be among the ones tried first: {chosen:?}",
        );

        let targets = [
            assigned_443.as_str(),
            // A control: an address from the hard-coded pool that the scan
            // spends its budget on.
            "162.159.192.1:443",
        ];

        for pinned in [true, false] {
            eprintln!("\n[test] ---- pin_endpoint = {pinned} ----");
            for target in targets {
                let cfg = masque_h2::H2TunnelConfig {
                    peer: target.parse().unwrap(),
                    sni: consts::CONNECT_SNI.to_string(),
                    authority: quic::default_authority().to_string(),
                    path: quic::default_path().to_string(),
                    cert_pem: identity.cert_pem.clone(),
                    key_pem: identity.key_pem.clone(),
                    local_ipv4: parse_local_v4(&identity.ipv4),
                    quiet: true,
                    pin_endpoint: pinned,
                    expected_pins: consts::masque_pins().iter().map(|p| p.to_vec()).collect(),
                    ech_config_list: None,
                };
                match masque_h2::verify_h2(&cfg, Duration::from_secs(6)).await {
                    Ok(rtt) => eprintln!("[test] {target} OK rtt={rtt:?}"),
                    Err(error) => eprintln!("[test] {target} FAILED {error}"),
                }
            }
        }

        eprintln!(
            "
[test] ---- h3 / quic ----"
        );
        for target in targets {
            let vp = quic::VerifyParams {
                peer: target.parse().unwrap(),
                sni: consts::CONNECT_SNI.to_string(),
                authority: quic::default_authority().to_string(),
                path: quic::default_path().to_string(),
                cert_pem: identity.cert_pem.clone(),
                key_pem: identity.key_pem.clone(),
                ech_config_list: None,
                noize: noize_config(),
                timeout: Duration::from_secs(6),
                local_ipv4: parse_local_v4(&identity.ipv4),
            };
            match quic::verify_masque(&vp).await {
                Ok(rtt) => eprintln!("[test] {target} OK rtt={rtt:?}"),
                Err(error) => eprintln!("[test] {target} FAILED {error}"),
            }
        }

        let _ = std::fs::remove_dir_all(&dir);
    }
}

/// Moving a running tunnel instead of rebuilding it.
#[cfg(test)]
mod migration_tests {
    use super::*;

    /// With nothing running, the answer is no rather than a failure.
    ///
    /// The caller reconnects on a no, which is what it always did -- so this
    /// being the ordinary answer for H2 and WireGuard has to cost nothing and
    /// say nothing alarming.
    #[test]
    fn asking_an_idle_engine_to_move_says_no() {
        *tunnel_control().lock() = None;
        assert!(!migrate_running_tunnel());
    }

    /// A running QUIC tunnel is asked, and hears it.
    #[tokio::test]
    async fn a_running_tunnel_is_asked_to_move() {
        let (tx, mut rx) = tokio::sync::mpsc::channel::<quic::Control>(4);
        let guard = hold_tunnel_control(&tx);

        assert!(migrate_running_tunnel());
        assert!(
            matches!(rx.try_recv(), Ok(quic::Control::Migrate)),
            "the tunnel was told something other than to migrate",
        );

        // And when the tunnel ends, nothing is left pointing at it: a later
        // roam must not be answered by a channel whose far end is gone.
        drop(guard);
        assert!(!migrate_running_tunnel());
    }

    /// A tunnel that has stopped reading is not mistaken for one that moved.
    ///
    /// try_send rather than send, because this runs on the phone's network
    /// callback and blocking there would hold up everything else. A full queue
    /// means the tunnel is not keeping up, and reconnecting is the better
    /// answer than waiting for room.
    #[tokio::test]
    async fn a_tunnel_that_is_not_listening_reports_no() {
        let (tx, rx) = tokio::sync::mpsc::channel::<quic::Control>(1);
        let _guard = hold_tunnel_control(&tx);
        drop(rx);

        assert!(!migrate_running_tunnel());
    }
}

async fn load_or_provision_warp_path(path: &str) -> Result<account::Identity> {
    load_or_provision_warp(&identity_site(path, identity::Slot::Wireguard)).await
}
async fn load_or_provision_masque_path(path: &str) -> Result<account::Identity> {
    load_or_provision_masque(&identity_site(path, identity::Slot::Masque)).await
}
