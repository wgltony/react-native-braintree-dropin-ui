package tech.power.RNBraintreeDropIn;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.braintreepayments.api.DropInClient;
import com.braintreepayments.api.DropInListener;
import com.braintreepayments.api.DropInPaymentMethod;
import com.braintreepayments.api.ThreeDSecureRequest;
import com.braintreepayments.api.UserCanceledException;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Promise;
import com.braintreepayments.api.DropInRequest;
import com.braintreepayments.api.DropInResult;
import com.braintreepayments.api.PaymentMethodNonce;
import com.braintreepayments.api.CardNonce;
import com.braintreepayments.api.ThreeDSecureInfo;
import com.braintreepayments.api.GooglePayRequest;
import com.google.android.gms.wallet.TransactionInfo;
import com.google.android.gms.wallet.WalletConstants;

import java.util.Objects;

public class RNBraintreeDropInModule extends ReactContextBaseJavaModule {

  private Promise mPromise;

  private boolean isVerifyingThreeDSecure = false;

  private static DropInClient dropInClient = null;
  private static String clientToken = null;

  public static void initDropInClient(FragmentActivity activity) {
    dropInClient = new DropInClient(activity, callback -> {
      if (clientToken != null) {
        callback.onSuccess(clientToken);
      } else {
        callback.onFailure(new Exception("Client token is null"));
      }
    });
  }

  public RNBraintreeDropInModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @ReactMethod
  public void show(final ReadableMap options, final Promise promise) {
    isVerifyingThreeDSecure = false;

    if (!options.hasKey("clientToken")) {
      promise.reject("NO_CLIENT_TOKEN", "You must provide a client token");
      return;
    }

    FragmentActivity currentActivity = (FragmentActivity) getCurrentActivity();
    if (currentActivity == null) {
      promise.reject("NO_ACTIVITY", "There is no current activity");
      return;
    }

    DropInRequest dropInRequest = new DropInRequest();

    if(options.hasKey("vaultManager")) {
      dropInRequest.setVaultManagerEnabled(options.getBoolean("vaultManager"));
    }

    if(options.hasKey("googlePay") && options.getBoolean("googlePay")){
      GooglePayRequest googlePayRequest = new GooglePayRequest();
      googlePayRequest.setTransactionInfo(TransactionInfo.newBuilder()
          .setTotalPrice(Objects.requireNonNull(options.getString("orderTotal")))
          .setTotalPriceStatus(WalletConstants.TOTAL_PRICE_STATUS_FINAL)
          .setCurrencyCode(Objects.requireNonNull(options.getString("currencyCode")))
          .build());
      googlePayRequest.setBillingAddressRequired(true);
      googlePayRequest.setGoogleMerchantId(options.getString("googlePayMerchantId"));

      dropInRequest.setGooglePayDisabled(false);
      dropInRequest.setGooglePayRequest(googlePayRequest);
    }else{
        dropInRequest.setGooglePayDisabled(true);
    }

    if(options.hasKey("cardDisabled")) {
      dropInRequest.setCardDisabled(true);
    }

    if (options.hasKey("threeDSecure")) {
      final ReadableMap threeDSecureOptions = options.getMap("threeDSecure");
      if (threeDSecureOptions == null || !threeDSecureOptions.hasKey("amount")) {
        promise.reject("NO_3DS_AMOUNT", "You must provide an amount for 3D Secure");
        return;
      }

      isVerifyingThreeDSecure = true;

      ThreeDSecureRequest threeDSecureRequest = new ThreeDSecureRequest();
      threeDSecureRequest.setAmount(threeDSecureOptions.getString("amount"));

      dropInRequest.setThreeDSecureRequest(threeDSecureRequest);
    }

    dropInRequest.setPayPalDisabled(!options.hasKey("payPal") || !options.getBoolean("payPal"));

    mPromise = promise;

    clientToken = options.getString("clientToken");

    if (dropInClient == null) {
      mPromise.reject(
        "DROP_IN_CLIENT_UNINITIALIZED",
        "Did you forget to call RNBraintreeDropInModule.initDropInClient(this) in MainActivity.onCreate?"
      );
      mPromise = null;
      return;
    }
    dropInClient.setListener(mDropInListener);
    dropInClient.launchDropIn(dropInRequest);
  }

  private final DropInListener mDropInListener = new DropInListener() {
    @Override
    public void onDropInSuccess(@NonNull DropInResult dropInResult) {
      if (mPromise == null) {
        return;
      }
      PaymentMethodNonce paymentMethodNonce = dropInResult.getPaymentMethodNonce();
      String deviceData = dropInResult.getDeviceData();

      if (isVerifyingThreeDSecure && paymentMethodNonce instanceof CardNonce) {
        CardNonce cardNonce = (CardNonce) paymentMethodNonce;
        ThreeDSecureInfo threeDSecureInfo = cardNonce.getThreeDSecureInfo();
        if (!threeDSecureInfo.isLiabilityShiftPossible()) {
          mPromise.reject("3DSECURE_NOT_ABLE_TO_SHIFT_LIABILITY", "3D Secure liability cannot be shifted");
        } else if (!threeDSecureInfo.isLiabilityShifted()) {
          mPromise.reject("3DSECURE_LIABILITY_NOT_SHIFTED", "3D Secure liability was not shifted");
        } else {
          resolvePayment(dropInResult, deviceData);
        }
      } else {
        resolvePayment(dropInResult, deviceData);
      }

      mPromise = null;
    }

    @Override
    public void onDropInFailure(@NonNull Exception exception) {
      if (mPromise == null) {
        return;
      }

      if (exception instanceof UserCanceledException) {
        mPromise.reject("USER_CANCELLATION", "The user cancelled");
      } else {
        mPromise.reject(exception.getMessage(), exception.getMessage());
      }

      mPromise = null;
    }
  };

  private void resolvePayment(DropInResult dropInResult, String deviceData) {
    PaymentMethodNonce paymentMethodNonce = dropInResult.getPaymentMethodNonce();

    WritableMap jsResult = Arguments.createMap();

    if (paymentMethodNonce == null) {
      mPromise.reject("NO_PAYMENT_METHOD_NONCE", "Payment method nonce is missing");
      return;
    }

    Activity currentActivity = getCurrentActivity();
    if (currentActivity == null) {
      mPromise.reject("NO_ACTIVITY", "There is no current activity");
      return;
    }

    DropInPaymentMethod dropInPaymentMethod = dropInResult.getPaymentMethodType();
    if (dropInPaymentMethod == null) {
      mPromise.reject("NO_PAYMENT_METHOD", "There is no payment method");
      return;
    }

    jsResult.putString("nonce", paymentMethodNonce.getString());
    jsResult.putString("type", currentActivity.getString(dropInPaymentMethod.getLocalizedName()));
    jsResult.putString("description", dropInResult.getPaymentDescription());
    jsResult.putBoolean("isDefault", paymentMethodNonce.isDefault());
    jsResult.putString("deviceData", deviceData);

    mPromise.resolve(jsResult);
  }

  @NonNull
  @Override
  public String getName() {
    return "RNBraintreeDropIn";
  }
}
