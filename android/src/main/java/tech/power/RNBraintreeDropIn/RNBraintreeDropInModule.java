package tech.power.RNBraintreeDropIn;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.braintreepayments.api.BraintreeClient;
import com.braintreepayments.api.Card;
import com.braintreepayments.api.CardClient;
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
      dropInRequest.setCardDisabled(options.getBoolean("cardDisabled"));
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

    clientToken = options.getString("clientToken");

    if (dropInClient == null) {
      promise.reject(
        "DROP_IN_CLIENT_UNINITIALIZED",
        "Did you forget to call RNBraintreeDropInModule.initDropInClient(this) in MainActivity.onCreate?"
      );
      return;
    }
    dropInClient.setListener(new DropInListener() {
      @Override
      public void onDropInSuccess(@NonNull DropInResult dropInResult) {
        PaymentMethodNonce paymentMethodNonce = dropInResult.getPaymentMethodNonce();

        if (isVerifyingThreeDSecure && paymentMethodNonce instanceof CardNonce) {
          CardNonce cardNonce = (CardNonce) paymentMethodNonce;
          ThreeDSecureInfo threeDSecureInfo = cardNonce.getThreeDSecureInfo();
          if (!threeDSecureInfo.isLiabilityShiftPossible()) {
            promise.reject("3DSECURE_NOT_ABLE_TO_SHIFT_LIABILITY", "3D Secure liability cannot be shifted");
          } else if (!threeDSecureInfo.isLiabilityShifted()) {
            promise.reject("3DSECURE_LIABILITY_NOT_SHIFTED", "3D Secure liability was not shifted");
          } else {
            resolvePayment(dropInResult, promise);
          }
        } else {
          resolvePayment(dropInResult, promise);
        }
      }

      @Override
      public void onDropInFailure(@NonNull Exception exception) {
        if (exception instanceof UserCanceledException) {
          promise.reject("USER_CANCELLATION", "The user cancelled");
        } else {
          promise.reject(exception.getMessage(), exception.getMessage());
        }
      }
    });
    dropInClient.launchDropIn(dropInRequest);
  }

  @ReactMethod
  public void fetchMostRecentPaymentMethod(final String clientToken, final Promise promise) {
    FragmentActivity currentActivity = (FragmentActivity) getCurrentActivity();

    if (currentActivity == null) {
      promise.reject("NO_ACTIVITY", "There is no current activity");
      return;
    }

    if (dropInClient == null) {
      promise.reject(
        "DROP_IN_CLIENT_UNINITIALIZED",
        "Did you forget to call RNBraintreeDropInModule.initDropInClient(this) in MainActivity.onCreate?"
      );
      return;
    }

    RNBraintreeDropInModule.clientToken = clientToken;

    dropInClient.fetchMostRecentPaymentMethod(currentActivity, (dropInResult, error) -> {
      if (error != null) {
        promise.reject(error.getMessage(), error.getMessage());
      } else if (dropInResult == null) {
        promise.reject("NO_DROP_IN_RESULT", "dropInResult is null");
      } else {
        resolvePayment(dropInResult, promise);
      }
    });
  }

  @ReactMethod
  public void tokenizeCard(final String clientToken, final ReadableMap cardInfo, final Promise promise) {
    if (clientToken == null) {
      promise.reject("NO_CLIENT_TOKEN", "You must provide a client token");
      return;
    }

    if (
      !cardInfo.hasKey("number") ||
      !cardInfo.hasKey("expirationMonth") ||
      !cardInfo.hasKey("expirationYear") ||
      !cardInfo.hasKey("cvv") ||
      !cardInfo.hasKey("postalCode")
    ) {
      promise.reject("INVALID_CARD_INFO", "Invalid card info");
      return;
    }

    Activity currentActivity = getCurrentActivity();

    if (currentActivity == null) {
      promise.reject("NO_ACTIVITY", "There is no current activity");
      return;
    }

    BraintreeClient braintreeClient = new BraintreeClient(getCurrentActivity(), clientToken);
    CardClient cardClient = new CardClient(braintreeClient);

    Card card = new Card();
    card.setNumber(cardInfo.getString("number"));
    card.setExpirationMonth(cardInfo.getString("expirationMonth"));
    card.setExpirationYear(cardInfo.getString("expirationYear"));
    card.setCvv(cardInfo.getString("cvv"));
    card.setPostalCode(cardInfo.getString("postalCode"));

    cardClient.tokenize(card, (cardNonce, error) -> {
      if (error != null) {
        promise.reject(error.getMessage(), error.getMessage());
      } else if (cardNonce == null) {
        promise.reject("NO_CARD_NONCE", "Card nonce is null");
      } else {
        promise.resolve(cardNonce.getString());
      }
    });
  }

  private void resolvePayment(DropInResult dropInResult, Promise promise) {
    String deviceData = dropInResult.getDeviceData();
    PaymentMethodNonce paymentMethodNonce = dropInResult.getPaymentMethodNonce();

    WritableMap jsResult = Arguments.createMap();

    if (paymentMethodNonce == null) {
      promise.resolve(null);
      return;
    }

    Activity currentActivity = getCurrentActivity();
    if (currentActivity == null) {
      promise.reject("NO_ACTIVITY", "There is no current activity");
      return;
    }

    DropInPaymentMethod dropInPaymentMethod = dropInResult.getPaymentMethodType();
    if (dropInPaymentMethod == null) {
      promise.reject("NO_PAYMENT_METHOD", "There is no payment method");
      return;
    }

    jsResult.putString("nonce", paymentMethodNonce.getString());
    jsResult.putString("type", currentActivity.getString(dropInPaymentMethod.getLocalizedName()));
    jsResult.putString("description", dropInResult.getPaymentDescription());
    jsResult.putBoolean("isDefault", paymentMethodNonce.isDefault());
    jsResult.putString("deviceData", deviceData);

    promise.resolve(jsResult);
  }

  @NonNull
  @Override
  public String getName() {
    return "RNBraintreeDropIn";
  }
}
