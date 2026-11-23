use std::collections::HashSet;

use std::net::SocketAddr;

use serde::{Deserialize, Serialize};

pub const RECENT_CAP: usize = 8;

pub const CARRIER_MASQUE_H3: &str = "masque-h3";

pub const CARRIER_MASQUE_H2: &str = "masque-h2";

pub const CARRIER_WIREGUARD: &str = "wireguard";

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct LastConnection {
    pub peer: String,
    /// Kept at the top level for the files earlier builds wrote.
    #[serde(default)]
    pub profile: String,
    #[serde(default)]
    pub proof: Proof,
    #[serde(default)]
    pub carrier: String,
    #[serde(default)]
    pub recent: Vec<String>,
}

pub fn load(path: &str) -> Option<LastConnection> {
    let text = std::fs::read_to_string(path).ok()?;
    toml::from_str(&text).ok()
}

/// Records an endpoint that has carried traffic, and what carried it.
///
/// Called when a tunnel confirms end-to-end data, never when one is merely
/// chosen. It used to be written the moment an endpoint was picked -- before
/// anything had gone through it -- so an address that passed every check and
/// then failed to build a session was stored as the last good one, and the next
/// connect led with it.
pub fn save(path: &str, peer: &str, proof: &Proof) {
    let carrier = match proof.transport.as_str() {
        "h2" => "masque-h2",
        "h3" => "masque-h3",
        "wg" => "wireguard",
        other => other,
    };
    let mut recent = vec![peer.to_string()];
    if let Some(previous) = load(path) {
        if previous.carrier.is_empty() || previous.carrier == carrier {
            for entry in std::iter::once(previous.peer).chain(previous.recent) {
                if !recent.contains(&entry) && recent.len() < 8 {
                    recent.push(entry);
                }
            }
        }
    }
    let conn = LastConnection {
        peer: peer.to_string(),
        profile: proof.profile.clone(),
        proof: proof.clone(),
        carrier: carrier.to_string(),
        recent,
    };
    match toml::to_string_pretty(&conn) {
        Ok(text) => {
            if let Err(e) = std::fs::write(path, text) {
                log::debug!("[lastconn] failed to save {path}: {e}");
            }
        }
        Err(e) => log::debug!("[lastconn] failed to encode: {e}"),
    }
}

pub fn usable_peers(cached: &LastConnection, carrier: &str) -> Vec<SocketAddr> {
    if !cached.carrier.is_empty() && cached.carrier != carrier {
        log::debug!(
            "[lastconn] ignoring gateways saved for {} while running {carrier}",
            cached.carrier
        );
        return Vec::new();
    }

    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for raw in std::iter::once(&cached.peer).chain(cached.recent.iter()) {
        if let Ok(addr) = raw.parse::<SocketAddr>() {
            if seen.insert(addr) {
                out.push(addr);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn successful_gateway_ring_is_bounded_and_deduplicated() {
        let path = std::env::temp_dir().join(format!("spicy-lastconn-ring-{}.toml", std::process::id()));
        let path = path.to_string_lossy().to_string();
        let _ = std::fs::remove_file(&path);
        for n in 1..=12 {
            save(&path, &format!("10.0.0.{n}:443"), &proof("h2", false, false));
        }
        save(&path, "10.0.0.12:443", &proof("h2", false, false));
        let cached = load(&path).unwrap();
        assert_eq!(cached.recent.len(), RECENT_CAP);
        assert_eq!(cached.recent[0], "10.0.0.12:443");
        assert_eq!(usable_peers(&cached, CARRIER_MASQUE_H2).len(), RECENT_CAP);
        assert!(usable_peers(&cached, CARRIER_WIREGUARD).is_empty());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn changing_carrier_drops_previous_gateways() {
        let path = std::env::temp_dir().join(format!("spicy-lastconn-carrier-{}.toml", std::process::id()));
        let path = path.to_string_lossy().to_string();
        let _ = std::fs::remove_file(&path);
        save(&path, "10.0.0.1:443", &proof("h2", false, false));
        save(&path, "10.0.0.2:443", &proof("wg", false, false));
        let cached = load(&path).unwrap();
        assert_eq!(cached.recent, vec!["10.0.0.2:443"]);
        assert_eq!(cached.proof.transport, "wg");
        let _ = std::fs::remove_file(path);
    }

    fn proof(transport: &str, fragment_tls: bool, ech: bool) -> Proof {
        Proof {
            transport: transport.to_string(),
            profile: "firewall".to_string(),
            fragment_tls,
            ech,
        }
    }

    fn remembered(proof: Proof) -> LastConnection {
        LastConnection {
            peer: "162.159.198.2:443".to_string(),
            profile: proof.profile.clone(),
            proof,
            ..Default::default()
        }
    }

    /// An endpoint proven over one framing is not a proof about the other.
    ///
    /// The cache is consulted whatever the attempt is made with, so without
    /// this a gateway that answered over TCP is led with on a QUIC attempt --
    /// and the check that finds out costs five seconds at the front of every
    /// connect.
    #[test]
    fn a_memory_only_applies_to_the_shape_that_earned_it() {
        let over_h2 = remembered(proof("h2", false, false));

        assert!(over_h2.applies_to(&proof("h2", false, false)));
        assert!(!over_h2.applies_to(&proof("h3", false, false)));
        assert!(!over_h2.applies_to(&proof("h2", true, false)));
        assert!(!over_h2.applies_to(&proof("h2", false, true)));
    }

    /// A file from before proofs were recorded is still worth reading.
    ///
    /// Refusing it would throw away every existing install's cache on the first
    /// connect after updating, to save a single check.
    #[test]
    fn a_file_written_by_an_earlier_build_is_still_used() {
        let old = LastConnection {
            peer: "162.159.198.2:443".to_string(),
            profile: "firewall".to_string(),
            proof: Proof::default(),
            ..Default::default()
        };
        assert!(old.applies_to(&proof("h3", true, true)));
    }

    /// The files earlier builds wrote still parse, and so do the ones this writes.
    #[test]
    fn both_shapes_of_file_round_trip() {
        let old: LastConnection =
            toml::from_str("peer = \"1.2.3.4:443\"\nprofile = \"gfw\"\n").expect("the old shape");
        assert_eq!("1.2.3.4:443", old.peer);
        assert_eq!("gfw", old.profile);
        assert_eq!(Proof::default(), old.proof);

        let written = toml::to_string_pretty(&remembered(proof("h3", false, true))).unwrap();
        let back: LastConnection = toml::from_str(&written).expect("the new shape");
        assert_eq!("h3", back.proof.transport);
        assert!(back.proof.ech);
        assert!(!back.proof.fragment_tls);
        // The top-level profile stays, so a build that only knows the old shape
        // still reads something true out of a file this one wrote.
        assert_eq!("firewall", back.profile);
    }
}

/// What an endpoint was proven with.
///
/// An address on its own is not a memory of anything useful. The same gateway
/// answers over one framing and not the other, with a split ClientHello and not
/// without, under one obfuscation profile and not another -- so a cache holding
/// only the address offers a proof it never made, and the connect that leads
/// with it spends a check finding that out.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Proof {
    /// `h2`, `h3`, `wg`, or whatever the run used.
    #[serde(default)]
    pub transport: String,
    /// The obfuscation profile in force.
    #[serde(default)]
    pub profile: String,
    #[serde(default)]
    pub fragment_tls: bool,
    #[serde(default)]
    pub ech: bool,
}

impl LastConnection {
    /// Whether this memory is about the attempt being made now.
    ///
    /// A file written before proofs were recorded has no transport, and is
    /// accepted: it is one quick check to find out, and refusing it would throw
    /// away every existing install's cache on the first connect after updating.
    /// Anything it does say has to agree.
    pub fn applies_to(&self, now: &Proof) -> bool {
        if self.proof.transport.is_empty() {
            return true;
        }
        self.proof.transport == now.transport
            && self.proof.fragment_tls == now.fragment_tls
            && self.proof.ech == now.ech
    }
}
