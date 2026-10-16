//
//  PjsipTypes.h
//  The vocabulary the Swift layer and the PJSIP bridge share.
//
//  These enums are NOT pjsua's. pjsua's own enums leak its internals — PJSIP_INV_STATE_*
//  has six values whose names describe an INVITE transaction rather than a call, and
//  pjsua_call_media_status mixes "no media" with "media is on hold". The Swift layer
//  models a call the way `com.whatsappv2.domain.call.CallState` does, because that
//  model is shared with Android and has tests, so the bridge translates once, here,
//  instead of leaving every call site to interpret a pjsua constant.
//
//  NS_CLOSED_ENUM, not NS_ENUM: these cross into Swift, and a closed enum is one Swift
//  can switch over exhaustively without a `@unknown default`. They are closed because
//  the bridge owns every value — nothing on the wire can introduce a new one.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Signalling transport. Mirrors `com.whatsappv2.domain.model.Transport`.
typedef NS_CLOSED_ENUM(NSInteger, WAVTransport) {
    WAVTransportUDP = 0,
    WAVTransportTCP = 1,
    WAVTransportTLS = 2,
};

/// Media encryption policy. Mirrors `com.whatsappv2.domain.model.SrtpPolicy`.
///
/// `Mandatory` fails the call rather than downgrading — which is the whole point of the
/// setting, and the behaviour the office server punishes: it answers 488 to any
/// `a=crypto` line on an RTP/AVP offer, so an account set to Mandatory against that
/// server does not place calls, visibly, instead of placing them unencrypted, silently.
typedef NS_CLOSED_ENUM(NSInteger, WAVSrtpPolicy) {
    WAVSrtpPolicyDisabled  = 0,
    WAVSrtpPolicyOptional  = 1,
    WAVSrtpPolicyMandatory = 2,
};

/// Where a call is in its life. Mirrors `com.whatsappv2.domain.call.CallState`.
typedef NS_CLOSED_ENUM(NSInteger, WAVCallState) {
    WAVCallStateIdle        = 0,
    WAVCallStateCalling     = 1,   ///< INVITE sent, no provisional response yet
    WAVCallStateRinging     = 2,   ///< 180
    WAVCallStateEarlyMedia  = 3,   ///< 183 with SDP — audio must be played
    WAVCallStateIncoming    = 4,
    WAVCallStateConnecting  = 5,   ///< 200 received, ACK not yet exchanged
    WAVCallStateConnected   = 6,
    WAVCallStateTerminated  = 7,
};

/// Which party holds the call. Mirrors `com.whatsappv2.domain.call.HoldParty`, including
/// the reason it is not a boolean: both sides can hold at once, and a local resume must
/// not claim the call is connected while the far end is still holding.
typedef NS_CLOSED_ENUM(NSInteger, WAVHoldParty) {
    WAVHoldPartyNone   = 0,
    WAVHoldPartyLocal  = 1,
    WAVHoldPartyRemote = 2,
    WAVHoldPartyBoth   = 3,
};

/// Registration status of one account. Mirrors
/// `com.whatsappv2.domain.model.RegistrationState`.
typedef NS_CLOSED_ENUM(NSInteger, WAVRegistrationState) {
    WAVRegistrationStateUnregistered = 0,
    WAVRegistrationStateRegistering  = 1,
    WAVRegistrationStateRegistered   = 2,
    WAVRegistrationStateFailed       = 3,
};

/// Why registration failed, at the granularity the user interface needs. Mirrors
/// `com.whatsappv2.domain.model.RegistrationFailure` value for value, so the Swift layer
/// can hand it straight to the shared `RegistrationBackoff` policy.
typedef NS_CLOSED_ENUM(NSInteger, WAVRegistrationFailure) {
    WAVRegistrationFailureNone                 = 0,
    WAVRegistrationFailureAuthenticationFailed = 1,  ///< 401/407 — the user must fix it
    WAVRegistrationFailureAccountRejected      = 2,  ///< 403/404
    WAVRegistrationFailureNetworkUnavailable   = 3,
    WAVRegistrationFailureTimeout              = 4,  ///< 408
    WAVRegistrationFailureServerUnavailable    = 5,  ///< 503
    WAVRegistrationFailureTransportFailure     = 6,  ///< TLS handshake or socket
    WAVRegistrationFailureInvalidConfiguration = 7,
};

/// Media direction for one stream, as negotiated.
typedef NS_CLOSED_ENUM(NSInteger, WAVMediaDirection) {
    WAVMediaDirectionNone     = 0,
    WAVMediaDirectionSendOnly = 1,
    WAVMediaDirectionRecvOnly = 2,
    WAVMediaDirectionSendRecv = 3,
};

/// What a call asked for, and what it got.
typedef NS_CLOSED_ENUM(NSInteger, WAVMediaProfile) {
    WAVMediaProfileAudioOnly = 0,
    WAVMediaProfileAudioVideo = 1,
};

/// Why a call ended. Mirrors `com.whatsappv2.domain.model.HangupReason`.
typedef NS_CLOSED_ENUM(NSInteger, WAVHangupReason) {
    WAVHangupReasonLocalHangup   = 0,
    WAVHangupReasonRemoteHangup  = 1,
    WAVHangupReasonRejected      = 2,
    WAVHangupReasonBusy          = 3,
    WAVHangupReasonNotFound      = 4,
    WAVHangupReasonNoAnswer      = 5,
    WAVHangupReasonServerError   = 6,
    WAVHangupReasonNetworkError  = 7,
    WAVHangupReasonCancelled     = 8,
};

NS_ASSUME_NONNULL_END
