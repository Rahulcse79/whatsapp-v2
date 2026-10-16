//
//  PjsipBridge.h
//  The only file in this app that knows pjsua exists.
//
//  ## Why a bridge rather than calling pjsua from Swift directly
//
//  Swift can call C. It cannot call pjsua *safely*: every pjsua entry point must be
//  invoked from a thread PJLIB has registered, pjsua's callbacks arrive on whichever
//  thread the stack happens to be on, and `pj_thread_register` allocates thread-local
//  state whose lifetime Swift has no way to tie to a GCD queue — a queue that migrates
//  its work to a different thread between two calls is a use-after-free. The Android
//  side learned the same lesson as a SIGSEGV inside `DetachCurrentThread` and fixed it
//  the same way: `threadCnt = 0`, one owning thread, everything else posts to it.
//
//  So this class owns a single pthread that is the only thread ever to enter pjsua, and
//  exposes an Objective-C surface that is safe to call from anywhere. Every method below
//  may be called from any thread, including the main one.
//
//  ## What it deliberately does NOT do
//
//  * It does not touch `AVAudioSession`. CallKit owns the audio session, activates it
//    when the system is ready, and tells the app in `provider(_:didActivate:)`. The
//    bridge waits to be told — see `activateAudioSession` / `deactivateAudioSession`.
//  * It does not decide policy. Retry backoff, which codec order to apply and what a
//    call is allowed to do next come from the shared Kotlin module, which Android uses
//    for the same decisions.
//  * It does not model state. It reports events; `SipEngine.swift` folds them.
//

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import "PjsipTypes.h"

NS_ASSUME_NONNULL_BEGIN

#pragma mark - Value objects

/// Everything needed to register one account. A plain carrier: the bridge does no
/// validation, because `com.whatsappv2.domain.validation.AccountValidator` in the shared
/// module already does it and doing it twice is how the two answers drift apart.
@interface WAVAccountConfig : NSObject
@property (nonatomic, copy)   NSString *accountId;        ///< the app's stable id, not pjsua's
@property (nonatomic, copy)   NSString *username;
@property (nonatomic, copy)   NSString *authUsername;
@property (nonatomic, copy)   NSString *password;
@property (nonatomic, copy)   NSString *domain;
@property (nonatomic, copy, nullable) NSString *displayName;
@property (nonatomic, copy, nullable) NSString *registrar;     ///< `host[:port]`; nil means `domain`
@property (nonatomic, copy, nullable) NSString *outboundProxy; ///< `host[:port]`
@property (nonatomic, assign) NSInteger port;                  ///< 0 means the transport default
@property (nonatomic, assign) WAVTransport transport;
@property (nonatomic, assign) NSInteger registrationExpirySeconds;
@property (nonatomic, assign) WAVSrtpPolicy srtpPolicy;
@property (nonatomic, copy, nullable) NSString *stunServer;
@property (nonatomic, assign) BOOL iceEnabled;
@property (nonatomic, assign) NSInteger keepaliveIntervalSeconds;
@end

/// One call, as the bridge sees it right now.
@interface WAVCallInfo : NSObject
@property (nonatomic, assign) NSInteger callId;         ///< pjsua's call id
@property (nonatomic, copy)   NSString *accountId;
@property (nonatomic, copy)   NSString *remoteUri;      ///< full SIP URI, for redial
@property (nonatomic, copy)   NSString *remoteDisplay;  ///< what to show; may equal the user part
@property (nonatomic, assign) WAVCallState state;
@property (nonatomic, assign) WAVHoldParty heldBy;
@property (nonatomic, assign) BOOL isIncoming;
@property (nonatomic, assign) BOOL hasVideo;            ///< a video stream is negotiated
@property (nonatomic, assign) BOOL isMuted;
@property (nonatomic, assign) NSInteger lastStatusCode; ///< the SIP response, when there was one
@property (nonatomic, assign) WAVHangupReason hangupReason;
@property (nonatomic, assign) NSTimeInterval connectedAt; ///< 0 until answered
@end

/// One codec pjsua actually compiled and registered, with its current priority.
/// Produced by `-enumerateAudioCodecs` / `-enumerateVideoCodecs`, which is what makes
/// `CodecAudit` in the shared module able to report "declared but not compiled" honestly
/// rather than guessing from `config_site.h`.
@interface WAVCodecInfo : NSObject
@property (nonatomic, copy)   NSString *identifier;  ///< e.g. "PCMU/8000/1", "VP8/97"
@property (nonatomic, copy)   NSString *name;        ///< e.g. "PCMU", "VP8"
@property (nonatomic, assign) NSInteger clockRate;
@property (nonatomic, assign) NSInteger channelCount;
@property (nonatomic, assign) NSInteger priority;    ///< 0 disables it; 255 is highest
@property (nonatomic, assign) BOOL isVideo;
@end

/// Media counters at a moment, for the in-call diagnostics screen and for the adaptive
/// video ladder in the shared module, which takes exactly these inputs.
@interface WAVMediaStats : NSObject
@property (nonatomic, assign) NSInteger rxPackets;
@property (nonatomic, assign) NSInteger txPackets;
@property (nonatomic, assign) NSInteger rxLostPackets;
@property (nonatomic, assign) NSInteger txLostPackets;
@property (nonatomic, assign) double    rxJitterMs;
@property (nonatomic, assign) double    roundTripMs;
@property (nonatomic, assign) NSInteger rxBytesPerSecond;
@property (nonatomic, assign) NSInteger txBytesPerSecond;
@property (nonatomic, assign) double    videoDecodeFps;
@property (nonatomic, assign) double    videoEncodeFps;
@property (nonatomic, assign) NSInteger videoRxWidth;
@property (nonatomic, assign) NSInteger videoRxHeight;
@end

#pragma mark - Events

/// Everything the stack reports. Every method is delivered on the **main queue**, so an
/// implementation may touch UI directly and a `@MainActor` Swift type can adopt it
/// without a hop. The bridge's own thread is never exposed.
@protocol PjsipBridgeDelegate <NSObject>

- (void)pjsipRegistrationDidChange:(NSString *)accountId
                             state:(WAVRegistrationState)state
                           failure:(WAVRegistrationFailure)failure
               grantedExpirySeconds:(NSInteger)grantedExpirySeconds
                        statusCode:(NSInteger)statusCode;

/// An INVITE arrived. The implementation MUST report it to CallKit essentially
/// immediately — iOS terminates an app that takes a PushKit VoIP wake and does not.
- (void)pjsipDidReceiveIncomingCall:(WAVCallInfo *)call;

- (void)pjsipCallDidChange:(WAVCallInfo *)call;

/// The call is over and will not be reported again. Carries the final info so the call
/// log can be written without racing a lookup against a call id pjsua has already reused.
- (void)pjsipCallDidEnd:(WAVCallInfo *)call;

/// Audio or video began or stopped flowing. Separate from `pjsipCallDidChange:` because
/// a call can be Connected with no media (a hold, a codec mismatch, a one-way NAT) and
/// the two must not be conflated — that conflation is what makes "connected but silent"
/// invisible in a log.
- (void)pjsipCallMediaDidChange:(WAVCallInfo *)call
                      audioLive:(BOOL)audioLive
                      videoLive:(BOOL)videoLive;

/// The far end offered or withdrew video mid-call.
- (void)pjsipCallDidReceiveVideoRequest:(NSInteger)callId wantsVideo:(BOOL)wantsVideo;

/// A remote video window is ready to be put on screen, or has gone away.
/// The view is pjsua's own and must not be retained beyond `windowId` being valid.
- (void)pjsipVideoWindowDidChange:(NSInteger)callId
                         windowId:(NSInteger)windowId
                             view:(nullable UIView *)view
                             size:(CGSize)size;

- (void)pjsipDidReceiveDtmf:(NSInteger)callId digit:(NSString *)digit;

- (void)pjsipTransferDidProgress:(NSInteger)callId
                      statusCode:(NSInteger)statusCode
                           final:(BOOL)isFinal;

/// A log line from PJSIP itself, already level-filtered. Routed rather than printed so
/// the SIP trace setting controls it, the way `PjsipTrace` does on Android.
- (void)pjsipDidLog:(NSString *)message level:(NSInteger)level;

@end

#pragma mark - The bridge

@interface PjsipBridge : NSObject

@property (nonatomic, weak, nullable) id<PjsipBridgeDelegate> delegate;

/// True between a successful `-startWithError:` and `-shutdown`.
@property (nonatomic, readonly, getter=isRunning) BOOL running;

/// `pjproject 2.17`, and the codecs this binary actually contains. Read from the library
/// rather than from a constant, so a build that silently lost a codec says so.
@property (nonatomic, readonly, copy) NSString *stackVersion;

+ (instancetype)shared;

#pragma mark Lifecycle

/// Creates the stack, opens the transports and starts the worker thread.
/// Idempotent: a second call while running succeeds and does nothing.
- (BOOL)startWithTransports:(NSArray<NSNumber *> *)transports   ///< boxed WAVTransport
                   logLevel:(NSInteger)logLevel
                      error:(NSError **)error
    NS_SWIFT_NAME(start(transports:logLevel:));

/// Unregisters every account, hangs up every call and destroys the stack. Blocks until
/// the worker thread has exited, because the alternative is pjsua tearing down its pools
/// underneath a callback that is still running.
- (void)shutdown;

#pragma mark Accounts

- (BOOL)addAccount:(WAVAccountConfig *)config error:(NSError **)error
    NS_SWIFT_NAME(addAccount(_:));
- (BOOL)updateAccount:(WAVAccountConfig *)config error:(NSError **)error
    NS_SWIFT_NAME(updateAccount(_:));
- (void)removeAccount:(NSString *)accountId;
- (void)setRegistration:(BOOL)wanted forAccount:(NSString *)accountId;

/// Re-sends REGISTER for every account that wants one. Called when the network comes
/// back and after a PushKit wake, because the binding the registrar still believes in
/// may point at an address this device no longer has.
- (void)refreshAllRegistrations;

#pragma mark Calls

/// `destination` may be a bare extension — it is completed against the account's domain
/// and its dialled port, the same rule `SipAccount.dialledPort` states for Android.
/// Returns the pjsua call id, or -1.
- (NSInteger)placeCallTo:(NSString *)destination
             fromAccount:(NSString *)accountId
                 profile:(WAVMediaProfile)profile
                   error:(NSError **)error
    NS_SWIFT_NAME(placeCall(to:fromAccount:profile:error:));

- (void)answerCall:(NSInteger)callId profile:(WAVMediaProfile)profile;
- (void)rejectCall:(NSInteger)callId statusCode:(NSInteger)statusCode;
- (void)hangupCall:(NSInteger)callId;
- (void)hangupAllCalls;

- (void)setHold:(BOOL)held forCall:(NSInteger)callId;
- (void)setMuted:(BOOL)muted forCall:(NSInteger)callId;
- (void)sendDtmf:(NSString *)digits forCall:(NSInteger)callId;

- (void)transferCall:(NSInteger)callId toDestination:(NSString *)destination;
- (void)attendedTransferCall:(NSInteger)callId toCall:(NSInteger)otherCallId;

- (nullable WAVCallInfo *)infoForCall:(NSInteger)callId NS_SWIFT_NAME(info(forCall:));
- (NSArray<WAVCallInfo *> *)activeCalls;

#pragma mark Local mixing (the conference, ADR-009)

/// Connects every listed call to every other through pjmedia's conference bridge, so the
/// mix happens on this device rather than on the server. `acrossTheMix` on Android is the
/// same operation; both exist because a server-side conference costs a relay hop and the
/// deployed FreeSWITCH drops video when it bridges.
- (BOOL)mixCalls:(NSArray<NSNumber *> *)callIds;
- (void)unmixCall:(NSInteger)callId;

#pragma mark Media

/// CallKit activated the audio session; open the sound device now and not before.
- (void)activateAudioSession;
/// CallKit deactivated it. The device is closed, and the conference bridge keeps running
/// on a null port — which is what `PJSUA_DONT_RELEASE_SND_DEV` in config_site.h buys.
- (void)deactivateAudioSession;

- (void)setVideoEnabled:(BOOL)enabled forCall:(NSInteger)callId;
- (void)switchCameraForCall:(NSInteger)callId NS_SWIFT_NAME(switchCamera(forCall:));
- (void)respondToVideoRequest:(NSInteger)callId accept:(BOOL)accept;

/// The local camera preview, as a view to put on screen. Nil when the camera is not open.
- (nullable UIView *)localPreviewView NS_SWIFT_NAME(localPreviewView());
- (void)startLocalPreview;
- (void)stopLocalPreview;

- (nullable WAVMediaStats *)statsForCall:(NSInteger)callId NS_SWIFT_NAME(stats(forCall:));

#pragma mark Codecs

- (NSArray<WAVCodecInfo *> *)enumerateAudioCodecs;
- (NSArray<WAVCodecInfo *> *)enumerateVideoCodecs;
/// `priority` 0 removes the codec from the offer entirely; 255 puts it first.
- (void)setCodecPriority:(NSInteger)priority forCodec:(NSString *)identifier;
/// Applies an ordered preference list: first entry gets the highest priority, anything
/// not named is disabled. One call, so the SDP offer cannot be observed half-applied.
- (void)applyAudioCodecOrder:(NSArray<NSString *> *)codecNames;
- (void)applyVideoCodecOrder:(NSArray<NSString *> *)codecNames;

/// The encoder's target frame rate, applied at a stream boundary. Mid-call reconfiguration
/// is NOT possible — pjmedia has no API to reconfigure a running video stream, and an
/// autonomous re-INVITE races the user's own and freezes media — so this takes effect on
/// the next stream that starts.
- (void)setVideoFrameRate:(NSInteger)fps;
- (void)setVideoBitrateKbps:(NSInteger)kbps;

#pragma mark Push

/// The PushKit VoIP token, formatted for the `pn-*` Contact parameters the IMS reads.
/// Passing nil clears it, which is what logout must do: a binding that still carries a
/// token wakes a device for a call it can no longer answer.
- (void)setPushToken:(nullable NSString *)token
            provider:(NSString *)provider
            bundleId:(NSString *)bundleId
    NS_SWIFT_NAME(setPushToken(_:provider:bundleId:));

@end

NS_ASSUME_NONNULL_END
