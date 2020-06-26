//
//  RNScanCard.h
//  RNBraintreeDropIn
//
//  Created by  Cacley Technologies on 6/26/20.
//  Copyright © 2020 Facebook. All rights reserved.
//
@import UIKit;
@import PassKit;

#if __has_include("RCTBridgeModule.h")
#import "RCTBridgeModule.h"
#else
#import <React/RCTBridgeModule.h>
#endif

@interface RNScanCard : NSObject <RCTBridgeModule>
@end
