#import "RNBraintreeDropIn.h"

@implementation RNBraintreeDropIn

- (dispatch_queue_t)methodQueue
{
    return dispatch_get_main_queue();
}
RCT_EXPORT_MODULE()

RCT_REMAP_METHOD(show,
                 showWithOptions:(NSDictionary*)options resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    self.resolve = resolve;

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

    

    if([options[@"applePay"] boolValue]){
        self.braintreeClient = [[BTAPIClient alloc] initWithAuthorization:clientToken];

        self.paymentRequest = [[PKPaymentRequest alloc] init];
        self.paymentRequest.merchantIdentifier = options[@"merchantIdentifier"];
        self.paymentRequest.merchantCapabilities = PKMerchantCapability3DS;
        self.paymentRequest.countryCode = options[@"countryCode"];
        self.paymentRequest.currencyCode = options[@"currencyCode"];
        self.paymentRequest.supportedNetworks = @[PKPaymentNetworkAmex, PKPaymentNetworkVisa, PKPaymentNetworkMasterCard, PKPaymentNetworkDiscover, PKPaymentNetworkChinaUnionPay];
        self.paymentRequest.paymentSummaryItems =
            @[
                [PKPaymentSummaryItem summaryItemWithLabel:options[@"merchantName"] amount:[NSDecimalNumber decimalNumberWithString:options[@"orderTotal"]]]
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
                    } else if(result.paymentMethod == nil && result.paymentOptionType == 16){ //Apple Pay
                        UIViewController *ctrl = [[[[UIApplication sharedApplication] delegate] window] rootViewController];
                        [ctrl presentViewController:self.viewController animated:YES completion:nil];
                    } else{
                        [[self class] resolvePayment:result deviceData:self.deviceDataCollector resolver:resolve];
                    }
                } else if(result.paymentMethod == nil && result.paymentOptionType == 16){ //Apple Pay
                    UIViewController *ctrl = [[[[UIApplication sharedApplication] delegate] window] rootViewController];
                    [ctrl presentViewController:self.viewController animated:YES completion:nil];
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
