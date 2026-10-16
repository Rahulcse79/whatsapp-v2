//
//  WhatsAppV2-Bridging-Header.h
//
//  The Objective-C surface Swift may see.
//
//  Deliberately TWO headers and not pjsua's. `PjsipBridge.h` is the whole contract;
//  `<pjsua-lib/pjsua.h>` is not in here and must not be, because importing it would put
//  every pjsua symbol into Swift's namespace and make it possible to call the stack
//  from the wrong thread by accident — which is the single thing the bridge exists to
//  prevent. If something in Swift needs a pjsua capability, it gets a method on
//  PjsipBridge rather than an import here.
//

#import "PjsipTypes.h"
#import "PjsipBridge.h"
