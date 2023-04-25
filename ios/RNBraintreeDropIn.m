#import "RNBraintreeDropIn.h"
#import <React/RCTUtils.h>
#import "BTThreeDSecureRequest.h"

@implementation RNBraintreeDropIn

- (dispatch_queue_t)methodQueue
{
    return dispatch_get_main_queue();
}
RCT_EXPORT_MODULE(RNBraintreeDropIn)

RCT_EXPORT_METHOD(show:(NSDictionary*)options resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    BTDropInColorScheme colorScheme;
  
    if([options[@"darkTheme"] boolValue]){
        if (@available(iOS 13.0, *)) {
            colorScheme = BTDropInColorSchemeDynamic;
        } else {
            colorScheme = BTDropInColorSchemeDark;
        }
    } else {
        colorScheme = BTDropInColorSchemeLight;
    }

    BTDropInUICustomization *uiCustomization = [[BTDropInUICustomization alloc] initWithColorScheme:colorScheme];

    if(options[@"fontFamily"]){
        uiCustomization.fontFamily = options[@"fontFamily"];
    }
    if(options[@"boldFontFamily"]){
        uiCustomization.boldFontFamily = options[@"boldFontFamily"];
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
    request.uiCustomization = uiCustomization;

    NSDictionary* threeDSecureOptions = options[@"threeDSecure"];
    if (threeDSecureOptions) {
        NSNumber* threeDSecureAmount = threeDSecureOptions[@"amount"];
        if (!threeDSecureAmount) {
            reject(@"NO_3DS_AMOUNT", @"You must provide an amount for 3D Secure", nil);
            return;
        }

        BTThreeDSecureRequest *threeDSecureRequest = [[BTThreeDSecureRequest alloc] init];
        threeDSecureRequest.amount = [NSDecimalNumber decimalNumberWithString:threeDSecureAmount.stringValue];
        request.threeDSecureRequest = threeDSecureRequest;

    }

    BTAPIClient *apiClient = [[BTAPIClient alloc] initWithAuthorization:clientToken];
    self.dataCollector = [[BTDataCollector alloc] initWithAPIClient:apiClient];
    [self.dataCollector collectDeviceData:^(NSString * _Nonnull deviceDataCollector) {
        // Save deviceData
        self.deviceDataCollector = deviceDataCollector;
    }];

    if([options[@"vaultManager"] boolValue]){
        request.vaultManager = YES;
    }

    if([options[@"cardDisabled"] boolValue]){
        request.cardDisabled = YES;
    }

    if([options[@"applePay"] boolValue]){
        NSString* merchantIdentifier = options[@"merchantIdentifier"];
        NSString* countryCode = options[@"countryCode"];
        NSString* currencyCode = options[@"currencyCode"];
        NSString* merchantName = options[@"merchantName"];
        NSDecimalNumber* orderTotal = [NSDecimalNumber decimalNumberWithDecimal:[options[@"orderTotal"] decimalValue]];
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
                [PKPaymentSummaryItem summaryItemWithLabel:merchantName amount:orderTotal]
            ];

        self.viewController = [[PKPaymentAuthorizationViewController alloc] initWithPaymentRequest: self.paymentRequest];
        self.viewController.delegate = self;
    }else{
        request.applePayDisabled = YES;
    }
    
    if(![options[@"payPal"] boolValue]){ //disable paypal
        request.paypalDisabled = YES;
    }

    BTDropInController *dropIn = [[BTDropInController alloc] initWithAuthorization:clientToken request:request handler:^(BTDropInController * _Nonnull controller, BTDropInResult * _Nullable result, NSError * _Nullable error) {
            [self.reactRoot dismissViewControllerAnimated:YES completion:nil];

            //result.paymentOptionType == .ApplePay
            //NSLog(@"paymentOptionType = %ld", result.paymentOptionType);

            if (error != nil) {
                reject(error.localizedDescription, error.localizedDescription, error);
            } else if (result.canceled) {
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
                } else if(result.paymentMethod == nil && (result.paymentMethodType == 16 || result.paymentMethodType == 17 || result.paymentMethodType == 18)){ //Apple Pay
                    // UIViewController *ctrl = [[[[UIApplication sharedApplication] delegate] window] rootViewController];
                    // [ctrl presentViewController:self.viewController animated:YES completion:nil];
                    UIViewController *rootViewController = RCTPresentedViewController();
                    [rootViewController presentViewController:self.viewController animated:YES completion:nil];
                } else{
                    [[self class] resolvePayment:result deviceData:self.deviceDataCollector resolver:resolve];
                }
            }
        }];

    if (dropIn != nil) {
        [self.reactRoot presentViewController:dropIn animated:YES completion:nil];
    } else {
        reject(@"INVALID_CLIENT_TOKEN", @"The client token seems invalid", nil);
    }
}

RCT_EXPORT_METHOD(fetchMostRecentPaymentMethod:(NSString*)clientToken
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  [BTDropInResult mostRecentPaymentMethodForClientToken:clientToken completion:^(BTDropInResult * _Nullable result, NSError * _Nullable error) {
    if (error != nil) {
        reject(error.localizedDescription, error.localizedDescription, error);
    } else if (result.canceled) {
        reject(@"USER_CANCELLATION", @"The user cancelled", nil);
    } else {
      [[self class] resolvePayment:result deviceData:result.deviceData resolver:resolve];
    }
  }];
}

RCT_EXPORT_METHOD(tokenizeCard:(NSString*)clientToken
                          info:(NSDictionary*)cardInfo
                      resolver:(RCTPromiseResolveBlock)resolve
                      rejecter:(RCTPromiseRejectBlock)reject)
{
    NSString *number = cardInfo[@"number"];
    NSString *expirationMonth = cardInfo[@"expirationMonth"];
    NSString *expirationYear = cardInfo[@"expirationYear"];
    NSString *cvv = cardInfo[@"cvv"];
    NSString *postalCode = cardInfo[@"postalCode"];

    if (!number || !expirationMonth || !expirationYear || !cvv || !postalCode) {
        reject(@"INVALID_CARD_INFO", @"Invalid card info", nil);
        return;
    }
    
    BTAPIClient *braintreeClient = [[BTAPIClient alloc] initWithAuthorization:clientToken];
    BTCardClient *cardClient = [[BTCardClient alloc] initWithAPIClient:braintreeClient];
    BTCard *card = [[BTCard alloc] init];
    card.number = number;
    card.expirationMonth = expirationMonth;
    card.expirationYear = expirationYear;
    card.cvv = cvv;
    card.postalCode = postalCode;

    [cardClient tokenizeCard:card
                  completion:^(BTCardNonce *tokenizedCard, NSError *error) {
        if (error == nil) {
            resolve(tokenizedCard.nonce);
        } else {
            reject(@"TOKENIZE_ERROR", @"Error tokenizing card.", error);
        }
    }];
}

- (void)paymentAuthorizationViewController:(PKPaymentAuthorizationViewController *)controller
                       didAuthorizePayment:(PKPayment *)payment
                                handler:(nonnull void (^)(PKPaymentAuthorizationResult * _Nonnull))completion
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

            completion([[PKPaymentAuthorizationResult alloc] initWithStatus:PKPaymentAuthorizationStatusSuccess errors:nil]);
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
            completion([[PKPaymentAuthorizationResult alloc] initWithStatus:PKPaymentAuthorizationStatusFailure errors:nil]);
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

    if (!result) {
        resolve(nil);
        return;
    }

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
