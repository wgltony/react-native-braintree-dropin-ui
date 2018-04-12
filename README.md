# react-native-braintree-payments-drop-in

> React Native integration of Braintree Drop-in

## Getting started

```bash
npm install react-native-braintree-dropin-ui --save
```

### Mostly automatic installation

```bash
react-native link react-native-braintree-dropin-ui
```

#### iOS specific

You must have a iOS deployment target \>= 9.0.

If you don't have a Podfile or are unsure on how to proceed, see the [CocoaPods][1] usage guide.

In your `Podfile`, add:

```
pod 'Braintree'
pod 'BraintreeDropIn'
pod 'Braintree/Apple-Pay'
pod 'Braintree/PayPal'
pod 'Braintree/Venmo'
pod 'Braintree/DataCollector'

  # uncomment the next line to support credit card scanning
  pod 'CardIO'

```

Then:

```bash
cd ios
pod repo update # optional and can be very long
pod install
```

#### Android specific

Add in your `app/build.gradle`:

```
dependencies {
...
    implementation "io.card:android-sdk:5.+"
    implementation 'com.braintreepayments.api:data-collector:2.+'
```

### Configuration

For more configuration options, see Braintree's documentation ([iOS][2] | [Android][3]).

#### 3D Secure

If you plan on using 3D Secure, you have to do the following.

##### iOS

###### Configure a new URL scheme

Add a bundle url scheme `{BUNDLE_IDENTIFIER}.payments` in your app Info via XCode or manually in the `Info.plist`.
In your `Info.plist`, you should have something like:

```xml
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleTypeRole</key>
        <string>Editor</string>
        <key>CFBundleURLName</key>
        <string>com.myapp</string>
        <key>CFBundleURLSchemes</key>
        <array>
            <string>com.myapp.payments</string>
        </array>
    </dict>
</array>
```

###### Update your code

In your `AppDelegate.m`:

```objective-c
#import "BraintreeCore.h"

...
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
{
    ...
    [BTAppSwitch setReturnURLScheme:self.paymentsURLScheme];
}

- (BOOL)application:(UIApplication *)application
            openURL:(NSURL *)url
            options:(NSDictionary<UIApplicationOpenURLOptionsKey,id> *)options {

    if ([url.scheme localizedCaseInsensitiveCompare:self.paymentsURLScheme] == NSOrderedSame) {
        return [BTAppSwitch handleOpenURL:url options:options];
    }
    
    return [RCTLinkingManager application:application openURL:url options:options];
}

- (NSString *)paymentsURLScheme {
    NSString *bundleIdentifier = [[NSBundle mainBundle] bundleIdentifier];
    return [NSString stringWithFormat:@"%@.%@", bundleIdentifier, @"payments"];
}
```

##### Android

Setup [browser switch][4].


## Usage

For the API, see the [Flow typings][5].

### Basic

```javascript
import BraintreeDropIn from 'react-native-braintree-payments-drop-in';

BraintreeDropIn.show({
	clientToken: 'token',
        merchantIdentifier: 'applePayMerchantIdentifier',    
        countryCode: 'US',    //apple pay setting
        currencyCode: 'USD',   //apple pay setting
        merchantName: 'Your Merchant Name for Apple Pay',
        orderTotal:'Total Price',
        googlePay: true,
        applePay: true,
})
.then(result => console.log(result))
.catch((error) => {
  if (error.code === 'USER_CANCELLATION') {
    // update your UI to handle cancellation
  } else {
    // update your UI to handle other errors
  }
});
```

### 3D Secure

```javascript
import BraintreeDropIn from 'react-native-braintree-payments-drop-in';

BraintreeDropIn.show({
  clientToken: 'token',
  threeDSecure: {
    amount: 1.0,
  },
})
.then(result => console.log(result))
.catch((error) => {
  if (error.code === 'USER_CANCELLATION') {
    // update your UI to handle cancellation
  } else {
    // update your UI to handle other errors
    // for 3D secure, there are two other specific error codes: 3DSECURE_NOT_ABLE_TO_SHIFT_LIABILITY and 3DSECURE_LIABILITY_NOT_SHIFTED
  }
});
```

[1]:	http://guides.cocoapods.org/using/using-cocoapods.html
[2]:	https://github.com/braintree/braintree-ios-drop-in
[3]:	https://github.com/braintree/braintree-android-drop-in
[4]:	https://developers.braintreepayments.com/guides/client-sdk/setup/android/v2#browser-switch-setup
[5]:	./index.js.flow