//
//  PjsipBridge.mm
//
//  Objective-C++ over pjsua's C API. See PjsipBridge.h for why this layer exists at all.
//
//  ## The thread rule, which is the whole design
//
//  PJLIB keeps per-thread state and every pjsua entry point asserts that the calling
//  thread has it. There are two ways to satisfy that and only one of them is safe here:
//
//    1. Let pjsua spawn worker threads (`thread_cnt > 0`) and register any other thread
//       that calls in with `pj_thread_register`. This is upstream's default and it is
//       what the Android side did until 2026-09-13, when it crashed inside
//       `DetachCurrentThread`: the thread-local descriptor PJLIB allocates outlives the
//       thread that owns it, and a pool of threads — GCD's, or the JVM's — recycles the
//       OS thread underneath it.
//
//    2. `thread_cnt = 0`, one thread of our own that pumps `pjsua_handle_events`, and
//       every call into pjsua posted to that thread. Nothing else ever enters the stack,
//       so nothing else ever needs registering.
//
//  This file does (2), which is also what `PjsipEventPump` does on Android. `pj_sync`
//  and `pj_async` below are the only two ways in, and every public method is one of them
//  wrapping a block.
//
//  ## Callbacks
//
//  With no worker threads, every pjsua callback arrives on the pump thread. They are
//  re-dispatched to the main queue before reaching the delegate, so Swift never sees the
//  pump thread and `@MainActor` types need no hop of their own.
//

#import "PjsipBridge.h"

#import <AVFoundation/AVFoundation.h>
#import <pthread.h>

#include <pjsua-lib/pjsua.h>
#include <pjmedia/vid_codec.h>

#include <string>
#include <vector>

// ---------------------------------------------------------------- small helpers

static NSString *const kPjsipErrorDomain = @"com.whatsappv2.pjsip";

/// pjsua reserves -1 for "no such call/account", and the API returns it rather than an
/// error code in several places. Named so a reader does not have to recognise the -1.
static const pjsua_call_id kNoCall = PJSUA_INVALID_ID;

static inline pj_str_t PjStr(NSString *s) {
    // pj_str() does not copy, so the backing bytes must outlive the pj_str_t. Every use
    // in this file passes the result straight into a pjsua call that copies it into its
    // own pool before returning, which is the only pattern that is safe without a pool
    // of our own.
    return pj_str((char *)[s UTF8String]);
}

static inline NSString *NsStr(const pj_str_t *s) {
    if (!s || !s->ptr || s->slen <= 0) return @"";
    return [[NSString alloc] initWithBytes:s->ptr length:(NSUInteger)s->slen
                                  encoding:NSUTF8StringEncoding] ?: @"";
}

static NSError *PjError(pj_status_t status, NSString *what) {
    char buf[PJ_ERR_MSG_SIZE];
    pj_strerror(status, buf, sizeof(buf));
    return [NSError errorWithDomain:kPjsipErrorDomain
                               code:status
                           userInfo:@{ NSLocalizedDescriptionKey:
                                           [NSString stringWithFormat:@"%@: %s (%d)", what, buf, status] }];
}

/// The SIP response code a registration failed with, mapped to the reason the UI shows.
///
/// This mapping is `RegistrationFailure` in the shared Kotlin module, restated here
/// because the bridge has to produce the value before any Kotlin runs. The two must
/// agree; `RegistrationFailure.requiresUserAction` is what decides whether the retry
/// policy backs off or stops, so a 403 classified as a timeout would retry for ever.
static WAVRegistrationFailure FailureForStatus(int code, pj_status_t lastErr) {
    switch (code) {
        case 401: case 407: return WAVRegistrationFailureAuthenticationFailed;
        case 403: case 404: return WAVRegistrationFailureAccountRejected;
        case 408:           return WAVRegistrationFailureTimeout;
        case 502: case 503: case 504: return WAVRegistrationFailureServerUnavailable;
        default: break;
    }
    if (code >= 500) return WAVRegistrationFailureServerUnavailable;
    if (code >= 400) return WAVRegistrationFailureAccountRejected;
    // No response at all. PJSIP reports a transport problem as a pj_status_t in the
    // PJSIP_TLS / PJ_ERRNO socket ranges rather than as a SIP code, and "the Wi-Fi is
    // off" must not read as "the server rejected you".
    if (lastErr != PJ_SUCCESS) {
        // The TLS errors are a contiguous block pjproject numbers from
        // PJSIP_TLS_EUNKNOWN (PJSIP_ERRNO_START_PJSIP + 160) upwards; sip_errno.h
        // defines each member and no START/END pair for the range, so the bounds are
        // named from the first and last members rather than invented.
        if (lastErr >= PJSIP_TLS_EUNKNOWN && lastErr <= PJSIP_TLS_ECERTVERIF) {
            return WAVRegistrationFailureTransportFailure;
        }
        return WAVRegistrationFailureNetworkUnavailable;
    }
    return WAVRegistrationFailureTransportFailure;
}

/// The SIP response that ended a call, mapped to why it ended.
static WAVHangupReason HangupForStatus(int code, BOOL localHangup) {
    if (localHangup) return WAVHangupReasonLocalHangup;
    switch (code) {
        case 200: case 0:  return WAVHangupReasonRemoteHangup;
        case 486: case 600: return WAVHangupReasonBusy;
        case 404: case 484: return WAVHangupReasonNotFound;
        case 408: case 480: return WAVHangupReasonNoAnswer;
        case 487:           return WAVHangupReasonCancelled;
        case 603:           return WAVHangupReasonRejected;
        default: break;
    }
    if (code >= 500) return WAVHangupReasonServerError;
    if (code >= 400) return WAVHangupReasonRejected;
    return WAVHangupReasonRemoteHangup;
}

#pragma mark - Value objects

@implementation WAVAccountConfig
- (instancetype)init {
    if ((self = [super init])) {
        _registrationExpirySeconds = 180;   // SipAccount.DEFAULT_EXPIRY_SECONDS
        _keepaliveIntervalSeconds  = 15;
        _transport  = WAVTransportUDP;
        _srtpPolicy = WAVSrtpPolicyDisabled;
        _accountId = @""; _username = @""; _authUsername = @""; _password = @""; _domain = @"";
    }
    return self;
}
@end

@implementation WAVCallInfo
- (instancetype)init {
    if ((self = [super init])) {
        _callId = kNoCall; _accountId = @""; _remoteUri = @""; _remoteDisplay = @"";
        _state = WAVCallStateIdle; _heldBy = WAVHoldPartyNone;
        _lastStatusCode = 0; _hangupReason = WAVHangupReasonLocalHangup;
    }
    return self;
}
@end

@implementation WAVCodecInfo
- (instancetype)init {
    if ((self = [super init])) { _identifier = @""; _name = @""; }
    return self;
}
@end

@implementation WAVMediaStats
@end

#pragma mark - The bridge

/// One command queued for the pump thread.
typedef void (^PjBlock)(void);

@interface PjsipBridge () {
    pthread_t        _pumpThread;
    pthread_mutex_t  _queueLock;
    pthread_cond_t   _queueSignal;
    NSMutableArray<PjBlock> *_queue;        // guarded by _queueLock
    volatile BOOL    _pumpShouldRun;
    volatile BOOL    _pumpExited;
}
/// app account id -> pjsua_acc_id. Mutated only on the pump thread; read under
/// `_mapLock` from anywhere, because `-infoForCall:` is synchronous by design.
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *accountIds;
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, NSString *> *accountIdsReverse;
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, WAVCallInfo *> *calls;
@property (nonatomic, strong) NSLock *mapLock;
@property (nonatomic, assign) BOOL running;
@property (nonatomic, assign) BOOL audioSessionActive;
@property (nonatomic, assign) NSInteger preferredVideoFps;
@property (nonatomic, assign) NSInteger preferredVideoKbps;
@property (nonatomic, assign) pjmedia_vid_dev_index captureDevice;
@end

/// The one instance the C callbacks reach. `__weak` would be wrong: the callbacks run on
/// the pump thread while `-shutdown` runs on another, and a weak read racing dealloc is
/// precisely the window this avoids. The singleton outlives the process instead.
static PjsipBridge *gBridge = nil;

@implementation PjsipBridge

+ (instancetype)shared {
    static dispatch_once_t once;
    static PjsipBridge *instance;
    dispatch_once(&once, ^{ instance = [[PjsipBridge alloc] init]; gBridge = instance; });
    return instance;
}

- (instancetype)init {
    if ((self = [super init])) {
        _queue = [NSMutableArray array];
        _accountIds = [NSMutableDictionary dictionary];
        _accountIdsReverse = [NSMutableDictionary dictionary];
        _calls = [NSMutableDictionary dictionary];
        _mapLock = [[NSLock alloc] init];
        _captureDevice = PJMEDIA_VID_DEFAULT_CAPTURE_DEV;
        _preferredVideoFps = 20;
        _preferredVideoKbps = 512;
        pthread_mutex_init(&_queueLock, NULL);
        pthread_cond_init(&_queueSignal, NULL);
    }
    return self;
}

#pragma mark - The pump thread

/// Posts a block and returns immediately. Used for everything the caller does not need
/// an answer from, which is most of the API: a hangup that has been queued will happen.
- (void)pj_async:(PjBlock)block {
    if (!block) return;
    pthread_mutex_lock(&_queueLock);
    [_queue addObject:[block copy]];
    pthread_cond_signal(&_queueSignal);
    pthread_mutex_unlock(&_queueLock);
}

/// Posts a block and waits for it. Only for the handful of calls that must return a
/// value — `-placeCallTo:...` returns a call id, `-enumerateAudioCodecs` a list.
///
/// Deadlocks if called FROM the pump thread, so it checks: a callback that reaches a
/// public method would otherwise hang the stack for ever, and that bug presents as "the
/// app froze during a call" with no stack trace worth reading.
- (void)pj_sync:(PjBlock)block {
    if (!block) return;
    if (pthread_equal(pthread_self(), _pumpThread)) { block(); return; }

    __block BOOL done = NO;
    NSCondition *finished = [[NSCondition alloc] init];
    [self pj_async:^{
        block();
        [finished lock]; done = YES; [finished signal]; [finished unlock];
    }];
    [finished lock];
    // A bounded wait, not a bare one. If the stack has wedged — and a SIP stack talking
    // to an unreachable registrar over TCP can block inside a socket call — the caller
    // gets control back rather than taking the UI down with it.
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:5.0];
    while (!done && [finished waitUntilDate:deadline]) { /* re-check */ }
    [finished unlock];
}

static void *PumpMain(void *context) {
    pthread_setname_np("com.whatsappv2.pjsip");
    PjsipBridge *bridge = (__bridge PjsipBridge *)context;
    [bridge runPump];
    return NULL;
}

- (void)runPump {
    // This thread, and only this thread, is inside pjsua. It is registered once here;
    // nothing else ever needs registering, which is the point of thread_cnt = 0.
    pj_thread_desc desc;
    pj_thread_t *thread = NULL;
    pj_bzero(desc, sizeof(desc));
    if (!pj_thread_is_registered()) {
        pj_thread_register("wav-pump", desc, &thread);
    }

    while (_pumpShouldRun) {
        // Drain every queued command before pumping events, so a burst of UI actions is
        // applied in one pass rather than one per 10 ms tick.
        for (;;) {
            PjBlock next = nil;
            pthread_mutex_lock(&_queueLock);
            if (_queue.count > 0) { next = _queue.firstObject; [_queue removeObjectAtIndex:0]; }
            pthread_mutex_unlock(&_queueLock);
            if (!next) break;
            @autoreleasepool { next(); }
        }

        // 10 ms. Long enough that the thread is asleep almost all the time — the idle
        // cost measured on Android for the same loop is below the noise floor of
        // /proc schedstat — and short enough that a 180 Ringing is not perceptibly late.
        pjsua_handle_events(10);
    }

    _pumpExited = YES;
}

#pragma mark - pjsua callbacks
//
// Every one of these runs on the pump thread. Each builds a plain Objective-C value and
// hands it to the main queue; none of them touches UIKit or calls back into pjsua
// synchronously, because a pjsua callback that re-enters pjsua is how the stack's own
// mutexes deadlock.

static void cb_on_reg_state2(pjsua_acc_id acc_id, pjsua_reg_info *info) {
    PjsipBridge *bridge = gBridge;
    if (!bridge) return;
    [bridge handleRegState:acc_id info:info];
}

static void cb_on_incoming_call(pjsua_acc_id acc_id, pjsua_call_id call_id, pjsip_rx_data *rdata) {
    (void)rdata;
    PjsipBridge *bridge = gBridge;
    if (!bridge) return;
    [bridge handleIncomingCall:acc_id call:call_id];
}

static void cb_on_call_state(pjsua_call_id call_id, pjsip_event *e) {
    (void)e;
    PjsipBridge *bridge = gBridge;
    if (!bridge) return;
    [bridge handleCallState:call_id];
}

static void cb_on_call_media_state(pjsua_call_id call_id) {
    PjsipBridge *bridge = gBridge;
    if (!bridge) return;
    [bridge handleCallMediaState:call_id];
}

static void cb_on_dtmf_digit(pjsua_call_id call_id, int digit) {
    PjsipBridge *bridge = gBridge;
    if (!bridge) return;
    NSString *d = [NSString stringWithFormat:@"%c", (char)digit];
    dispatch_async(dispatch_get_main_queue(), ^{
        [bridge.delegate pjsipDidReceiveDtmf:call_id digit:d];
    });
}

static void cb_on_call_transfer_status(pjsua_call_id call_id, int st_code,
                                       const pj_str_t *st_text, pj_bool_t final,
                                       pj_bool_t *p_cont) {
    (void)st_text;
    PjsipBridge *bridge = gBridge;
    if (!bridge) return;
    // Keep receiving NOTIFYs until the transfer is final — the default is to stop, and
    // stopping early means a transfer that later fails is reported as having succeeded.
    *p_cont = final ? PJ_FALSE : PJ_TRUE;
    BOOL isFinal = (final != 0);
    dispatch_async(dispatch_get_main_queue(), ^{
        [bridge.delegate pjsipTransferDidProgress:call_id statusCode:st_code final:isFinal];
    });
}

static void cb_on_call_media_event(pjsua_call_id call_id, unsigned med_idx,
                                   pjmedia_event *event) {
    (void)med_idx;
    PjsipBridge *bridge = gBridge;
    if (!bridge || !event) return;
    // PJMEDIA_EVENT_FMT_CHANGED is the only one the UI needs: it is the moment the
    // decoder learns the real frame size, and the window dimensions reported before it
    // are the placeholder ones. Reading the window instead of the event is what makes
    // the first frame land at the right aspect ratio rather than being letterboxed.
    if (event->type == PJMEDIA_EVENT_FMT_CHANGED) {
        [bridge refreshVideoWindowForCall:call_id];
    }
}

static void cb_on_log(int level, const char *data, int len) {
    PjsipBridge *bridge = gBridge;
    if (!bridge || !data || len <= 0) return;
    NSString *line = [[NSString alloc] initWithBytes:data length:(NSUInteger)len
                                            encoding:NSUTF8StringEncoding];
    if (!line) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        [bridge.delegate pjsipDidLog:line level:level];
    });
}

#pragma mark - Callback bodies (pump thread)

- (void)handleRegState:(pjsua_acc_id)accId info:(pjsua_reg_info *)info {
    NSString *appId = [self appAccountIdFor:accId];
    if (!appId) return;

    pjsua_acc_info acc;
    if (pjsua_acc_get_info(accId, &acc) != PJ_SUCCESS) return;

    int code = info && info->cbparam ? info->cbparam->code : acc.status;
    pj_status_t lastErr = info && info->cbparam ? info->cbparam->status : PJ_SUCCESS;

    WAVRegistrationState state;
    WAVRegistrationFailure failure = WAVRegistrationFailureNone;
    NSInteger granted = 0;

    if (acc.has_registration && code >= 200 && code < 300 && acc.expires > 0) {
        state = WAVRegistrationStateRegistered;
        // The expiry the SERVER granted, which may be lower than the one requested. Every
        // refresh timer is derived from this and never from the request — a client that
        // refreshes on its own requested value against a registrar that halved it spends
        // half of every binding unregistered.
        granted = acc.expires;
    } else if (code == 0 && lastErr == PJ_SUCCESS && acc.has_registration) {
        state = WAVRegistrationStateRegistering;
    } else if (!acc.has_registration || acc.expires == 0) {
        state = (code >= 400 || lastErr != PJ_SUCCESS)
              ? WAVRegistrationStateFailed : WAVRegistrationStateUnregistered;
        if (state == WAVRegistrationStateFailed) failure = FailureForStatus(code, lastErr);
    } else {
        state = WAVRegistrationStateFailed;
        failure = FailureForStatus(code, lastErr);
    }

    __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        [weakSelf.delegate pjsipRegistrationDidChange:appId
                                                state:state
                                              failure:failure
                                 grantedExpirySeconds:granted
                                           statusCode:code];
    });
}

- (void)handleIncomingCall:(pjsua_acc_id)accId call:(pjsua_call_id)callId {
    WAVCallInfo *info = [self buildInfoForCall:callId];
    if (!info) return;
    info.isIncoming = YES;
    info.accountId = [self appAccountIdFor:accId] ?: @"";
    info.state = WAVCallStateIncoming;

    [self.mapLock lock];
    self.calls[@(callId)] = info;
    [self.mapLock unlock];

    // 180 Ringing, immediately and before anything else. Without it the caller hears
    // nothing until the user picks up, and a FreeSWITCH that never saw a 180 will not
    // generate ringback either — which is the "silent until answered" defect the
    // push-wake path exposed on Android.
    pjsua_call_answer(callId, 180, NULL, NULL);

    __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        [weakSelf.delegate pjsipDidReceiveIncomingCall:info];
    });
}

- (void)handleCallState:(pjsua_call_id)callId {
    pjsua_call_info ci;
    if (pjsua_call_get_info(callId, &ci) != PJ_SUCCESS) return;

    WAVCallInfo *info = [self buildInfoForCall:callId];
    if (!info) return;

    [self.mapLock lock];
    WAVCallInfo *previous = self.calls[@(callId)];
    if (previous) {
        info.isIncoming = previous.isIncoming;
        info.isMuted    = previous.isMuted;
        info.heldBy     = previous.heldBy;
        if (previous.accountId.length) info.accountId = previous.accountId;
        if (previous.connectedAt > 0)  info.connectedAt = previous.connectedAt;
    }
    [self.mapLock unlock];

    if (ci.state == PJSIP_INV_STATE_CONFIRMED && info.connectedAt == 0) {
        info.connectedAt = [[NSDate date] timeIntervalSince1970];
    }

    if (ci.state == PJSIP_INV_STATE_DISCONNECTED) {
        info.state = WAVCallStateTerminated;
        info.lastStatusCode = ci.last_status;
        // `last_status` is the response that ended it either way, so "who hung up" comes
        // from the role and the code together: a 200 on a BYE we sent is a local hangup,
        // the same 200 on one we received is a remote one.
        BOOL localHangup = (ci.last_status == 200 && ci.role == PJSIP_ROLE_UAC && ci.connect_duration.sec > 0);
        info.hangupReason = HangupForStatus(ci.last_status, localHangup);

        [self.mapLock lock];
        [self.calls removeObjectForKey:@(callId)];
        [self.mapLock unlock];

        __weak typeof(self) weakSelf = self;
        dispatch_async(dispatch_get_main_queue(), ^{
            [weakSelf.delegate pjsipCallDidEnd:info];
        });
        return;
    }

    [self.mapLock lock];
    self.calls[@(callId)] = info;
    [self.mapLock unlock];

    __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        [weakSelf.delegate pjsipCallDidChange:info];
    });
}

- (void)handleCallMediaState:(pjsua_call_id)callId {
    pjsua_call_info ci;
    if (pjsua_call_get_info(callId, &ci) != PJ_SUCCESS) return;

    BOOL audioLive = NO, videoLive = NO;

    for (unsigned i = 0; i < ci.media_cnt; ++i) {
        if (ci.media[i].type == PJMEDIA_TYPE_AUDIO &&
            (ci.media[i].status == PJSUA_CALL_MEDIA_ACTIVE ||
             ci.media[i].status == PJSUA_CALL_MEDIA_REMOTE_HOLD)) {
            // Connect the call's audio to the conference bridge in both directions.
            // Slot 0 is the sound device. Doing this per media index rather than once
            // per call is what makes a second call join the same local mix instead of
            // replacing the first — the mechanism the whole local conference rests on.
            pjsua_conf_connect(ci.media[i].stream.aud.conf_slot, 0);
            pjsua_conf_connect(0, ci.media[i].stream.aud.conf_slot);
            if (ci.media[i].status == PJSUA_CALL_MEDIA_ACTIVE) audioLive = YES;
        }
        if (ci.media[i].type == PJMEDIA_TYPE_VIDEO &&
            ci.media[i].status == PJSUA_CALL_MEDIA_ACTIVE) {
            videoLive = YES;
        }
    }

    WAVCallInfo *info = [self buildInfoForCall:callId];
    if (!info) return;

    [self.mapLock lock];
    WAVCallInfo *previous = self.calls[@(callId)];
    if (previous) {
        info.isIncoming = previous.isIncoming;
        info.isMuted    = previous.isMuted;
        info.connectedAt = previous.connectedAt;
        if (previous.accountId.length) info.accountId = previous.accountId;
    }
    self.calls[@(callId)] = info;
    [self.mapLock unlock];

    // Re-apply mute after any media change. A re-INVITE rebuilds the conference port and
    // its transmit level goes back to 1.0, so a call muted before a hold comes back
    // unmuted — visibly to the other party and invisibly to this one.
    if (info.isMuted) [self applyMute:YES toCall:callId];

    if (videoLive) [self refreshVideoWindowForCall:callId];

    __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        [weakSelf.delegate pjsipCallMediaDidChange:info audioLive:audioLive videoLive:videoLive];
    });
}

- (void)refreshVideoWindowForCall:(pjsua_call_id)callId {
    pjsua_call_info ci;
    if (pjsua_call_get_info(callId, &ci) != PJ_SUCCESS) return;

    for (unsigned i = 0; i < ci.media_cnt; ++i) {
        if (ci.media[i].type != PJMEDIA_TYPE_VIDEO) continue;
        if (ci.media[i].dir == PJMEDIA_DIR_NONE) continue;

        pjsua_vid_win_id wid = ci.media[i].stream.vid.win_in;
        if (wid == PJSUA_INVALID_ID) continue;

        pjsua_vid_win_info wi;
        if (pjsua_vid_win_get_info(wid, &wi) != PJ_SUCCESS) continue;

        UIView *view = (__bridge UIView *)wi.hwnd.info.ios.window;
        CGSize size = CGSizeMake(wi.size.w, wi.size.h);
        __weak typeof(self) weakSelf = self;
        dispatch_async(dispatch_get_main_queue(), ^{
            [weakSelf.delegate pjsipVideoWindowDidChange:callId
                                                windowId:wid
                                                    view:view
                                                    size:size];
        });
        return;
    }

    // No video window any more — the far end removed the stream, or the call is ending.
    __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        [weakSelf.delegate pjsipVideoWindowDidChange:callId
                                            windowId:PJSUA_INVALID_ID
                                                view:nil
                                                size:CGSizeZero];
    });
}

#pragma mark - Mapping helpers

- (nullable NSString *)appAccountIdFor:(pjsua_acc_id)accId {
    [self.mapLock lock];
    NSString *appId = self.accountIdsReverse[@(accId)];
    [self.mapLock unlock];
    return appId;
}

- (pjsua_acc_id)pjAccountIdFor:(NSString *)appId {
    [self.mapLock lock];
    NSNumber *boxed = self.accountIds[appId];
    [self.mapLock unlock];
    return boxed ? (pjsua_acc_id)boxed.intValue : PJSUA_INVALID_ID;
}

/// Reads the current pjsua state for one call into a value object. Pump thread only.
- (nullable WAVCallInfo *)buildInfoForCall:(pjsua_call_id)callId {
    pjsua_call_info ci;
    if (pjsua_call_get_info(callId, &ci) != PJ_SUCCESS) return nil;

    WAVCallInfo *info = [[WAVCallInfo alloc] init];
    info.callId = callId;
    info.remoteUri = NsStr(&ci.remote_info);
    info.remoteDisplay = NsStr(&ci.remote_contact).length ? NsStr(&ci.remote_info)
                                                          : NsStr(&ci.remote_info);
    info.accountId = [self appAccountIdFor:ci.acc_id] ?: @"";
    info.lastStatusCode = ci.last_status;

    switch (ci.state) {
        case PJSIP_INV_STATE_NULL:        info.state = WAVCallStateIdle; break;
        case PJSIP_INV_STATE_CALLING:     info.state = WAVCallStateCalling; break;
        case PJSIP_INV_STATE_INCOMING:    info.state = WAVCallStateIncoming; break;
        case PJSIP_INV_STATE_EARLY:
            // 180 and 183 are the same INVITE state and a very different user
            // experience: 183 carries SDP and means the far end is already sending
            // audio — a network announcement, or ringback generated upstream — which
            // must be played rather than replaced with a local ring tone.
            info.state = (ci.last_status == 183) ? WAVCallStateEarlyMedia : WAVCallStateRinging;
            break;
        case PJSIP_INV_STATE_CONNECTING:  info.state = WAVCallStateConnecting; break;
        case PJSIP_INV_STATE_CONFIRMED:   info.state = WAVCallStateConnected; break;
        case PJSIP_INV_STATE_DISCONNECTED:info.state = WAVCallStateTerminated; break;
        default: info.state = WAVCallStateIdle; break;
    }

    for (unsigned i = 0; i < ci.media_cnt; ++i) {
        if (ci.media[i].type == PJMEDIA_TYPE_VIDEO && ci.media[i].dir != PJMEDIA_DIR_NONE) {
            info.hasVideo = YES;
        }
        if (ci.media[i].type == PJMEDIA_TYPE_AUDIO) {
            // LOCAL_HOLD and REMOTE_HOLD are separate statuses and both can be true at
            // once. Collapsing them into one boolean is how "resume did nothing" bugs
            // happen: the local side resumes, the remote hold is still in force, and the
            // UI says Connected over silence.
            if (ci.media[i].status == PJSUA_CALL_MEDIA_LOCAL_HOLD) {
                info.heldBy = (info.heldBy == WAVHoldPartyRemote) ? WAVHoldPartyBoth : WAVHoldPartyLocal;
            } else if (ci.media[i].status == PJSUA_CALL_MEDIA_REMOTE_HOLD) {
                info.heldBy = (info.heldBy == WAVHoldPartyLocal) ? WAVHoldPartyBoth : WAVHoldPartyRemote;
            }
        }
    }
    return info;
}

#pragma mark - Lifecycle

- (NSString *)stackVersion {
    return [NSString stringWithFormat:@"pjproject %s", pj_get_version()];
}

- (BOOL)startWithTransports:(NSArray<NSNumber *> *)transports
                   logLevel:(NSInteger)logLevel
                      error:(NSError **)error {
    if (self.running) return YES;

    __block pj_status_t status = PJ_SUCCESS;
    __block NSString *failedAt = nil;

    // The stack is created on the thread that will own it, so `pjsua_create`'s own
    // thread registration is the pump's. Starting the pump first and configuring from
    // inside it is the only ordering in which no other thread ever touches pjsua.
    _pumpShouldRun = YES;
    _pumpExited = NO;
    if (pthread_create(&_pumpThread, NULL, PumpMain, (__bridge void *)self) != 0) {
        if (error) *error = PjError(PJ_EUNKNOWN, @"could not start the PJSIP worker thread");
        return NO;
    }

    __weak typeof(self) weakSelf = self;
    [self pj_sync:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;

        status = pjsua_create();
        if (status != PJ_SUCCESS) { failedAt = @"pjsua_create"; return; }

        pjsua_config ua_cfg;
        pjsua_logging_config log_cfg;
        pjsua_media_config media_cfg;
        pjsua_config_default(&ua_cfg);
        pjsua_logging_config_default(&log_cfg);
        pjsua_media_config_default(&media_cfg);

        // NO WORKER THREADS. See the file header: this is the invariant the whole
        // design rests on, and raising it re-opens the crash it closed on Android.
        ua_cfg.thread_cnt = 0;

        // Stated rather than left to config_site.h. PJSUA_MAX_CALLS is only the
        // compile-time default for this field, and raising the macro alone left pjsua
        // answering the fifth INVITE `486 Busy Here` on Android.
        ua_cfg.max_calls = PJSUA_MAX_CALLS;

        ua_cfg.cb.on_reg_state2           = &cb_on_reg_state2;
        ua_cfg.cb.on_incoming_call        = &cb_on_incoming_call;
        ua_cfg.cb.on_call_state           = &cb_on_call_state;
        ua_cfg.cb.on_call_media_state     = &cb_on_call_media_state;
        ua_cfg.cb.on_dtmf_digit           = &cb_on_dtmf_digit;
        ua_cfg.cb.on_call_transfer_status = &cb_on_call_transfer_status;
        ua_cfg.cb.on_call_media_event     = &cb_on_call_media_event;

        // The same User-Agent the Android client announces, and it must STAY the same:
        // the deployed dialplan matches on it, and FreeSWITCH rewrites the peer's
        // User-Agent when it B2BUAs a leg, which is how the mesh-ack check learned not
        // to trust it.
        //
        // `coraluc-v2` tracks `RealPjsipCoreGateway.USER_AGENT`, which main moved from
        // `coraluc` in 5a3aedc8. The rename is safe because the dialplan matches a
        // PREFIX — `coraluc-v2` still contains `coraluc`, so a v2 build and an older one
        // are both recognised and no handset in the field is stranded. The two platforms
        // must not drift: an iPhone announcing `coraluc` while the dialplan has moved on
        // is a device the server stops treating as ours.
        ua_cfg.user_agent = pj_str((char *)"coraluc-v2");

        log_cfg.level        = (unsigned)logLevel;
        log_cfg.console_level = 0;              // nothing to stdout; the delegate routes it
        log_cfg.cb           = &cb_on_log;

        // Software echo cancellation OFF: CoreAudio's VoiceProcessingIO unit does it in
        // hardware and running both produces audible pumping. config_site.h sets
        // PJMEDIA_HAS_SPEEX_AEC 0 for the same reason; this is the run-time half.
        media_cfg.ec_tail_len = 0;
        media_cfg.enable_ice  = PJ_TRUE;
        media_cfg.snd_auto_close_time = -1;     // never close it behind CallKit's back
        media_cfg.no_vad = PJ_FALSE;

        status = pjsua_init(&ua_cfg, &log_cfg, &media_cfg);
        if (status != PJ_SUCCESS) { failedAt = @"pjsua_init"; return; }

        for (NSNumber *boxed in transports) {
            pjsip_transport_type_e type;
            switch ((WAVTransport)boxed.integerValue) {
                case WAVTransportTCP: type = PJSIP_TRANSPORT_TCP; break;
                case WAVTransportTLS: type = PJSIP_TRANSPORT_TLS; break;
                case WAVTransportUDP:
                default:              type = PJSIP_TRANSPORT_UDP; break;
            }
            pjsua_transport_config tcfg;
            pjsua_transport_config_default(&tcfg);
            tcfg.port = 0;      // ephemeral: a fixed port collides with a second instance
            pj_status_t tstatus = pjsua_transport_create(type, &tcfg, NULL);
            if (tstatus != PJ_SUCCESS) {
                // One transport failing is not fatal — TLS is unavailable on a build
                // without OpenSSL and the app should still register over UDP — but it
                // must be visible, because "TLS accounts silently never register" is
                // exactly the failure config_site.h's check exists to prevent.
                char buf[PJ_ERR_MSG_SIZE];
                pj_strerror(tstatus, buf, sizeof(buf));
                PJ_LOG(1, ("wav", "transport %d could not be created: %s", (int)type, buf));
            }
        }

        status = pjsua_start();
        if (status != PJ_SUCCESS) { failedAt = @"pjsua_start"; return; }

        // No sound device until CallKit says so. `pjsua_set_no_snd_dev` leaves the
        // conference bridge running against a null port, which is what lets a call
        // negotiate and connect before the audio session exists — the ordering CallKit
        // imposes and the one a stack that opens the device itself gets wrong.
        pjsua_set_no_snd_dev();

        [strongSelf applyVideoDefaults];
    }];

    if (status != PJ_SUCCESS) {
        _pumpShouldRun = NO;
        pthread_cond_signal(&_queueSignal);
        pthread_join(_pumpThread, NULL);
        if (error) *error = PjError(status, failedAt ?: @"pjsua");
        return NO;
    }

    self.running = YES;
    return YES;
}

- (void)shutdown {
    if (!self.running) return;
    self.running = NO;

    [self pj_sync:^{
        pjsua_call_hangup_all();
        // Give the BYEs a chance onto the wire. pjsua_destroy tears the transports down
        // and anything still queued is lost, which leaves the registrar holding a
        // binding for a device that has gone — and routing the next call into silence.
        for (int i = 0; i < 20; ++i) pjsua_handle_events(10);
        pjsua_destroy();
    }];

    _pumpShouldRun = NO;
    pthread_mutex_lock(&_queueLock);
    pthread_cond_signal(&_queueSignal);
    pthread_mutex_unlock(&_queueLock);
    pthread_join(_pumpThread, NULL);

    [self.mapLock lock];
    [self.accountIds removeAllObjects];
    [self.accountIdsReverse removeAllObjects];
    [self.calls removeAllObjects];
    [self.mapLock unlock];
}

#pragma mark - Accounts

/// Fills a pjsua_acc_config from our carrier. Pump thread only — the pj_str_t values
/// point into the NSStrings, so `config` must outlive the pjsua call, which it does
/// because the caller holds it for the duration of the block.
- (void)fill:(pjsua_acc_config *)acc fromConfig:(WAVAccountConfig *)config
      strings:(std::vector<std::string> *)keep {
    pjsua_acc_config_default(acc);

    const char *scheme = (config.transport == WAVTransportTLS) ? "sips" : "sip";
    NSInteger port = config.port > 0 ? config.port
                   : (config.transport == WAVTransportTLS ? 5061 : 5060);
    const char *tp = (config.transport == WAVTransportTLS) ? "tls"
                   : (config.transport == WAVTransportTCP) ? "tcp" : "udp";

    // `;transport=udp` is deliberately omitted for UDP and written for the others.
    // Naming udp explicitly stops PJSIP escalating an oversized request to TCP
    // (RFC 3261 §18.1.1), which is a behaviour change and not a no-op — the opposite of
    // the port, which is always written because stating it costs nothing.
    NSString *transportParam = (config.transport == WAVTransportUDP)
        ? @"" : [NSString stringWithFormat:@";transport=%s", tp];

    NSString *idUri = config.displayName.length
        ? [NSString stringWithFormat:@"\"%@\" <%s:%@@%@>", config.displayName, scheme,
                                     config.username, config.domain]
        : [NSString stringWithFormat:@"%s:%@@%@", scheme, config.username, config.domain];

    NSString *registrar = config.registrar.length ? config.registrar
        : [NSString stringWithFormat:@"%@:%ld", config.domain, (long)port];
    NSString *regUri = [NSString stringWithFormat:@"%s:%@%@", scheme, registrar, transportParam];

    keep->push_back(std::string([idUri UTF8String]));
    acc->id = pj_str((char *)keep->back().c_str());
    keep->push_back(std::string([regUri UTF8String]));
    acc->reg_uri = pj_str((char *)keep->back().c_str());

    acc->cred_count = 1;
    acc->cred_info[0].realm     = pj_str((char *)"*");
    acc->cred_info[0].scheme    = pj_str((char *)"digest");
    keep->push_back(std::string([(config.authUsername.length ? config.authUsername
                                                             : config.username) UTF8String]));
    acc->cred_info[0].username  = pj_str((char *)keep->back().c_str());
    acc->cred_info[0].data_type = PJSIP_CRED_DATA_PLAIN_PASSWD;
    keep->push_back(std::string([config.password UTF8String]));
    acc->cred_info[0].data      = pj_str((char *)keep->back().c_str());

    if (config.outboundProxy.length) {
        // `;lr` — loose routing. Without it the proxy is a strict router and rewrites
        // the request URI, which breaks every in-dialog request after the first.
        keep->push_back(std::string([[NSString stringWithFormat:@"%s:%@%@;lr", scheme,
                                      config.outboundProxy, transportParam] UTF8String]));
        acc->proxy[0] = pj_str((char *)keep->back().c_str());
        acc->proxy_cnt = 1;
    }

    acc->reg_timeout = (unsigned)config.registrationExpirySeconds;

    // Refresh at half the expiry, FLOORED at 30 s and CAPPED at 120 s.
    //
    // Half the lifetime alone is not enough, and this is measured rather than
    // theoretical: on this deployment an account whose registrar granted 5180 s armed
    // its keepalive at 2590 s, and calls to it stopped arriving within ten minutes of
    // the last REGISTER — the handset rang again the moment anything forced a fresh
    // one. The NAT mapping dies long before the lease does, and PJSIP's transport
    // keepalive does not cover it.
    //
    // 120 s is RFC 5626 §4.4.1's recommendation for a connection-oriented flow and
    // sits under every NAT idle timeout seen on this network. The floor stops a
    // registrar that grants a very short expiry from turning this into a REGISTER
    // storm. Both constants track `RegistrationRecoveryCoordinator.KEEPALIVE_FLOOR`
    // and `KEEPALIVE_CEILING` on the Android side (main, 0d4c562e) — a cap that holds
    // on one platform and not the other is a bug report nobody can reproduce.
    const int kKeepaliveFloorSeconds = 30;
    const int kKeepaliveCeilingSeconds = 120;
    int refresh = (int)config.registrationExpirySeconds / 2;
    refresh = MAX(kKeepaliveFloorSeconds, MIN(kKeepaliveCeilingSeconds, refresh));
    acc->reg_delay_before_refresh = (unsigned)refresh;
    acc->reg_retry_interval = 0;   // retries are the shared RegistrationBackoff's job

    switch (config.srtpPolicy) {
        case WAVSrtpPolicyMandatory:
            acc->use_srtp = PJMEDIA_SRTP_MANDATORY;
            acc->srtp_secure_signaling = 0;   // SRTP without requiring sips: — see below
            break;
        case WAVSrtpPolicyOptional:
            acc->use_srtp = PJMEDIA_SRTP_OPTIONAL;
            acc->srtp_secure_signaling = 0;
            break;
        case WAVSrtpPolicyDisabled:
        default:
            acc->use_srtp = PJMEDIA_SRTP_DISABLED;
            acc->srtp_secure_signaling = 0;
            break;
    }
    // `srtp_secure_signaling = 0` deliberately: upstream defaults it to 1, which refuses
    // to offer SRTP unless the signalling is already TLS. The deployed office server
    // answers 488 to any `a=crypto` on RTP/AVP, so that default turns "SRTP optional"
    // into "SRTP never" on a UDP account and the setting looks broken.

    acc->allow_via_rewrite    = PJ_TRUE;
    acc->allow_contact_rewrite = PJ_TRUE;
    acc->contact_rewrite_method = PJSUA_CONTACT_REWRITE_UNREGISTER | PJSUA_CONTACT_REWRITE_NO_UNREG;

    if (config.stunServer.length) acc->sip_stun_use = PJSUA_STUN_USE_DEFAULT;
    acc->ice_cfg_use = PJSUA_ICE_CONFIG_USE_CUSTOM;
    acc->ice_cfg.enable_ice = config.iceEnabled ? PJ_TRUE : PJ_FALSE;

    // The same ceiling on the transport keepalive, for the same reason: a configured
    // value longer than the NAT's idle timeout is a value that does nothing.
    acc->ka_interval = (unsigned)MIN(kKeepaliveCeilingSeconds,
                                     MAX(0, (int)config.keepaliveIntervalSeconds));

    // Video is offered per call, not per account: a dialler that offered video on every
    // INVITE would light the camera for an audio call.
    acc->vid_in_auto_show  = PJ_TRUE;
    acc->vid_out_auto_transmit = PJ_TRUE;
    acc->vid_cap_dev = self.captureDevice;
}

- (BOOL)addAccount:(WAVAccountConfig *)config error:(NSError **)error {
    __block pj_status_t status = PJ_SUCCESS;
    __weak typeof(self) weakSelf = self;
    [self pj_sync:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;
        pjsua_acc_config acc;
        std::vector<std::string> keep;
        keep.reserve(8);
        [strongSelf fill:&acc fromConfig:config strings:&keep];

        pjsua_acc_id accId = PJSUA_INVALID_ID;
        status = pjsua_acc_add(&acc, PJ_FALSE, &accId);
        if (status != PJ_SUCCESS) return;

        [strongSelf.mapLock lock];
        strongSelf.accountIds[config.accountId] = @(accId);
        strongSelf.accountIdsReverse[@(accId)] = config.accountId;
        [strongSelf.mapLock unlock];
    }];
    if (status != PJ_SUCCESS) {
        if (error) *error = PjError(status, @"pjsua_acc_add");
        return NO;
    }
    return YES;
}

- (BOOL)updateAccount:(WAVAccountConfig *)config error:(NSError **)error {
    pjsua_acc_id accId = [self pjAccountIdFor:config.accountId];
    if (accId == PJSUA_INVALID_ID) return [self addAccount:config error:error];

    __block pj_status_t status = PJ_SUCCESS;
    __weak typeof(self) weakSelf = self;
    [self pj_sync:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;
        pjsua_acc_config acc;
        std::vector<std::string> keep;
        keep.reserve(8);
        [strongSelf fill:&acc fromConfig:config strings:&keep];
        status = pjsua_acc_modify(accId, &acc);
    }];
    if (status != PJ_SUCCESS) {
        if (error) *error = PjError(status, @"pjsua_acc_modify");
        return NO;
    }
    return YES;
}

- (void)removeAccount:(NSString *)accountId {
    pjsua_acc_id accId = [self pjAccountIdFor:accountId];
    if (accId == PJSUA_INVALID_ID) return;
    __weak typeof(self) weakSelf = self;
    [self pj_async:^{
        pjsua_acc_set_registration(accId, PJ_FALSE);
        pjsua_acc_del(accId);
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;
        [strongSelf.mapLock lock];
        [strongSelf.accountIds removeObjectForKey:accountId];
        [strongSelf.accountIdsReverse removeObjectForKey:@(accId)];
        [strongSelf.mapLock unlock];
    }];
}

- (void)setRegistration:(BOOL)wanted forAccount:(NSString *)accountId {
    pjsua_acc_id accId = [self pjAccountIdFor:accountId];
    if (accId == PJSUA_INVALID_ID) return;
    [self pj_async:^{ pjsua_acc_set_registration(accId, wanted ? PJ_TRUE : PJ_FALSE); }];
}

- (void)refreshAllRegistrations {
    [self pj_async:^{
        unsigned count = PJSUA_MAX_ACC;
        pjsua_acc_id ids[PJSUA_MAX_ACC];
        if (pjsua_enum_accs(ids, &count) != PJ_SUCCESS) return;
        for (unsigned i = 0; i < count; ++i) {
            pjsua_acc_info info;
            if (pjsua_acc_get_info(ids[i], &info) != PJ_SUCCESS) continue;
            if (!info.has_registration) continue;
            // PJ_TRUE on an account that is already registered sends a fresh REGISTER
            // rather than being a no-op, which is the point: after a network change the
            // binding the registrar holds names an address this device no longer has.
            pjsua_acc_set_registration(ids[i], PJ_TRUE);
        }
    }];
}

#pragma mark - Calls

/// Completes a dialled string into a SIP URI against the account.
///
/// The port is ALWAYS written, including when it is the transport's default. This is the
/// rule `SipAccount.dialledPort` states for Android and it exists because of a real
/// field defect: the registrar URI carried the account's port and the INVITE's did not,
/// so an account registered on 5070 sent every call to 5060. `sip:x@host` and
/// `sip:x@host:5060` are the same address, so writing it costs nothing on the wire and
/// buys a rule with no exception to remember.
- (NSString *)completeTarget:(NSString *)destination forAccount:(WAVAccountConfig *)unused
                      domain:(NSString *)domain port:(NSInteger)port scheme:(NSString *)scheme
                   transport:(WAVTransport)transport {
    (void)unused;
    NSString *trimmed = [destination stringByTrimmingCharactersInSet:
                            [NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if ([trimmed hasPrefix:@"sip:"] || [trimmed hasPrefix:@"sips:"]) return trimmed;

    NSString *transportParam = (transport == WAVTransportUDP) ? @""
        : [NSString stringWithFormat:@";transport=%@",
             transport == WAVTransportTLS ? @"tls" : @"tcp"];

    if ([trimmed containsString:@"@"]) {
        return [NSString stringWithFormat:@"%@:%@%@", scheme, trimmed, transportParam];
    }
    return [NSString stringWithFormat:@"%@:%@@%@:%ld%@", scheme, trimmed, domain,
                                      (long)port, transportParam];
}

- (NSInteger)placeCallTo:(NSString *)destination
             fromAccount:(NSString *)accountId
                 profile:(WAVMediaProfile)profile
                   error:(NSError **)error {
    pjsua_acc_id accId = [self pjAccountIdFor:accountId];
    if (accId == PJSUA_INVALID_ID) {
        if (error) *error = PjError(PJ_ENOTFOUND, @"no such account");
        return -1;
    }

    __block pjsua_call_id callId = kNoCall;
    __block pj_status_t status = PJ_SUCCESS;
    __weak typeof(self) weakSelf = self;

    [self pj_sync:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;

        // The account's own identity supplies the domain, port and scheme, read back from
        // pjsua rather than cached, so an account edited since it was added dials its new
        // registrar and not its old one.
        pjsua_acc_info ainfo;
        if (pjsua_acc_get_info(accId, &ainfo) != PJ_SUCCESS) { status = PJ_ENOTFOUND; return; }

        NSString *accUri = NsStr(&ainfo.acc_uri);
        NSString *scheme = [accUri hasPrefix:@"sips:"] ? @"sips" : @"sip";
        NSString *domain = @"";
        NSRange at = [accUri rangeOfString:@"@"];
        if (at.location != NSNotFound) {
            domain = [accUri substringFromIndex:at.location + 1];
            domain = [[domain componentsSeparatedByString:@">"] firstObject];
            domain = [[domain componentsSeparatedByString:@";"] firstObject];
        }
        NSInteger port = 0;
        NSRange colon = [domain rangeOfString:@":" options:NSBackwardsSearch];
        if (colon.location != NSNotFound) {
            port = [[domain substringFromIndex:colon.location + 1] integerValue];
            domain = [domain substringToIndex:colon.location];
        }
        if (port == 0) port = [scheme isEqualToString:@"sips"] ? 5061 : 5060;

        NSString *uri = [strongSelf completeTarget:destination forAccount:nil domain:domain
                                              port:port scheme:scheme
                                         transport:[scheme isEqualToString:@"sips"]
                                                   ? WAVTransportTLS : WAVTransportUDP];

        pjsua_call_setting opt;
        pjsua_call_setting_default(&opt);
        opt.aud_cnt = 1;
        opt.vid_cnt = (profile == WAVMediaProfileAudioVideo) ? 1 : 0;

        pj_str_t dst = PjStr(uri);
        status = pjsua_call_make_call(accId, &dst, &opt, NULL, NULL, &callId);
    }];

    if (status != PJ_SUCCESS) {
        if (error) *error = PjError(status, @"pjsua_call_make_call");
        return -1;
    }
    return callId;
}

- (void)answerCall:(NSInteger)callId profile:(WAVMediaProfile)profile {
    [self pj_async:^{
        pjsua_call_setting opt;
        pjsua_call_setting_default(&opt);
        opt.aud_cnt = 1;
        opt.vid_cnt = (profile == WAVMediaProfileAudioVideo) ? 1 : 0;
        pjsua_call_answer2((pjsua_call_id)callId, &opt, 200, NULL, NULL);
    }];
}

- (void)rejectCall:(NSInteger)callId statusCode:(NSInteger)statusCode {
    [self pj_async:^{
        pjsua_call_hangup((pjsua_call_id)callId, (unsigned)statusCode, NULL, NULL);
    }];
}

- (void)hangupCall:(NSInteger)callId {
    [self pj_async:^{ pjsua_call_hangup((pjsua_call_id)callId, 0, NULL, NULL); }];
}

- (void)hangupAllCalls {
    [self pj_async:^{ pjsua_call_hangup_all(); }];
}

- (void)setHold:(BOOL)held forCall:(NSInteger)callId {
    [self pj_async:^{
        if (held) {
            pjsua_call_set_hold((pjsua_call_id)callId, NULL);
        } else {
            // PJSUA_CALL_UNHOLD, not a bare reinvite: the flag is what puts sendrecv back
            // in the SDP. A plain re-INVITE re-offers the held direction and the far end
            // correctly keeps holding.
            pjsua_call_reinvite((pjsua_call_id)callId, PJSUA_CALL_UNHOLD, NULL);
        }
    }];
}

/// Mute by silencing the call's transmit level on the conference bridge.
///
/// NOT by stopping the capture device: this app mixes several calls locally, they all
/// share one capture slot, and closing it would mute every one of them. Breaking the
/// per-call transmit level is the same mechanism `CaptureRouting` settled on for Android
/// after the voice gate closed the slot its own tap was listening to.
- (void)applyMute:(BOOL)muted toCall:(pjsua_call_id)callId {
    pjsua_call_info ci;
    if (pjsua_call_get_info(callId, &ci) != PJ_SUCCESS) return;
    for (unsigned i = 0; i < ci.media_cnt; ++i) {
        if (ci.media[i].type != PJMEDIA_TYPE_AUDIO) continue;
        pjsua_conf_port_id slot = ci.media[i].stream.aud.conf_slot;
        if (slot == PJSUA_INVALID_ID) continue;
        if (muted) {
            pjsua_conf_disconnect(0, slot);
        } else {
            pjsua_conf_connect(0, slot);
        }
    }
}

- (void)setMuted:(BOOL)muted forCall:(NSInteger)callId {
    __weak typeof(self) weakSelf = self;
    [self pj_async:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;
        [strongSelf applyMute:muted toCall:(pjsua_call_id)callId];
        [strongSelf.mapLock lock];
        WAVCallInfo *info = strongSelf.calls[@(callId)];
        info.isMuted = muted;
        [strongSelf.mapLock unlock];
        if (info) {
            dispatch_async(dispatch_get_main_queue(), ^{
                [strongSelf.delegate pjsipCallDidChange:info];
            });
        }
    }];
}

- (void)sendDtmf:(NSString *)digits forCall:(NSInteger)callId {
    [self pj_async:^{
        pjsua_call_send_dtmf_param param;
        pjsua_call_send_dtmf_param_default(&param);
        // RFC 2833 first. SIP INFO is the fallback and not the default: INFO is
        // out-of-band, arrives out of order with the audio, and a gateway that counts
        // on in-band timing for an IVR menu misreads it.
        param.method = PJSUA_DTMF_METHOD_RFC2833;
        param.digits = PjStr(digits);
        pj_status_t status = pjsua_call_send_dtmf((pjsua_call_id)callId, &param);
        if (status != PJ_SUCCESS) {
            param.method = PJSUA_DTMF_METHOD_SIP_INFO;
            pjsua_call_send_dtmf((pjsua_call_id)callId, &param);
        }
    }];
}

- (void)transferCall:(NSInteger)callId toDestination:(NSString *)destination {
    [self pj_async:^{
        pj_str_t dst = PjStr(destination);
        pjsua_call_xfer((pjsua_call_id)callId, &dst, NULL);
    }];
}

- (void)attendedTransferCall:(NSInteger)callId toCall:(NSInteger)otherCallId {
    [self pj_async:^{
        pjsua_call_xfer_replaces((pjsua_call_id)callId, (pjsua_call_id)otherCallId, 0, NULL);
    }];
}

- (nullable WAVCallInfo *)infoForCall:(NSInteger)callId {
    [self.mapLock lock];
    WAVCallInfo *info = self.calls[@(callId)];
    [self.mapLock unlock];
    return info;
}

- (NSArray<WAVCallInfo *> *)activeCalls {
    [self.mapLock lock];
    NSArray<WAVCallInfo *> *all = self.calls.allValues;
    [self.mapLock unlock];
    return all;
}

#pragma mark - Local mixing

- (BOOL)mixCalls:(NSArray<NSNumber *> *)callIds {
    if (callIds.count < 2) return NO;
    __block BOOL ok = YES;
    [self pj_sync:^{
        std::vector<pjsua_conf_port_id> slots;
        for (NSNumber *boxed in callIds) {
            pjsua_call_info ci;
            if (pjsua_call_get_info((pjsua_call_id)boxed.integerValue, &ci) != PJ_SUCCESS) {
                ok = NO; continue;
            }
            for (unsigned i = 0; i < ci.media_cnt; ++i) {
                if (ci.media[i].type == PJMEDIA_TYPE_AUDIO &&
                    ci.media[i].stream.aud.conf_slot != PJSUA_INVALID_ID) {
                    slots.push_back(ci.media[i].stream.aud.conf_slot);
                    break;
                }
            }
        }
        // Every slot to every other slot, both directions. N*(N-1) connections, which is
        // what makes it a mix rather than a relay: nobody's audio passes through a third
        // party and no leg hears itself.
        for (size_t a = 0; a < slots.size(); ++a) {
            for (size_t b = 0; b < slots.size(); ++b) {
                if (a == b) continue;
                if (pjsua_conf_connect(slots[a], slots[b]) != PJ_SUCCESS) ok = NO;
            }
        }
    }];
    return ok;
}

- (void)unmixCall:(NSInteger)callId {
    [self pj_async:^{
        pjsua_call_info ci;
        if (pjsua_call_get_info((pjsua_call_id)callId, &ci) != PJ_SUCCESS) return;
        for (unsigned i = 0; i < ci.media_cnt; ++i) {
            if (ci.media[i].type != PJMEDIA_TYPE_AUDIO) continue;
            pjsua_conf_port_id slot = ci.media[i].stream.aud.conf_slot;
            if (slot == PJSUA_INVALID_ID) continue;
            // Everything except the sound device. The call stays up and audible to this
            // handset; it just stops being part of the mix.
            unsigned count = PJSUA_MAX_CONF_PORTS;
            pjsua_conf_port_id ids[PJSUA_MAX_CONF_PORTS];
            if (pjsua_enum_conf_ports(ids, &count) != PJ_SUCCESS) continue;
            for (unsigned k = 0; k < count; ++k) {
                if (ids[k] == slot || ids[k] == 0) continue;
                pjsua_conf_disconnect(slot, ids[k]);
                pjsua_conf_disconnect(ids[k], slot);
            }
        }
    }];
}

#pragma mark - Media

- (void)activateAudioSession {
    if (self.audioSessionActive) return;
    self.audioSessionActive = YES;
    [self pj_async:^{
        // Default devices. The ROUTE — earpiece, speaker, Bluetooth — is AVAudioSession's
        // and is set in Swift before this runs; pjsua only needs to know a device exists.
        pjsua_set_snd_dev(PJMEDIA_AUD_DEFAULT_CAPTURE_DEV, PJMEDIA_AUD_DEFAULT_PLAYBACK_DEV);
    }];
}

- (void)deactivateAudioSession {
    if (!self.audioSessionActive) return;
    self.audioSessionActive = NO;
    [self pj_async:^{ pjsua_set_no_snd_dev(); }];
}

- (void)applyVideoDefaults {
    // Pump thread. Applies the frame rate and bitrate to every registered video codec.
    //
    // Applied to the ENDPOINT codec parameters, not to a running stream: pjmedia has no
    // API to reconfigure a stream that is already running, and an autonomous re-INVITE
    // races the user's own and freezes the media. So this takes effect at the next
    // stream boundary, which is what the Android side settled on for the same reason.
    unsigned count = 32;
    pjsua_codec_info codecs[32];
    if (pjsua_vid_enum_codecs(codecs, &count) != PJ_SUCCESS) return;

    for (unsigned i = 0; i < count; ++i) {
        pjmedia_vid_codec_param param;
        if (pjsua_vid_codec_get_param(&codecs[i].codec_id, &param) != PJ_SUCCESS) continue;

        param.enc_fmt.det.vid.fps.num = (unsigned)self.preferredVideoFps;
        param.enc_fmt.det.vid.fps.denum = 1;
        param.enc_fmt.det.vid.avg_bps = (unsigned)(self.preferredVideoKbps * 1000);
        param.enc_fmt.det.vid.max_bps = (unsigned)(self.preferredVideoKbps * 1500);

        pjsua_vid_codec_set_param(&codecs[i].codec_id, &param);
    }
}

- (void)setVideoFrameRate:(NSInteger)fps {
    self.preferredVideoFps = MAX(5, MIN(30, fps));
    __weak typeof(self) weakSelf = self;
    [self pj_async:^{ [weakSelf applyVideoDefaults]; }];
}

- (void)setVideoBitrateKbps:(NSInteger)kbps {
    self.preferredVideoKbps = MAX(64, MIN(4096, kbps));
    __weak typeof(self) weakSelf = self;
    [self pj_async:^{ [weakSelf applyVideoDefaults]; }];
}

- (void)setVideoEnabled:(BOOL)enabled forCall:(NSInteger)callId {
    __weak typeof(self) weakSelf = self;
    [self pj_async:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;
        pjsua_call_vid_strm_op_param param;
        pjsua_call_vid_strm_op_param_default(&param);
        param.cap_dev = strongSelf.captureDevice;

        pjsua_call_info ci;
        if (pjsua_call_get_info((pjsua_call_id)callId, &ci) != PJ_SUCCESS) return;

        BOOL hasVideoStream = NO;
        for (unsigned i = 0; i < ci.media_cnt; ++i) {
            if (ci.media[i].type == PJMEDIA_TYPE_VIDEO) { hasVideoStream = YES; break; }
        }

        if (enabled) {
            // ADD creates the stream and re-INVITEs; START_TRANSMIT only resumes one that
            // is already negotiated. Using ADD on a call that already has a video stream
            // offers a second m=video line, which the deployed FreeSWITCH answers with
            // `m=video 0` and the tile goes black.
            pjsua_call_set_vid_strm((pjsua_call_id)callId,
                                    hasVideoStream ? PJSUA_CALL_VID_STRM_START_TRANSMIT
                                                   : PJSUA_CALL_VID_STRM_ADD,
                                    &param);
        } else {
            pjsua_call_set_vid_strm((pjsua_call_id)callId,
                                    PJSUA_CALL_VID_STRM_STOP_TRANSMIT, &param);
        }
    }];
}

- (void)switchCameraForCall:(NSInteger)callId {
    __weak typeof(self) weakSelf = self;
    [self pj_async:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;

        // Find the other camera by asking the device list, not by toggling an index.
        // PJMEDIA_VID_DEFAULT_CAPTURE_DEV is a sentinel rather than an index, so
        // "the other one" is only meaningful against the real enumeration.
        unsigned count = pjsua_vid_dev_count();
        pjmedia_vid_dev_index target = PJMEDIA_VID_INVALID_DEV;
        pjmedia_vid_dev_index first = PJMEDIA_VID_INVALID_DEV;
        for (unsigned i = 0; i < count; ++i) {
            pjmedia_vid_dev_info dinfo;
            if (pjsua_vid_dev_get_info((pjmedia_vid_dev_index)i, &dinfo) != PJ_SUCCESS) continue;
            if ((dinfo.dir & PJMEDIA_DIR_CAPTURE) == 0) continue;
            if (first == PJMEDIA_VID_INVALID_DEV) first = (pjmedia_vid_dev_index)i;
            if ((pjmedia_vid_dev_index)i != strongSelf.captureDevice) {
                target = (pjmedia_vid_dev_index)i;
                break;
            }
        }
        if (target == PJMEDIA_VID_INVALID_DEV) target = first;
        if (target == PJMEDIA_VID_INVALID_DEV) return;

        strongSelf.captureDevice = target;

        pjsua_call_vid_strm_op_param param;
        pjsua_call_vid_strm_op_param_default(&param);
        param.cap_dev = target;
        pjsua_call_set_vid_strm((pjsua_call_id)callId,
                                PJSUA_CALL_VID_STRM_CHANGE_CAP_DEV, &param);
    }];
}

- (void)respondToVideoRequest:(NSInteger)callId accept:(BOOL)accept {
    [self setVideoEnabled:accept forCall:callId];
}

- (void)startLocalPreview {
    __weak typeof(self) weakSelf = self;
    [self pj_async:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;
        pjsua_vid_preview_param param;
        pjsua_vid_preview_param_default(&param);
        param.show = PJ_TRUE;
        pjsua_vid_preview_start(strongSelf.captureDevice, &param);
    }];
}

- (void)stopLocalPreview {
    __weak typeof(self) weakSelf = self;
    [self pj_async:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;
        pjsua_vid_preview_stop(strongSelf.captureDevice);
    }];
}

- (nullable UIView *)localPreviewView {
    __block UIView *view = nil;
    __weak typeof(self) weakSelf = self;
    [self pj_sync:^{
        typeof(self) strongSelf = weakSelf; if (!strongSelf) return;
        pjsua_vid_win_id wid = pjsua_vid_preview_get_win(strongSelf.captureDevice);
        if (wid == PJSUA_INVALID_ID) return;
        pjsua_vid_win_info wi;
        if (pjsua_vid_win_get_info(wid, &wi) != PJ_SUCCESS) return;
        view = (__bridge UIView *)wi.hwnd.info.ios.window;
    }];
    return view;
}

- (nullable WAVMediaStats *)statsForCall:(NSInteger)callId {
    __block WAVMediaStats *stats = nil;
    [self pj_sync:^{
        pjsua_call_info ci;
        if (pjsua_call_get_info((pjsua_call_id)callId, &ci) != PJ_SUCCESS) return;

        WAVMediaStats *out = [[WAVMediaStats alloc] init];
        for (unsigned i = 0; i < ci.media_cnt; ++i) {
            // `pjsua_stream_stat` carries exactly two things — an RTCP statistic and a
            // jitter-buffer state. It has no video section: an earlier version of this
            // method read `stat.vid[0].rx.frame_cnt` and there is no such member.
            // Video's counters come from its own RTCP stream, which pjsua reports
            // through the same call keyed on the video media index, and its SIZE comes
            // from the render window.
            pjsua_stream_stat stat;
            if (pjsua_call_get_stream_stat((pjsua_call_id)callId, i, &stat) != PJ_SUCCESS) {
                continue;
            }

            if (ci.media[i].type == PJMEDIA_TYPE_AUDIO) {
                out.rxPackets        = (NSInteger)stat.rtcp.rx.pkt;
                out.txPackets        = (NSInteger)stat.rtcp.tx.pkt;
                out.rxLostPackets    = (NSInteger)stat.rtcp.rx.loss;
                out.txLostPackets    = (NSInteger)stat.rtcp.tx.loss;
                out.rxBytesPerSecond = (NSInteger)stat.rtcp.rx.bytes;
                out.txBytesPerSecond = (NSInteger)stat.rtcp.tx.bytes;
                // pjmedia reports jitter and RTT in MICROseconds; the UI shows ms, and
                // a factor of a thousand in a latency number is the kind of wrong that
                // gets believed.
                out.rxJitterMs  = stat.rtcp.rx.jitter.mean / 1000.0;
                out.roundTripMs = stat.rtcp.rtt.mean / 1000.0;
            } else if (ci.media[i].type == PJMEDIA_TYPE_VIDEO) {
                // The decoded frame SIZE, from the window rather than from a counter.
                //
                // It is the only place the real negotiated resolution appears: the
                // window is what the renderer sized itself to after FMT_CHANGED, and
                // the SDP's answer is a ceiling rather than what is arriving.
                pjsua_vid_win_id wid = ci.media[i].stream.vid.win_in;
                if (wid != PJSUA_INVALID_ID) {
                    pjsua_vid_win_info wi;
                    if (pjsua_vid_win_get_info(wid, &wi) == PJ_SUCCESS) {
                        out.videoRxWidth  = (NSInteger)wi.size.w;
                        out.videoRxHeight = (NSInteger)wi.size.h;
                    }
                }
                // Frame RATE is deliberately left at zero rather than guessed.
                //
                // pjsua exposes no per-stream frame counter, and deriving one from the
                // RTCP packet count is wrong by whatever the packets-per-frame ratio
                // happens to be — which for VP8 varies with the frame. Reporting 0 is
                // honest; reporting a number computed from the wrong quantity is the
                // sort of metric that gets optimised against for a week.
            }
        }
        stats = out;
    }];
    return stats;
}

#pragma mark - Codecs

- (NSArray<WAVCodecInfo *> *)codecsVideo:(BOOL)video {
    __block NSMutableArray<WAVCodecInfo *> *result = [NSMutableArray array];
    [self pj_sync:^{
        unsigned count = 64;
        pjsua_codec_info infos[64];
        pj_status_t status = video ? pjsua_vid_enum_codecs(infos, &count)
                                   : pjsua_enum_codecs(infos, &count);
        if (status != PJ_SUCCESS) return;
        for (unsigned i = 0; i < count; ++i) {
            WAVCodecInfo *c = [[WAVCodecInfo alloc] init];
            c.identifier = NsStr(&infos[i].codec_id);
            c.priority   = infos[i].priority;
            c.isVideo    = video;
            // "PCMU/8000/1" -> name PCMU, clock 8000, channels 1.
            NSArray<NSString *> *parts = [c.identifier componentsSeparatedByString:@"/"];
            c.name = parts.count > 0 ? parts[0] : c.identifier;
            c.clockRate    = parts.count > 1 ? parts[1].integerValue : 0;
            c.channelCount = parts.count > 2 ? parts[2].integerValue : 1;
            [result addObject:c];
        }
    }];
    return result;
}

- (NSArray<WAVCodecInfo *> *)enumerateAudioCodecs { return [self codecsVideo:NO]; }
- (NSArray<WAVCodecInfo *> *)enumerateVideoCodecs { return [self codecsVideo:YES]; }

- (void)setCodecPriority:(NSInteger)priority forCodec:(NSString *)identifier {
    [self pj_async:^{
        pj_str_t id_ = PjStr(identifier);
        pj_uint8_t p = (pj_uint8_t)MAX(0, MIN(255, priority));
        if (pjsua_codec_set_priority(&id_, p) != PJ_SUCCESS) {
            pjsua_vid_codec_set_priority(&id_, p);
        }
    }];
}

/// Applies an ordered preference list in one pass.
///
/// Anything NOT named is set to priority 0, which removes it from the SDP offer. That is
/// deliberate and is what makes the setting mean what it says: leaving unnamed codecs at
/// their default priority produces an offer containing codecs the user has deselected,
/// and the far end is entitled to pick one.
- (void)applyOrder:(NSArray<NSString *> *)names video:(BOOL)video {
    [self pj_async:^{
        unsigned count = 64;
        pjsua_codec_info infos[64];
        pj_status_t status = video ? pjsua_vid_enum_codecs(infos, &count)
                                   : pjsua_enum_codecs(infos, &count);
        if (status != PJ_SUCCESS) return;

        for (unsigned i = 0; i < count; ++i) {
            NSString *identifier = NsStr(&infos[i].codec_id);
            NSString *name = [[identifier componentsSeparatedByString:@"/"] firstObject] ?: identifier;

            NSUInteger rank = [names indexOfObjectPassingTest:
                ^BOOL(NSString *wanted, NSUInteger idx, BOOL *stop) {
                    (void)idx; (void)stop;
                    return [wanted caseInsensitiveCompare:name] == NSOrderedSame;
                }];

            // 255 for the first preference, descending by 8 so a list of up to 31 codecs
            // keeps a distinct priority each, and 0 for anything unnamed.
            pj_uint8_t priority = 0;
            if (rank != NSNotFound) {
                NSInteger p = 255 - (NSInteger)rank * 8;
                priority = (pj_uint8_t)MAX(1, p);
            }
            pj_str_t id_ = infos[i].codec_id;
            if (video) pjsua_vid_codec_set_priority(&id_, priority);
            else       pjsua_codec_set_priority(&id_, priority);
        }
    }];
}

- (void)applyAudioCodecOrder:(NSArray<NSString *> *)codecNames { [self applyOrder:codecNames video:NO]; }
- (void)applyVideoCodecOrder:(NSArray<NSString *> *)codecNames { [self applyOrder:codecNames video:YES]; }

#pragma mark - Push

- (void)setPushToken:(nullable NSString *)token
            provider:(NSString *)provider
            bundleId:(NSString *)bundleId {
    [self pj_async:^{
        unsigned count = PJSUA_MAX_ACC;
        pjsua_acc_id ids[PJSUA_MAX_ACC];
        if (pjsua_enum_accs(ids, &count) != PJ_SUCCESS) return;

        // The IMS reads these as Contact header PARAMETERS, not as a header of their own.
        // That is the detail the Android side had to discover: a `pn-*` set anywhere but
        // the Contact is accepted, echoed back and never used, so the device is never
        // woken and nobody sees an error.
        NSString *params = token.length
            ? [NSString stringWithFormat:@";pn-provider=%@;pn-prid=%@;pn-param=%@",
                                         provider, token, bundleId]
            : @"";

        for (unsigned i = 0; i < count; ++i) {
            pjsua_acc_config cfg;
            pj_pool_t *pool = pjsua_pool_create("wav-push", 512, 512);
            if (!pool) continue;
            if (pjsua_acc_get_config(ids[i], pool, &cfg) == PJ_SUCCESS) {
                pj_strdup2(pool, &cfg.contact_uri_params, [params UTF8String]);
                pjsua_acc_modify(ids[i], &cfg);
                // A fresh REGISTER, because the parameters live in the Contact and the
                // registrar only sees a Contact when one is sent.
                pjsua_acc_set_registration(ids[i], PJ_TRUE);
            }
            pj_pool_release(pool);
        }
    }];
}

- (void)dealloc {
    pthread_mutex_destroy(&_queueLock);
    pthread_cond_destroy(&_queueSignal);
}

@end
