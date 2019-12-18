#import "RNBraintreeDropIn.h"
#import <React/RCTUtils.h>

@implementation RNBraintreeDropIn

- (dispatch_queue_t)methodQueue
{
    return dispatch_get_main_queue();
}
RCT_EXPORT_MODULE(RNBraintreeDropIn)

RCT_EXPORT_METHOD(show:(NSDictionary*)options resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{

    if([options[@"darkTheme"] boolValue]){
        [BTUIKAppearance darkTheme];
    }

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
        NSNumber* threeDSecureAmount = threeDSecureOptions[@"amount"];
        if (!threeDSecureAmount) {
            reject(@"NO_3DS_AMOUNT", @"You must provide an amount for 3D Secure", nil);
            return;
        }

        request.threeDSecureVerification = YES;
        request.amount = [threeDSecureAmount stringValue];
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
        NSString* merchantName = options[@"merchantName"];
        NSDecimalNumber* orderTotal = options[@"orderTotal"];
        if(!merchantIdentifier || !countryCode || !currencyCode || !merchantName || !orderTotal){
            reject(@"MISSING_OPTIONS", @"Not all required Apple Pay options were provided", nil);
            return;
        }
        self.braintreeClient = [[BTAPIClient alloc] initWithAuthorization:clientToken];

        self.paymentRequest = [[PKPaymentRequest alloc] init];
        self.paymentRequest.merchantIdentifier = merchantIdentifier;
        self.paymentRequest.merchantCapabilities = PKMerchantCapability3DS;
        self.paymentRequest.countryCode = countryCode;
        self.paymentRequest.currencyCode = currencyCode;
        self.paymentRequest.supportedNetworks = @[PKPaymentNetworkAmex, PKPaymentNetworkVisa, PKPaymentNetworkMasterCard, PKPaymentNetworkDiscover, PKPaymentNetworkChinaUnionPay];
        self.paymentRequest.paymentSummaryItems =
            @[
                [PKPaymentSummaryItem summaryItemWithLabel:merchantName amount:[NSDecimalNumber decimalNumberWithString:orderTotal]]
            ];

        self.viewController = [[PKPaymentAuthorizationViewController alloc] initWithPaymentRequest: self.paymentRequest];
        self.viewController.delegate = self;
    }else{
        request.applePayDisabled = YES;
    }

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
                    } else{
                        [[self class] resolvePayment:result deviceData:self.deviceDataCollector resolver:resolve];
                    }
                } else if(result.paymentMethod == nil && (result.paymentOptionType == 16 || result.paymentOptionType == 18)){ //Apple Pay
                    // UIViewController *ctrl = [[[[UIApplication sharedApplication] delegate] window] rootViewController];
                    // [ctrl presentViewController:self.viewController animated:YES completion:nil];
                    UIViewController *rootViewController = RCTPresentedViewController();
                    [rootViewController presentViewController:self.viewController animated:YES completion:nil];
                } else{
                    [[self class] resolvePayment:result deviceData:self.deviceDataCollector resolver:resolve];
                }
            }
        }];
    [self.reactRoot presentViewController:dropIn animated:YES completion:nil];
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

+ (void)resolvePayment:(BTDropInResult* _Nullable)result deviceData:(NSString * _Nonnull)deviceDataCollector resolver:(RCTPromiseResolveBlock _Nonnull)resolve {
    //NSLog(@"result = %@", result);

    NSMutableDictionary* jsResult = [NSMutableDictionary new];

    //NSLog(@"paymentMethod = %@", result.paymentMethod);
    //NSLog(@"paymentIcon = %@", result.paymentIcon);

    [jsResult setObject:result.paymentMethod.nonce forKey:@"nonce"];
    [jsResult setObject:result.paymentMethod.type forKey:@"type"];
    [jsResult setObject:result.paymentDescription forKey:@"description"];
    [jsResult setObject:[NSNumber numberWithBool:result.paymentMethod.isDefault] forKey:@"isDefault"];
    [jsResult setObject:deviceDataCollector forKey:@"deviceData"];

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