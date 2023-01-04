@import UIKit;
@import PassKit;

#if __has_include("RCTBridgeModule.h")
#import "RCTBridgeModule.h"
#else
#import <React/RCTBridgeModule.h>
#endif

#import "BraintreeCore.h"
#import "BraintreeDropIn.h"
#import "BTCardNonce.h"
#import "BTDataCollector.h"

#import "BraintreeApplePay.h"

@interface RNBraintreeDropIn : NSObject <RCTBridgeModule, PKPaymentAuthorizationViewControllerDelegate>

@property (nonatomic, strong) UIViewController *_Nonnull reactRoot;

// Retain your `BTDataCollector` instance for your entire application lifecycle.
@property (nonatomic, strong) BTDataCollector *_Nonnull dataCollector;

@property (nonatomic, strong) BTAPIClient *_Nonnull braintreeClient;

@property (nonatomic, strong) PKPaymentRequest *_Nonnull paymentRequest;

@property (nonatomic, strong) PKPaymentAuthorizationViewController *_Nonnull viewController;

@property (nonatomic, strong) NSString * _Nonnull deviceDataCollector;

@property (nonatomic) RCTPromiseResolveBlock _Nonnull resolve;

@property (nonatomic) RCTPromiseRejectBlock _Nonnull reject;

@property (nonatomic, assign) BOOL applePayAuthorized;

+ (void)resolvePayment:(BTDropInResult* _Nullable)result deviceData:(NSString * _Nonnull)deviceDataCollector resolver:(RCTPromiseResolveBlock _Nonnull)resolve;

@end
