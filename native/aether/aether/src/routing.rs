use std::collections::{HashMap, HashSet};
use std::net::IpAddr;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use ipnet::IpNet;
use regex::Regex;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Proxy,
    Direct,
    Block,
}

impl Action {
    pub fn label(self) -> &'static str {
        match self {
            Action::Proxy => "proxy",
            Action::Direct => "direct",
            Action::Block => "block",
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub enum Host<'a> {
    Domain(&'a str),
    Ip(IpAddr),
}

#[derive(Debug)]
enum Matcher {
    DomainSuffix(String),
    DomainFull(String),
    DomainKeyword(String),
    DomainRegex(Regex),
    Net(IpNet),
    Ports(u16, u16),
    Private,
}

impl Matcher {
    fn parse(entry: &str) -> Option<Self> {
        let entry = entry.trim();
        if entry.is_empty() || entry.starts_with('#') {
            return None;
        }

        let (kind, value) = match entry.split_once(':') {
            Some((kind, value)) if !kind.contains('.') && !kind.contains('/') => {
                (kind.trim().to_lowercase(), value.trim())
            }
            _ => (String::new(), entry),
        };

        match kind.as_str() {
            "domain" | "suffix" => Some(Matcher::DomainSuffix(normalize_domain(value)?)),
            "full" | "exact" => Some(Matcher::DomainFull(normalize_domain(value)?)),
            "keyword" => {
                let needle = value.trim().to_lowercase();
                if needle.is_empty() {
                    None
                } else {
                    Some(Matcher::DomainKeyword(needle))
                }
            }
            "regexp" | "regex" => Regex::new(value).ok().map(Matcher::DomainRegex),
            "ip" | "cidr" => parse_net(value).map(Matcher::Net),
            "port" => parse_ports(value).map(|(lo, hi)| Matcher::Ports(lo, hi)),
            "geoip" | "geosite" => {
                if value.eq_ignore_ascii_case("private") {
                    Some(Matcher::Private)
                } else {
                    None
                }
            }
            "" => {
                if value.eq_ignore_ascii_case("private") {
                    return Some(Matcher::Private);
                }
                if let Some(net) = parse_net(value) {
                    return Some(Matcher::Net(net));
                }
                normalize_domain(value).map(Matcher::DomainSuffix)
            }
            _ => None,
        }
    }

    fn matches(&self, host: Host<'_>, port: u16) -> bool {
        match self {
            Matcher::Ports(lo, hi) => port >= *lo && port <= *hi,
            Matcher::Private => match host {
                Host::Ip(ip) => is_private(ip),
                Host::Domain(name) => name.eq_ignore_ascii_case("localhost"),
            },
            Matcher::Net(net) => match host {
                Host::Ip(ip) => net.contains(&ip),
                Host::Domain(_) => false,
            },
            Matcher::DomainSuffix(suffix) => match host {
                Host::Domain(name) => {
                    let name = name.to_lowercase();
                    name == *suffix || name.ends_with(&format!(".{suffix}"))
                }
                Host::Ip(_) => false,
            },
            Matcher::DomainFull(full) => match host {
                Host::Domain(name) => name.eq_ignore_ascii_case(full),
                Host::Ip(_) => false,
            },
            Matcher::DomainKeyword(needle) => match host {
                Host::Domain(name) => name.to_lowercase().contains(needle),
                Host::Ip(_) => false,
            },
            Matcher::DomainRegex(pattern) => match host {
                Host::Domain(name) => pattern.is_match(name),
                Host::Ip(_) => false,
            },
        }
    }
}

fn normalize_domain(value: &str) -> Option<String> {
    let cleaned = value
        .trim()
        .trim_start_matches('*')
        .trim_start_matches('.')
        .trim_end_matches('.')
        .to_lowercase();
    if cleaned.is_empty() {
        None
    } else {
        Some(cleaned)
    }
}

fn parse_net(value: &str) -> Option<IpNet> {
    let value = value.trim();
    if let Ok(net) = value.parse::<IpNet>() {
        return Some(net);
    }
    value.parse::<IpAddr>().ok().map(IpNet::from)
}

fn parse_ports(value: &str) -> Option<(u16, u16)> {
    let value = value.trim();
    match value.split_once('-') {
        Some((lo, hi)) => {
            let lo = lo.trim().parse::<u16>().ok()?;
            let hi = hi.trim().parse::<u16>().ok()?;
            Some(if hi < lo { (hi, lo) } else { (lo, hi) })
        }
        None => {
            let single = value.parse::<u16>().ok()?;
            Some((single, single))
        }
    }
}

pub fn is_private(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            v4.is_private()
                || v4.is_loopback()
                || v4.is_link_local()
                || v4.is_broadcast()
                || v4.is_documentation()
                || v4.is_unspecified()
                || v4.octets()[0] == 100 && (64..128).contains(&v4.octets()[1])
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                || (v6.segments()[0] & 0xfe00) == 0xfc00
                || (v6.segments()[0] & 0xffc0) == 0xfe80
        }
    }
}

// >>> AETHER-APP-PATCH smart-routes-index
//
// 1.4.0 (smart Iran bypass + ad blocking): the rule sets are no longer a
// handful of hand-typed entries. The routes file the app hands over
// (`AETHER_ROUTES_FILE`) carries ~1,300 Iranian address blocks and tens of
// thousands of ad/tracker domains, and `decide()` runs for EVERY flow and
// every UDP datagram. The old representation was a flat `Vec<Matcher>`
// walked with `any()`, and `DomainSuffix::matches` allocated a lowercase copy
// of the name plus a `format!(".{suffix}")` string per entry - i.e. ~40,000
// heap allocations per new connection with the ad list loaded.
//
// Same syntax, same semantics, same precedence (block before direct), indexed:
//
//   * suffix / full domains -> `HashSet`, matched by walking the labels of the
//     name ("a.b.example.com" -> "b.example.com" -> "example.com" -> "com"),
//     O(labels) instead of O(rules);
//   * IPv4 networks -> sorted, merged `[start, end]` ranges, binary search;
//   * IPv6 networks, keywords, regexes, ports and `private` stay a short list.
#[derive(Debug, Default)]
struct Rules {
    suffixes: HashSet<String>,
    fulls: HashSet<String>,
    v4: Vec<(u32, u32)>,
    v6: Vec<IpNet>,
    others: Vec<Matcher>,
    count: usize,
}

impl Rules {
    fn from_matchers(list: Vec<Matcher>) -> Self {
        let mut rules = Rules::default();
        let mut v4: Vec<(u32, u32)> = Vec::new();
        for matcher in list {
            rules.count += 1;
            match matcher {
                Matcher::DomainSuffix(suffix) => {
                    rules.suffixes.insert(suffix);
                }
                Matcher::DomainFull(full) => {
                    rules.fulls.insert(full);
                }
                Matcher::Net(IpNet::V4(net)) => {
                    v4.push((u32::from(net.network()), u32::from(net.broadcast())));
                }
                Matcher::Net(net @ IpNet::V6(_)) => rules.v6.push(net),
                other => rules.others.push(other),
            }
        }
        v4.sort_unstable();
        let mut merged: Vec<(u32, u32)> = Vec::with_capacity(v4.len());
        for (start, end) in v4 {
            if let Some(last) = merged.last_mut() {
                if (start as u64) <= (last.1 as u64) + 1 {
                    if end > last.1 {
                        last.1 = end;
                    }
                    continue;
                }
            }
            merged.push((start, end));
        }
        rules.v4 = merged;
        rules
    }

    fn len(&self) -> usize {
        self.count
    }

    fn is_empty(&self) -> bool {
        self.count == 0
    }

    fn has_domain_rules(&self) -> bool {
        !self.suffixes.is_empty()
            || !self.fulls.is_empty()
            || self.others.iter().any(|rule| {
                matches!(rule, Matcher::DomainKeyword(_) | Matcher::DomainRegex(_))
            })
    }

    fn matches(&self, host: Host<'_>, port: u16) -> bool {
        match host {
            Host::Domain(name) => {
                if !self.suffixes.is_empty() || !self.fulls.is_empty() {
                    let lowered = name.trim_end_matches('.').to_ascii_lowercase();
                    if self.fulls.contains(&lowered) {
                        return true;
                    }
                    if !self.suffixes.is_empty() {
                        let mut rest = lowered.as_str();
                        loop {
                            if self.suffixes.contains(rest) {
                                return true;
                            }
                            match rest.find('.') {
                                Some(dot) => rest = &rest[dot + 1..],
                                None => break,
                            }
                        }
                    }
                }
            }
            Host::Ip(IpAddr::V4(v4)) => {
                let x = u32::from(v4);
                let idx = self.v4.partition_point(|&(start, _)| start <= x);
                if idx > 0 && self.v4[idx - 1].1 >= x {
                    return true;
                }
            }
            Host::Ip(ip @ IpAddr::V6(_)) => {
                if self.v6.iter().any(|net| net.contains(&ip)) {
                    return true;
                }
            }
        }
        self.others.iter().any(|rule| rule.matches(host, port))
    }
}
// <<< AETHER-APP-PATCH smart-routes-index

#[derive(Debug, Default)]
pub struct RuleSet {
    block: Rules,
    direct: Rules,
}

impl RuleSet {
    pub fn from_env() -> Self {
        let mut block = std::env::var("AETHER_ROUTE_BLOCK").unwrap_or_default();
        let mut direct = std::env::var("AETHER_ROUTE_DIRECT").unwrap_or_default();

        if let Ok(path) = std::env::var("AETHER_ROUTES_FILE") {
            match std::fs::read_to_string(&path) {
                Ok(text) => {
                    let (file_block, file_direct) = split_sections(&text);
                    push_list(&mut block, &file_block);
                    push_list(&mut direct, &file_direct);
                }
                Err(error) => {
                    log::warn!("[-] could not read the routing file {path}: {error}");
                }
            }
        }

        Self::parse(&block, &direct)
    }

    pub fn parse(block: &str, direct: &str) -> Self {
        let set = Self {
            block: parse_list(block),
            direct: parse_list(direct),
        };

        if !set.is_empty() {
            log::info!(
                "[+] routing rules loaded: {} block, {} direct",
                set.block.len(),
                set.direct.len()
            );
        }
        set
    }

    pub fn is_empty(&self) -> bool {
        self.block.is_empty() && self.direct.is_empty()
    }

    pub fn has_domain_rules(&self) -> bool {
        self.block.has_domain_rules() || self.direct.has_domain_rules()
    }

    pub fn decide(&self, host: Host<'_>, port: u16) -> Action {
        if self.block.matches(host, port) {
            return Action::Block;
        }
        if self.direct.matches(host, port) {
            return Action::Direct;
        }
        Action::Proxy
    }

    // >>> AETHER-APP-PATCH smart-routes-dns
    /// DNS-level blocking (the sinkhole every modern ad blocker is built on).
    ///
    /// When [query] is a standard one-question DNS query for a name the BLOCK
    /// rules cover, returns an `NXDOMAIN` answer to hand straight back to the
    /// device, so the app never even gets an address to connect to. `None` for
    /// anything else, in which case the query is carried untouched.
    ///
    /// Only block rules are consulted: a direct rule changes where a flow goes,
    /// never whether a name resolves.
    pub fn dns_block_reply(&self, query: &[u8]) -> Option<Vec<u8>> {
        if self.block.is_empty() {
            return None;
        }
        let (name, question_end) = dns_question(query)?;
        if !self.block.matches(Host::Domain(&name), 53) {
            return None;
        }
        log::debug!("[route] dns sinkhole {name}");
        let mut reply = query.get(..question_end)?.to_vec();
        // QR=1, opcode 0, RD copied; RA=1, RCODE=3 (NXDOMAIN); only the question.
        reply[2] = (query[2] & 0x01) | 0x80;
        reply[3] = 0x83;
        for byte in &mut reply[6..12] {
            *byte = 0;
        }
        Some(reply)
    }
    // <<< AETHER-APP-PATCH smart-routes-dns
}

fn parse_list(raw: &str) -> Rules {
    Rules::from_matchers(raw.split(['\n', ',', ';']).filter_map(Matcher::parse).collect())
}

// >>> AETHER-APP-PATCH smart-routes-dns
/// The single question of a DNS QUERY: its name (lowercase, no trailing dot)
/// and the offset just past QTYPE/QCLASS. `None` for a response, a message
/// with more or fewer than one question, a compression pointer inside the
/// question (illegal there) or a truncated buffer.
fn dns_question(msg: &[u8]) -> Option<(String, usize)> {
    if msg.len() < 17 || msg[2] & 0x80 != 0 {
        return None;
    }
    if u16::from_be_bytes([msg[4], msg[5]]) != 1 {
        return None;
    }
    let (name, end) = read_plain_name(msg, 12)?;
    let end = end.checked_add(4)?;
    if end > msg.len() {
        return None;
    }
    Some((name, end))
}

/// Reads an uncompressed name starting at [at]; returns it and the offset
/// just past its terminating zero label.
fn read_plain_name(msg: &[u8], mut at: usize) -> Option<(String, usize)> {
    let mut name = String::new();
    let mut labels = 0;
    loop {
        let len = *msg.get(at)? as usize;
        if len == 0 {
            at += 1;
            break;
        }
        if len & 0xc0 != 0 || labels > 127 {
            return None;
        }
        let label = msg.get(at + 1..at + 1 + len)?;
        if !name.is_empty() {
            name.push('.');
        }
        name.push_str(&String::from_utf8_lossy(label).to_ascii_lowercase());
        at += 1 + len;
        labels += 1;
    }
    if name.is_empty() {
        return None;
    }
    Some((name, at))
}

fn skip_dns_name(msg: &[u8], mut at: usize) -> Option<usize> {
    let mut hops = 0;
    loop {
        let len = *msg.get(at)? as usize;
        if len & 0xc0 == 0xc0 {
            return Some(at + 2);
        }
        if len == 0 {
            return Some(at + 1);
        }
        at += 1 + len;
        hops += 1;
        if hops > 128 {
            return None;
        }
    }
}

/// Address -> name memory, filled from the DNS answers that pass through the
/// SOCKS5 UDP relay. It lets a flow that carries no readable name of its own
/// (QUIC, or a TCP protocol that is neither TLS nor HTTP) still be matched
/// against the domain rules.
///
/// It is a HINT, never an authority: CDN addresses are shared by many names,
/// so callers use it only where a wrong guess is harmless (dropping a QUIC
/// datagram makes the app fall back to TCP, where the real server name
/// decides) or where no better signal exists at all.
struct NameMemory {
    names: HashMap<IpAddr, (String, Instant)>,
}

const NAME_MEMORY_MAX: usize = 8192;
const NAME_MEMORY_MIN_TTL: Duration = Duration::from_secs(60);
const NAME_MEMORY_MAX_TTL: Duration = Duration::from_secs(3600);

fn name_memory() -> &'static Mutex<NameMemory> {
    static MEMORY: OnceLock<Mutex<NameMemory>> = OnceLock::new();
    MEMORY.get_or_init(|| {
        Mutex::new(NameMemory {
            names: HashMap::new(),
        })
    })
}

/// Records every A/AAAA answer in the DNS RESPONSE [msg] under the name that
/// was asked for (not the CNAME target: the asked-for name is what the user's
/// app will present as its server name, so it is what the rules are written
/// against).
pub fn learn_dns_answer(msg: &[u8]) {
    if msg.len() < 12 || msg[2] & 0x80 == 0 || msg[3] & 0x0f != 0 {
        return;
    }
    if u16::from_be_bytes([msg[4], msg[5]]) != 1 {
        return;
    }
    let answers = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    if answers == 0 {
        return;
    }
    let Some((name, question_end)) = read_plain_name(msg, 12) else {
        return;
    };
    let mut at = question_end + 4;
    let now = Instant::now();
    let Ok(mut memory) = name_memory().lock() else {
        return;
    };
    for _ in 0..answers.min(64) {
        let Some(after_name) = skip_dns_name(msg, at) else {
            return;
        };
        if after_name + 10 > msg.len() {
            return;
        }
        let rtype = u16::from_be_bytes([msg[after_name], msg[after_name + 1]]);
        let ttl = u32::from_be_bytes([
            msg[after_name + 4],
            msg[after_name + 5],
            msg[after_name + 6],
            msg[after_name + 7],
        ]);
        let rdlen = u16::from_be_bytes([msg[after_name + 8], msg[after_name + 9]]) as usize;
        let rdata = after_name + 10;
        if rdata + rdlen > msg.len() {
            return;
        }
        let ip = match (rtype, rdlen) {
            (1, 4) => Some(IpAddr::from([
                msg[rdata],
                msg[rdata + 1],
                msg[rdata + 2],
                msg[rdata + 3],
            ])),
            (28, 16) => {
                let mut raw = [0u8; 16];
                raw.copy_from_slice(&msg[rdata..rdata + 16]);
                Some(IpAddr::from(raw))
            }
            _ => None,
        };
        if let Some(ip) = ip {
            if memory.names.len() >= NAME_MEMORY_MAX {
                memory.names.retain(|_, (_, expires)| *expires > now);
                if memory.names.len() >= NAME_MEMORY_MAX {
                    memory.names.clear();
                }
            }
            let ttl = Duration::from_secs(ttl as u64).clamp(NAME_MEMORY_MIN_TTL, NAME_MEMORY_MAX_TTL);
            memory.names.insert(ip, (name.clone(), now + ttl));
        }
        at = rdata + rdlen;
    }
}

/// The name [ip] was last resolved from, while that answer is still fresh.
pub fn recall_name(ip: IpAddr) -> Option<String> {
    let memory = name_memory().lock().ok()?;
    let (name, expires) = memory.names.get(&ip)?;
    if *expires <= Instant::now() {
        return None;
    }
    Some(name.clone())
}
// <<< AETHER-APP-PATCH smart-routes-dns

fn push_list(target: &mut String, extra: &str) {
    if extra.trim().is_empty() {
        return;
    }
    if !target.trim().is_empty() {
        target.push('\n');
    }
    target.push_str(extra);
}

fn split_sections(text: &str) -> (String, String) {
    let mut block = String::new();
    let mut direct = String::new();
    let mut current: Option<&mut String> = None;

    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }

        let lowered = trimmed.to_lowercase();
        if lowered == "[block]" {
            current = Some(&mut block);
            continue;
        }
        if lowered == "[direct]" {
            current = Some(&mut direct);
            continue;
        }
        if lowered.starts_with('[') {
            current = None;
            continue;
        }

        if let Some(target) = current.as_deref_mut() {
            target.push_str(trimmed);
            target.push('\n');
        }
    }

    (block, direct)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rules(block: &str, direct: &str) -> RuleSet {
        RuleSet {
            block: parse_list(block),
            direct: parse_list(direct),
        }
    }

    #[test]
    fn an_empty_rule_set_sends_everything_through_the_proxy() {
        let set = rules("", "");
        assert!(set.is_empty());
        assert_eq!(set.decide(Host::Domain("example.com"), 443), Action::Proxy);
        assert_eq!(
            set.decide(Host::Ip("1.1.1.1".parse().unwrap()), 443),
            Action::Proxy
        );
    }

    #[test]
    fn a_bare_domain_matches_itself_and_its_subdomains() {
        let set = rules("ads.example", "");
        assert_eq!(set.decide(Host::Domain("ads.example"), 443), Action::Block);
        assert_eq!(
            set.decide(Host::Domain("tracker.ads.example"), 443),
            Action::Block
        );
        assert_eq!(
            set.decide(Host::Domain("notads.example"), 443),
            Action::Proxy
        );
        assert_eq!(
            set.decide(Host::Domain("ads.example.org"), 443),
            Action::Proxy
        );
    }

    #[test]
    fn a_full_rule_matches_only_the_exact_name() {
        let set = rules("full:example.com", "");
        assert_eq!(set.decide(Host::Domain("example.com"), 443), Action::Block);
        assert_eq!(
            set.decide(Host::Domain("www.example.com"), 443),
            Action::Proxy
        );
    }

    #[test]
    fn a_keyword_rule_matches_anywhere_in_the_name() {
        let set = rules("keyword:doubleclick", "");
        assert_eq!(
            set.decide(Host::Domain("stats.doubleclick.net"), 443),
            Action::Block
        );
        assert_eq!(set.decide(Host::Domain("example.com"), 443), Action::Proxy);
    }

    #[test]
    fn a_regex_rule_is_honoured() {
        let set = rules(r"regexp:^ad[0-9]+\.", "");
        assert_eq!(
            set.decide(Host::Domain("ad42.example.com"), 443),
            Action::Block
        );
        assert_eq!(
            set.decide(Host::Domain("ads.example.com"), 443),
            Action::Proxy
        );
    }

    #[test]
    fn a_cidr_rule_matches_addresses_inside_it() {
        let set = rules("", "10.0.0.0/8");
        assert_eq!(
            set.decide(Host::Ip("10.1.2.3".parse().unwrap()), 22),
            Action::Direct
        );
        assert_eq!(
            set.decide(Host::Ip("11.1.2.3".parse().unwrap()), 22),
            Action::Proxy
        );
    }

    #[test]
    fn a_bare_address_is_treated_as_a_single_host_rule() {
        let set = rules("1.2.3.4", "");
        assert_eq!(
            set.decide(Host::Ip("1.2.3.4".parse().unwrap()), 80),
            Action::Block
        );
        assert_eq!(
            set.decide(Host::Ip("1.2.3.5".parse().unwrap()), 80),
            Action::Proxy
        );
    }

    #[test]
    fn a_port_rule_can_carve_out_a_range() {
        let set = rules("port:25", "port:3000-3010");
        assert_eq!(set.decide(Host::Domain("mail.example"), 25), Action::Block);
        assert_eq!(
            set.decide(Host::Domain("dev.example"), 3005),
            Action::Direct
        );
        assert_eq!(set.decide(Host::Domain("dev.example"), 3011), Action::Proxy);
    }

    #[test]
    fn the_private_keyword_covers_lan_and_loopback_and_cgnat() {
        let set = rules("", "private");
        for address in [
            "10.1.1.1",
            "192.168.1.5",
            "172.16.9.9",
            "127.0.0.1",
            "100.96.0.2",
        ] {
            assert_eq!(
                set.decide(Host::Ip(address.parse().unwrap()), 80),
                Action::Direct,
                "{address} should be direct"
            );
        }
        assert_eq!(
            set.decide(Host::Ip("8.8.8.8".parse().unwrap()), 80),
            Action::Proxy
        );
        assert_eq!(set.decide(Host::Domain("localhost"), 80), Action::Direct);
    }

    #[test]
    fn ipv6_private_ranges_are_recognised() {
        assert!(is_private("::1".parse().unwrap()));
        assert!(is_private("fd00::1".parse().unwrap()));
        assert!(is_private("fe80::1".parse().unwrap()));
        assert!(!is_private("2606:4700::1111".parse().unwrap()));
    }

    #[test]
    fn domain_rules_are_reported_so_a_tunnel_can_recover_the_name() {
        assert!(!rules("", "").has_domain_rules());
        assert!(!rules("10.0.0.0/8, port:25", "private").has_domain_rules());
        assert!(rules("ads.example", "").has_domain_rules());
        assert!(rules("", "keyword:internal").has_domain_rules());
        assert!(rules("", r"regexp:^ad[0-9]+\.").has_domain_rules());
        assert!(rules("full:example.com", "").has_domain_rules());
    }

    #[test]
    fn block_wins_over_direct_when_both_match() {
        let set = rules("example.com", "example.com");
        assert_eq!(set.decide(Host::Domain("example.com"), 443), Action::Block);
    }

    #[test]
    fn lists_accept_commas_newlines_and_comments() {
        let set = rules("a.example, b.example\n# a comment\nc.example", "");
        for name in ["a.example", "b.example", "c.example"] {
            assert_eq!(set.decide(Host::Domain(name), 443), Action::Block, "{name}");
        }
        assert_eq!(set.decide(Host::Domain("comment"), 443), Action::Proxy);
    }

    #[test]
    fn a_leading_wildcard_is_tolerated() {
        let set = rules("*.example.com", "");
        assert_eq!(
            set.decide(Host::Domain("a.example.com"), 443),
            Action::Block
        );
        assert_eq!(set.decide(Host::Domain("example.com"), 443), Action::Block);
    }

    #[test]
    fn a_rules_file_is_split_into_its_two_sections() {
        let text =
            "# routing\n[block]\nads.example\nkeyword:tracker\n\n[direct]\nprivate\n10.0.0.0/8\n";
        let (block, direct) = split_sections(text);
        assert!(block.contains("ads.example"));
        assert!(block.contains("keyword:tracker"));
        assert!(direct.contains("private"));
        assert!(direct.contains("10.0.0.0/8"));
        assert!(!block.contains("private"));
    }

    #[test]
    fn an_unknown_section_is_ignored_rather_than_misfiled() {
        let (block, direct) = split_sections("[proxy]\nexample.com\n[block]\nads.example\n");
        assert!(!block.contains("example.com"));
        assert!(block.contains("ads.example"));
        assert!(direct.trim().is_empty());
    }

    // >>> AETHER-APP-PATCH smart-routes-dns
    fn dns_query(name: &str, qtype: u16) -> Vec<u8> {
        let mut msg = vec![0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
        for label in name.split('.') {
            msg.push(label.len() as u8);
            msg.extend_from_slice(label.as_bytes());
        }
        msg.push(0);
        msg.extend_from_slice(&qtype.to_be_bytes());
        msg.extend_from_slice(&1u16.to_be_bytes());
        msg
    }

    fn dns_answer(name: &str, ip: [u8; 4], ttl: u32) -> Vec<u8> {
        let mut msg = dns_query(name, 1);
        msg[2] = 0x81;
        msg[3] = 0x80;
        msg[7] = 1;
        // A compression pointer back to the question name at offset 12.
        msg.extend_from_slice(&[0xc0, 0x0c, 0, 1, 0, 1]);
        msg.extend_from_slice(&ttl.to_be_bytes());
        msg.extend_from_slice(&[0, 4]);
        msg.extend_from_slice(&ip);
        msg
    }

    #[test]
    fn a_blocked_name_is_answered_with_nxdomain() {
        let set = rules("ads.example", "");
        let query = dns_query("tracker.ads.example", 1);
        let reply = set.dns_block_reply(&query).expect("a blocked name is sinkholed");
        assert_eq!(&reply[..2], &query[..2], "the id is echoed");
        assert_eq!(reply[2] & 0x80, 0x80, "it is a response");
        assert_eq!(reply[2] & 0x01, 0x01, "RD is copied");
        assert_eq!(reply[3] & 0x0f, 3, "NXDOMAIN");
        assert_eq!(&reply[4..6], &[0, 1], "the question stays");
        assert_eq!(&reply[6..12], &[0; 6], "no answers");
        assert_eq!(reply.len(), query.len());
    }

    #[test]
    fn an_allowed_name_or_a_direct_rule_is_not_sinkholed() {
        let set = rules("ads.example", "bank.example");
        assert!(set.dns_block_reply(&dns_query("example.com", 1)).is_none());
        assert!(set.dns_block_reply(&dns_query("bank.example", 1)).is_none());
        assert!(rules("", "").dns_block_reply(&dns_query("ads.example", 1)).is_none());
    }

    #[test]
    fn a_response_or_garbage_is_never_sinkholed() {
        let set = rules("ads.example", "");
        assert!(set.dns_block_reply(&dns_answer("ads.example", [1, 2, 3, 4], 60)).is_none());
        assert!(set.dns_block_reply(&[0u8; 5]).is_none());
        let mut truncated = dns_query("ads.example", 1);
        truncated.truncate(truncated.len() - 3);
        assert!(set.dns_block_reply(&truncated).is_none());
    }

    #[test]
    fn answers_are_remembered_under_the_name_that_was_asked_for() {
        let ip: IpAddr = "198.51.100.77".parse().unwrap();
        assert_eq!(recall_name(ip), None);
        learn_dns_answer(&dns_answer("cdn.ads.example", [198, 51, 100, 77], 300));
        assert_eq!(recall_name(ip).as_deref(), Some("cdn.ads.example"));
    }

    #[test]
    fn a_query_teaches_the_memory_nothing() {
        let ip: IpAddr = "198.51.100.78".parse().unwrap();
        let mut query = dns_answer("x.example", [198, 51, 100, 78], 300);
        query[2] = 0x01; // QR cleared: a query, not an answer
        learn_dns_answer(&query);
        assert_eq!(recall_name(ip), None);
    }

    #[test]
    fn a_large_ipv4_list_is_merged_and_binary_searched() {
        let mut list = String::new();
        for third in 0..=255u32 {
            list.push_str(&format!("10.20.{third}.0/24\n"));
        }
        list.push_str("192.0.2.0/25\n192.0.2.128/25\n");
        let set = rules("", &list);
        assert_eq!(set.direct.v4.len(), 2, "adjacent ranges are merged");
        for probe in ["10.20.0.1", "10.20.255.254", "192.0.2.200"] {
            assert_eq!(
                set.decide(Host::Ip(probe.parse().unwrap()), 443),
                Action::Direct,
                "{probe}"
            );
        }
        for probe in ["10.19.255.255", "10.21.0.0", "192.0.3.0"] {
            assert_eq!(
                set.decide(Host::Ip(probe.parse().unwrap()), 443),
                Action::Proxy,
                "{probe}"
            );
        }
    }

    #[test]
    fn a_tld_suffix_covers_every_name_under_it() {
        let set = rules("", "ir");
        assert_eq!(set.decide(Host::Domain("www.digikala.ir"), 443), Action::Direct);
        assert_eq!(set.decide(Host::Domain("IR."), 443), Action::Direct);
        assert_eq!(set.decide(Host::Domain("example.iran"), 443), Action::Proxy);
    }
    // <<< AETHER-APP-PATCH smart-routes-dns

    #[test]
    fn malformed_entries_are_dropped_without_panicking() {
        let set = rules("regexp:[unclosed, port:abc, ip:not-an-ip, geoip:cn, :", "");
        assert!(set.is_empty() || set.decide(Host::Domain("example.com"), 443) == Action::Proxy);
    }
}
