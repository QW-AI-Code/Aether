//! Tests for padding

// @@ begin test lint list maintained by maint/add_warning @@
#![allow(clippy::bool_assert_comparison)]
#![allow(clippy::clone_on_copy)]
#![allow(clippy::dbg_macro)]
#![allow(clippy::mixed_attributes_style)]
#![allow(clippy::print_stderr)]
#![allow(clippy::print_stdout)]
#![allow(clippy::single_char_pattern)]
#![allow(clippy::unwrap_used)]
#![allow(clippy::unchecked_time_subtraction)]
#![allow(clippy::useless_vec)]
#![allow(clippy::needless_pass_by_value)]
#![allow(clippy::string_slice)] // See arti#2571
//! <!-- @@ end test lint list maintained by maint/add_warning @@ -->

use super::*;

use std::iter;

use async_trait::async_trait;
use futures::channel::mpsc;
use itertools::{Itertools, zip_eq};
#[cfg(feature = "relay")]
use safelog::Sensitive;

use tor_cell::chancell::msg::PaddingNegotiateCmd;
use tor_config::PaddingLevel;
use tor_linkspec::{HasRelayIds, OwnedChanTarget};
use tor_memquota::ArcMemoryQuotaTrackerExt as _;
use tor_netdir::NetDir;
use tor_proto::channel::{Channel, CtrlMsg};
use tor_proto::memquota::{ChannelAccount, ToplevelAccount};
use tor_rtcompat::{Runtime, test_with_all_runtimes};

use crate::ChannelUsage;
use crate::mgr::{AbstractChanMgr, AbstractChannelFactory};

use crate::factory::BootstrapReporter;
use PaddingLevel as PL;

const DEF_MS: [u32; 2] = [1500, 9500];
const REDUCED_MS: [u32; 2] = [9000, 14000];

const ADJ_MS: [u32; 2] = [1499, 9499];
const ADJ_REDUCED_MS: [u32; 2] = [8999, 13999];

/// Returns a NetDir that has consensus parameters as specified
fn some_interesting_netdir<'v, V>(values: V) -> Arc<NetDir>
where
    V: IntoIterator<Item = (&'v str, i32)>,
{
    tor_netdir::testnet::construct_custom_netdir_with_params(|_, _, _| {}, values, None)
        .unwrap()
        .unwrap_if_sufficient()
        .unwrap()
        .into()
}

/// Returns a NetDir that has consensus parameters different from the protocol default
fn interesting_netdir() -> Arc<NetDir> {
    some_interesting_netdir(
        [
            ("nf_ito_low", ADJ_MS[0]),
            ("nf_ito_high", ADJ_MS[1]),
            ("nf_ito_low_reduced", ADJ_REDUCED_MS[0]),
            ("nf_ito_high_reduced", ADJ_REDUCED_MS[1]),
        ]
        .into_iter()
        .map(|(k, v)| (k, v as _)),
    )
}

#[test]
fn padding_parameters_calculation() {
    fn one(pconfig: PaddingLevel, netparams: &NetParamsExtract, exp: Option<[u32; 2]>) {
        eprintln!(
            "### {:?} {:?}",
            pconfig,
            netparams.nf_ito.map(|l| l.map(|v| v.as_millis().get())),
        );
        let got = padding_parameters(pconfig, netparams).unwrap();
        let exp = exp.map(|exp| {
            PaddingParameters::builder()
                .low(exp[0].into())
                .high(exp[1].into())
                .build()
                .unwrap()
        });
        assert_eq!(got, exp);
    }

    one(
        PL::default(),
        &NetParamsExtract::from(interesting_netdir().params()),
        Some(ADJ_MS),
    );

    one(
        PL::Reduced,
        &NetParamsExtract::from(interesting_netdir().params()),
        Some(ADJ_REDUCED_MS),
    );

    let make_bogus_netdir = |values: &[(&str, i32)]| {
        NetParamsExtract::from(
            tor_netdir::testnet::construct_custom_netdir_with_params(
                |_, _, _| {},
                values.iter().cloned(),
                None,
            )
            .unwrap()
            .unwrap_if_sufficient()
            .unwrap()
            .params(),
        )
    };

    let bogus_netdir = make_bogus_netdir(&[
        // for testing low > high
        ("nf_ito_low", ADJ_REDUCED_MS[1] as _),
        ("nf_ito_high", ADJ_REDUCED_MS[0] as _),
    ]);
    one(PL::default(), &bogus_netdir, Some(DEF_MS));
}

#[derive(Clone)]
struct FakeChannelFactory {
    channel: Arc<Channel>,
}

#[async_trait]
impl AbstractChannelFactory for FakeChannelFactory {
    type Channel = Channel;
    type BuildSpec = tor_linkspec::OwnedChanTarget;
    type Stream = ();

    async fn build_channel(
        &self,
        _target: &Self::BuildSpec,
        _reporter: BootstrapReporter,
        _memquota: ChannelAccount,
    ) -> Result<Arc<Self::Channel>> {
        Ok(self.channel.clone())
    }

    #[cfg(feature = "relay")]
    async fn build_channel_using_incoming(
        &self,
        _peer: Sensitive<std::net::SocketAddr>,
        _stream: Self::Stream,
        _memquota: ChannelAccount,
    ) -> Result<Arc<Self::Channel>> {
        unimplemented!()
    }
}

struct CaseContext {
    channel: Arc<Channel>,
    recv: mpsc::UnboundedReceiver<CtrlMsg>,
    chanmgr: AbstractChanMgr<FakeChannelFactory>,
    netparams: Arc<dyn AsRef<NetParameters>>,
}

/// Details of an expected control message
#[derive(Debug, Clone, Default)]
struct Expected {
    enabled: Option<bool>,
    timing: Option<[u32; 2]>,
    nego: Option<(PaddingNegotiateCmd, [u32; 2])>,
}

async fn case(
    rt: &impl Runtime,
    level: PaddingLevel,
    dormancy: Dormancy,
    usage: ChannelUsage,
) -> CaseContext {
    let mut cconfig = ChannelConfig::builder();
    cconfig.padding(level);
    let cconfig = cconfig.build().unwrap();

    eprintln!("\n---- {:?} {:?} {:?} ----", cconfig, dormancy, usage);

    let (channel, recv) =
        Channel::new_fake(rt.clone(), tor_proto::channel::ChannelType::ClientInitiator);
    let peer_id = channel.target().ed_identity().unwrap().clone();
    let relay_ids = OwnedChanTarget::builder()
        .ed_identity(peer_id.clone())
        .build()
        .unwrap();
    let factory = FakeChannelFactory {
        channel: Arc::new(channel),
    };

    let netparams = Arc::new(NetParameters::default());

    let chanmgr = AbstractChanMgr::new(
        factory,
        cconfig,
        dormancy,
        &netparams,
        BootstrapReporter::fake(),
        ToplevelAccount::new_noop(),
    );

    let (channel, _prov) = chanmgr.get_or_launch(relay_ids, usage).await.unwrap();

    CaseContext {
        channel,
        recv,
        chanmgr,
        netparams,
    }
}

impl CaseContext {
    fn netparams(&self) -> Arc<dyn AsRef<NetParameters>> {
        self.netparams.clone()
    }

    fn expect_1(&mut self, exp: Expected) {
        self.expect(vec![exp]);
    }
    fn expect_0(&mut self) {
        self.expect(vec![]);
    }

    fn expect(&mut self, expected: Vec<Expected>) {
        #[allow(deprecated)] // TODO(#2386)
        let messages = iter::from_fn(|| match self.recv.try_next() {
            Ok(Some(t)) => Some(Ok(t)),
            Ok(None) => Some(Err(())),
            Err(_) => None,
        })
        .collect_vec();

        eprintln!("{:#?}", messages);

        for (i, (got, exp)) in zip_eq(messages, expected).enumerate() {
            eprintln!("{} {:?} {:?}", i, got, exp);
            let got: ChannelPaddingInstructionsUpdates = match got {
                Ok(CtrlMsg::ConfigUpdate(u)) => (*u).clone(),
                _ => panic!("wrong message {:?}", got),
            };

            let Expected {
                enabled,
                timing,
                nego,
            } = exp;
            let nego =
                nego.map(|(cmd, [low, high])| PaddingNegotiate::from_raw(cmd, low as _, high as _));
            let timing = timing.map(|[low, high]| {
                PaddingParameters::builder()
                    .low(low.into())
                    .high(high.into())
                    .build()
                    .unwrap()
            });
            assert_eq!(got.padding_enable(), enabled.as_ref());
            assert_eq!(got.padding_parameters(), timing.as_ref());
            assert_eq!(got.padding_negotiate(), nego.as_ref());
        }
    }
}

/// Test padding control from the top of chanmgr through to just before the channel reactor
///
/// The rules about when to send padding and what negotiation cells to send are super complex.
/// Furthermore, our implementation is spread across several layers, mostly for performance
/// reasons (in particular, to do as much of the work centrally, in
/// the channel manager, as possible).
///
/// So here we test what happens if we call the various channel manager methods (the methods on
/// `AbstractChanMgr`, not `ChanMgr`, because our channel factory is strange, but the methods of
/// `ChanMgr` are simple passthroughs).
///
/// We observe the effect by pretending that we are the channel reactor, and reading out
/// the control messages.  The channel reactor logic is very simple: it just does as it's
/// told.  For example each PaddingNegotiation in a control message will be sent precisely
/// once (assuming it can be sent before the channel is closed or the next one arrives).
/// The timing parameters, and enablement, are passed directly to the padding timer.
#[test]
fn padding_control_through_layer() {
    test_with_all_runtimes!(padding_control_through_layer_impl);
}

/// Helper for padding_control_through_layer: takes a runtime as an argument.
async fn padding_control_through_layer_impl(rt: impl tor_rtcompat::Runtime) {
    const STOP_MSG: (PaddingNegotiateCmd, [u32; 2]) = (PaddingNegotiateCmd::STOP, [0, 0]);
    const START_CMD: PaddingNegotiateCmd = PaddingNegotiateCmd::START;

    // ---- simple case, active exit, defaults ----

    let mut c = case(
        &rt,
        PL::default(),
        Dormancy::Active,
        ChannelUsage::UserTraffic,
    )
    .await;
    c.expect_1(Expected {
        enabled: Some(true),
        timing: Some(DEF_MS),
        nego: None,
    });

    // ---- reduced padding ----

    let mut c = case(
        &rt,
        PL::Reduced,
        Dormancy::Active,
        ChannelUsage::UserTraffic,
    )
    .await;
    c.expect_1(Expected {
        enabled: Some(true),
        timing: Some(REDUCED_MS),
        nego: Some(STOP_MSG),
    });

    // ---- dormant ----

    let mut c = case(
        &rt,
        PL::default(),
        Dormancy::Dormant,
        ChannelUsage::UserTraffic,
    )
    .await;
    c.expect_1(Expected {
        enabled: None,
        timing: None,
        nego: Some(STOP_MSG),
    });

    // ---- more complicated evolution ----

    let cconfig_reduced = {
        let mut cconfig = ChannelConfig::builder();
        cconfig.padding(PL::Reduced);
        cconfig.build().unwrap()
    };

    let mut c = case(&rt, PL::default(), Dormancy::Active, ChannelUsage::Dir).await;
    // directory circuits don't get padding (and we don't need to tell the peer to disable)
    c.expect_0();

    eprintln!("### UserTraffic ###");
    c.channel.engage_padding_activities();
    c.expect_1(Expected {
        enabled: Some(true),  // we now turn on our padding sender
        timing: Some(DEF_MS), // with default parameters
        nego: None,           // the peer will start padding when it sees us do non-dir stuff
    });

    eprintln!("### set_dormancy - Dormant ###");
    c.chanmgr
        .set_dormancy(Dormancy::Dormant, c.netparams())
        .unwrap();
    c.expect_1(Expected {
        enabled: Some(false), // we now must turn off our padding sender
        timing: None,
        nego: Some(STOP_MSG), // and tell the peer to stop
    });

    eprintln!("### change to reduced padding while dormant ###");
    c.chanmgr
        .reconfigure(&cconfig_reduced, c.netparams())
        .unwrap();
    c.expect_0();

    eprintln!("### set_dormancy - Active ###");
    c.chanmgr
        .set_dormancy(Dormancy::Active, c.netparams())
        .unwrap();
    c.expect_1(Expected {
        enabled: Some(true),
        timing: Some(REDUCED_MS),
        nego: None, // don't enable inbound padding again
    });

    eprintln!("### imagine a netdir turns up, with some different parameters ###");
    c.netparams = interesting_netdir();
    c.chanmgr.update_netparams(c.netparams()).unwrap();
    c.expect_1(Expected {
        enabled: None,                // still enabled
        timing: Some(ADJ_REDUCED_MS), // parameters adjusted a bit
        nego: None,                   // no need to send an update
    });

    eprintln!("### change back to normal padding ###");
    c.chanmgr
        .reconfigure(&ChannelConfig::default(), c.netparams())
        .unwrap();
    c.expect_1(Expected {
        enabled: None,                   // still enabled
        timing: Some(ADJ_MS),            // parameters adjusted
        nego: Some((START_CMD, [0, 0])), // ask peer to use consensus default
    });

    eprintln!("### consensus changes to no padding ###");
    // ---- consensus is no padding ----
    c.netparams = some_interesting_netdir(
        [
            "nf_ito_low",
            "nf_ito_high",
            "nf_ito_low_reduced",
            "nf_ito_high_reduced",
        ]
        .into_iter()
        .map(|k| (k, 0)),
    );
    c.chanmgr.update_netparams(c.netparams()).unwrap();
    c.expect_1(Expected {
        enabled: Some(false),
        timing: None,
        nego: None,
    });

    // Ideally we would somehow test the sending of a START message with nonzero parameters.
    //
    // However, that can only occur if we want the peer to send some padding which is not the
    // consensus default.  And we get our own desired parameters from our idea of the consensus:
    // the config can only enable/disable/reduce (and for reduced, we ask our peer not to send
    // padding at all).
    //
    // The only current arrangements for providing alternative parameters are via netdir overrides,
    // which (because they override our view of the netdir) alter not only our idea of what to do,
    // but also our idea of what our peer will do.
    //
    // Possibly at some future point we might support specifying padding parameters
    // separately in the config.
}
