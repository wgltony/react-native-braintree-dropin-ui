#import "RNBraintreeDropIn.h"
#import "BraintreePaymentFlow.h"

@interface RNBraintreeDropIn () <PKPaymentAuthorizationViewControllerDelegate> {
    __block NSString *currencyString;
    __block NSDecimalNumber *orderAmount;
}

@end

@implementation RNBraintreeDropIn

- (dispatch_queue_t)methodQueue
{
    return dispatch_get_main_queue();
}

RCT_EXPORT_MODULE(RNBraintreeDropIn)

RCT_EXPORT_METHOD(show:(NSDictionary*)options resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    self.recentNonce = [[NSMutableArray alloc] init];
    self.resolve = resolve;
    self.reject = reject;
    self.applePayAuthorized = NO;
    
    NSString* clientToken = options[@"clientToken"];
    if (!clientToken) {
        reject(@"NO_CLIENT_TOKEN", @"You must provide a client token", nil);
        return;
    }
    
    BTDropInRequest *request = [[BTDropInRequest alloc] init];
    
    NSDictionary* threeDSecureOptions = options[@"threeDSecure"];
    if (threeDSecureOptions) {
        BTThreeDSecureRequest *threeDSecureRequest = [[BTThreeDSecureRequest alloc] init];
        NSNumber* threeDSecureAmount = threeDSecureOptions[@"amount"];
        if (!threeDSecureAmount) {
            reject(@"NO_3DS_AMOUNT", @"You must provide an amount for 3D Secure", nil);
            return;
        }
        threeDSecureRequest.amount = [NSDecimalNumber decimalNumberWithDecimal:[threeDSecureAmount decimalValue]];
        threeDSecureRequest.versionRequested = BTThreeDSecureVersion2;
        threeDSecureRequest.challengeRequested = YES;
        
        request.threeDSecureVerification = YES;
        request.threeDSecureRequest = threeDSecureRequest;
    }
    
    BTAPIClient *apiClient = [[BTAPIClient alloc] initWithAuthorization:clientToken];
    self.dataCollector = [[BTDataCollector alloc] initWithAPIClient:apiClient];
    [self.dataCollector collectCardFraudData:^(NSString * _Nonnull deviceDataCollector) {
        // Save deviceData
        self.deviceDataCollector = deviceDataCollector;
    }];
    
    if([options[@"vaultManager"] boolValue]){
        request.vaultManager = YES;
    }
    
    if([options[@"applePay"] boolValue]){
        NSString* merchantIdentifier = options[@"merchantIdentifier"];
        NSString* countryCode = options[@"countryCode"];
        NSString* currencyCode = options[@"currencyCode"];
        currencyString = currencyCode;
        NSString* merchantName = options[@"merchantName"];
        NSString* orderTotal = options[@"orderTotal"];
        orderAmount = [NSDecimalNumber decimalNumberWithString: orderTotal];
        if(!merchantIdentifier || !countryCode || !currencyCode || !merchantName || !orderTotal){
            reject(@"MISSING_OPTIONS", @"Not all required Apple Pay options were provided", nil);
            return;
        }
        self.braintreeClient = [[BTAPIClient alloc] initWithAuthorization:clientToken];
        self.paymentRequest = [self paymentRequestWithMerchantId:options];
        
        self.viewController = [[PKPaymentAuthorizationViewController alloc] initWithPaymentRequest: self.paymentRequest];
        self.viewController.delegate = self;
    }else{
        request.applePayDisabled = YES;
    }
    
    [apiClient fetchPaymentMethodNonces:^(NSArray<BTPaymentMethodNonce *> * _Nullable paymentMethodNonces, NSError * _Nullable error) {
        if(paymentMethodNonces != nil){
            for(BTPaymentMethodNonce *paymentMethodNonce in paymentMethodNonces){
                [self.recentNonce addObject:paymentMethodNonce.nonce];
            }
        }
    }];
    BTDropInController *dropIn = [[BTDropInController alloc] initWithAuthorization:clientToken request:request handler:^(BTDropInController * _Nonnull controller, BTDropInResult * _Nullable result, NSError * _Nullable error) {
        [self.reactRoot dismissViewControllerAnimated:YES completion:nil];
        
        //result.paymentOptionType == .ApplePay
        //NSLog(@"paymentOptionType = %ld", result.paymentOptionType);
        
        if (error != nil) {
            reject(error.localizedDescription, error.localizedDescription, error);
        } else if (result.cancelled) {
            reject(@"USER_CANCELLATION", @"The user cancelled", nil);
        } else {
            if (threeDSecureOptions && [result.paymentMethod isKindOfClass:[BTCardNonce class]]) {
                BTCardNonce *cardNonce = (BTCardNonce *)result.paymentMethod;
                if (!cardNonce.threeDSecureInfo.liabilityShiftPossible && cardNonce.threeDSecureInfo.wasVerified) {
                    reject(@"3DSECURE_NOT_ABLE_TO_SHIFT_LIABILITY", @"3D Secure liability cannot be shifted", nil);
                } else if (!cardNonce.threeDSecureInfo.liabilityShifted && cardNonce.threeDSecureInfo.wasVerified) {
                    reject(@"3DSECURE_LIABILITY_NOT_SHIFTED", @"3D Secure liability was not shifted", nil);
                } else if(result.paymentMethod == nil && result.paymentOptionType == 18){ //Apple Pay
                    UIViewController *ctrl = [[[[UIApplication sharedApplication] delegate] window] rootViewController];
                    [ctrl presentViewController:self.viewController animated:YES completion:nil];
                } else{
                    [[self class] resolvePayment:result deviceData:self.deviceDataCollector resolver:resolve rececntNonce:self.recentNonce];
                }
            } else if(result.paymentMethod == nil && result.paymentOptionType == 18){ //Apple Pay
                [self payWithApplePayFromBraintree:result];
            } else{
                [[self class] resolvePayment:result deviceData:self.deviceDataCollector resolver:resolve rececntNonce:self.recentNonce];
            }
        }
    }];
    [self.reactRoot presentViewController:dropIn animated:YES completion:nil];
}

- (void) payWithApplePayFromBraintree:(BTDropInResult *) result {
    PKPaymentRequest *paymentRequest = self.paymentRequest;
    PKPaymentAuthorizationViewController *vc = [[PKPaymentAuthorizationViewController alloc] initWithPaymentRequest:paymentRequest];
    vc.delegate = self;
    UIViewController *ctrl = [[[[UIApplication sharedApplication] delegate] window] rootViewController];
    [ctrl presentViewController:vc animated:YES completion:nil];
}

- (PKPaymentRequest *)paymentRequestWithMerchantId:(NSDictionary *) options {
    PKPaymentRequest *paymentRequest = [[PKPaymentRequest alloc] init];
    paymentRequest.merchantIdentifier = options[@"merchantIdentifier"];
    paymentRequest.supportedNetworks = @[PKPaymentNetworkAmex, PKPaymentNetworkVisa, PKPaymentNetworkMasterCard, PKPaymentNetworkDiscover, PKPaymentNetworkChinaUnionPay];
    paymentRequest.merchantCapabilities = PKMerchantCapability3DS;
    
    NSLocale *currentLocale = [NSLocale currentLocale]; // get the current locale.
    NSString *countryCode = [currentLocale objectForKey:NSLocaleCountryCode];
    paymentRequest.currencyCode = options[@"currencyCode"];
    paymentRequest.countryCode = [countryCode uppercaseString];
    
    paymentRequest.paymentSummaryItems =
    @[
      [PKPaymentSummaryItem summaryItemWithLabel:options[@"merchantName"] amount: orderAmount]
      ];
    
    return paymentRequest;
}

- (void)paymentAuthorizationViewController:(PKPaymentAuthorizationViewController *)controller
                       didAuthorizePayment:(PKPayment *)payment
                                completion:(void (^)(PKPaymentAuthorizationStatus))completion
{
    
    // Example: Tokenize the Apple Pay payment
    BTApplePayClient *applePayClient = [[BTApplePayClient alloc]
                                        initWithAPIClient:self.braintreeClient];
    [applePayClient tokenizeApplePayPayment:payment
                                 completion:^(BTApplePayCardNonce *tokenizedApplePayPayment,
                                              NSError *error) {
                                     if (tokenizedApplePayPayment) {
                                         // On success, send nonce to your server for processing.
                                         // If applicable, address information is accessible in `payment`.
                                         // NSLog(@"description = %@", tokenizedApplePayPayment.localizedDescription);
                                         
                                         completion(PKPaymentAuthorizationStatusSuccess);
                                         self.applePayAuthorized = YES;
                                         
                                         
                                         NSMutableDictionary* result = [NSMutableDictionary new];
                                         [result setObject:tokenizedApplePayPayment.nonce forKey:@"nonce"];
                                         [result setObject:@"Apple Pay" forKey:@"type"];
                                         [result setObject:[NSString stringWithFormat: @"%@ %@", @"", tokenizedApplePayPayment.type] forKey:@"description"];
                                         [result setObject:[NSNumber numberWithBool:false] forKey:@"isDefault"];
                                         [result setObject:self.deviceDataCollector forKey:@"deviceData"];
                                         [result setObject:[NSNumber numberWithBool:true] forKey:@"isRecent"];
                                         self.resolve(result);
                                         
                                     } else {
                                         // Tokenization failed. Check `error` for the cause of the failure.
                                         
                                         // Indicate failure via the completion callback:
                                         completion(PKPaymentAuthorizationStatusFailure);
                                     }
                                 }];
}

// Be sure to implement -paymentAuthorizationViewControllerDidFinish:
- (void)paymentAuthorizationViewControllerDidFinish:(PKPaymentAuthorizationViewController *)controller{
    [self.reactRoot dismissViewControllerAnimated:YES completion:nil];
    if(self.applePayAuthorized == NO){
        self.reject(@"USER_CANCELLATION", @"The user cancelled", nil);
    }
}

+ (void)resolvePayment:(BTDropInResult* _Nullable)result deviceData:(NSString * _Nonnull)deviceDataCollector resolver:(RCTPromiseResolveBlock _Nonnull)resolve rececntNonce:(NSArray * _Nonnull)rececntNonce{
    //    NSLog(@"result = %@", result);
    
    NSMutableDictionary* jsResult = [NSMutableDictionary new];
    
    //NSLog(@"paymentMethod = %@", result.paymentMethod);
    //NSLog(@"paymentIcon = %@", result.paymentIcon);
    NSString *nonce = result.paymentMethod.nonce;
    [jsResult setObject:nonce forKey:@"nonce"];
    [jsResult setObject:result.paymentMethod.type forKey:@"type"];
    [jsResult setObject:result.paymentDescription forKey:@"description"];
    [jsResult setObject:[NSNumber numberWithBool:result.paymentMethod.isDefault] forKey:@"isDefault"];
    [jsResult setObject:deviceDataCollector forKey:@"deviceData"];
    [jsResult setObject:[NSNumber numberWithBool:[rececntNonce containsObject:nonce]] forKey:@"isRecent"];
    
    resolve(jsResult);
}

- (UIViewController*)reactRoot {
    UIViewController *root  = [UIApplication sharedApplication].keyWindow.rootViewController;
    UIViewController *maybeModal = root.presentedViewController;
    
    UIViewController *modalRoot = root;
    
    if (maybeModal != nil) {
        modalRoot = maybeModal;
    }
    
    return modalRoot;
}

@end
