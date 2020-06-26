package tech.power.RNBraintreeDropIn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.braintreepayments.api.BraintreeFragment;
import com.braintreepayments.api.PayPal;
import com.braintreepayments.api.exceptions.BraintreeError;
import com.braintreepayments.api.exceptions.ErrorWithResponse;
import com.braintreepayments.api.exceptions.InvalidArgumentException;
import com.braintreepayments.api.interfaces.BraintreeCancelListener;
import com.braintreepayments.api.interfaces.BraintreeErrorListener;
import com.braintreepayments.api.interfaces.PayPalApprovalCallback;
import com.braintreepayments.api.interfaces.PayPalApprovalHandler;
import com.braintreepayments.api.interfaces.PaymentMethodNonceCreatedListener;
import com.braintreepayments.api.models.ClientToken;
import com.braintreepayments.api.models.PayPalAccountNonce;
import com.braintreepayments.api.models.PayPalRequest;
import com.braintreepayments.api.models.PostalAddress;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.braintreepayments.api.dropin.DropInActivity;
import com.braintreepayments.api.dropin.DropInRequest;
import com.braintreepayments.api.dropin.DropInResult;
import com.braintreepayments.api.models.PaymentMethodNonce;
import com.braintreepayments.api.models.CardNonce;
import com.braintreepayments.api.models.ThreeDSecureInfo;
import com.braintreepayments.api.models.GooglePaymentRequest;
import com.google.android.gms.wallet.TransactionInfo;
import com.google.android.gms.wallet.WalletConstants;
import com.paypal.android.sdk.onetouch.core.Request;

public class RNBraintreeDropInModule extends ReactContextBaseJavaModule {

  private Promise mPromise;
  private BraintreeFragment mBraintreeFragment;

  private static final int DROP_IN_REQUEST = 0x444;

  private boolean isVerifyingThreeDSecure = false;

  public RNBraintreeDropInModule(ReactApplicationContext reactContext) {
    super(reactContext);
    reactContext.addActivityEventListener(mActivityListener);
  }

  private void createNewFragmentInstance(String token) {
    try {
      AppCompatActivity currentActivity = (AppCompatActivity) getCurrentActivity();
      if (currentActivity == null) {
        mPromise.reject("NO_ACTIVITY", "There is no current activity");
        return;
      }
      this.mBraintreeFragment = BraintreeFragment.newInstance(currentActivity, token);
      this.mBraintreeFragment.addListener(new BraintreeCancelListener() {
        @Override
        public void onCancel(int requestCode) {
          mPromise.reject("USER_CANCELLATION", "USER_CANCELLATION");
        }
      });
      this.mBraintreeFragment.addListener(new PaymentMethodNonceCreatedListener() {
        @Override
        public void onPaymentMethodNonceCreated(PaymentMethodNonce paymentMethodNonce) {
          Log.d("Naveen", "onPaymentMethodNonceCreated: " + paymentMethodNonce);
          resolvePayment(paymentMethodNonce, null);
        }
      });
      this.mBraintreeFragment.addListener(new BraintreeErrorListener() {
        @Override
        public void onError(Exception error) {
          if (error instanceof ErrorWithResponse) {
            ErrorWithResponse errorWithResponse = (ErrorWithResponse) error;
            Log.d("Naveen", "onError: " + errorWithResponse.getMessage());
          }
        }
      });
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void paypalLogin(final ReadableMap options, final Promise promise) {
    if (!options.hasKey("clientToken")) {
      promise.reject("NO_CLIENT_TOKEN", "You must provide a client token");
      return;
    }
    String token = options.getString("clientToken");
    createNewFragmentInstance(token);
    PayPalRequest request = new PayPalRequest().billingAgreementDescription("Your agreement description");
    mPromise = promise;
    PayPal.requestBillingAgreement(mBraintreeFragment, request);
  }

  @ReactMethod
  public void tokenize(String authorization, final ReadableMap parameters, final Promise promise) {
    setup(authorization, promise);
  }

  @ReactMethod
  public void show(final ReadableMap options, final Promise promise) {
    isVerifyingThreeDSecure = false;

    if (!options.hasKey("clientToken")) {
      promise.reject("NO_CLIENT_TOKEN", "You must provide a client token");
      return;
    }

    Activity currentActivity = getCurrentActivity();
    if (currentActivity == null) {
      promise.reject("NO_ACTIVITY", "There is no current activity");
      return;
    }

    DropInRequest dropInRequest = new DropInRequest().clientToken(options.getString("clientToken"));

    if (options.hasKey("vaultManager")) {
      dropInRequest.vaultManager(options.getBoolean("vaultManager"));
    }

    dropInRequest.collectDeviceData(true);

    if (options.getBoolean("googlePay")) {
      GooglePaymentRequest googlePaymentRequest = new GooglePaymentRequest()
          .transactionInfo(TransactionInfo.newBuilder().setTotalPrice(options.getString("orderTotal"))
              .setTotalPriceStatus(WalletConstants.TOTAL_PRICE_STATUS_FINAL)
              .setCurrencyCode(options.getString("currencyCode")).build())
          .billingAddressRequired(true).googleMerchantId(options.getString("googlePayMerchantId"));

      dropInRequest.googlePaymentRequest(googlePaymentRequest);
    }

    if (options.hasKey("threeDSecure")) {
      final ReadableMap threeDSecureOptions = options.getMap("threeDSecure");
      if (!threeDSecureOptions.hasKey("amount")) {
        promise.reject("NO_3DS_AMOUNT", "You must provide an amount for 3D Secure");
        return;
      }

      isVerifyingThreeDSecure = true;

      dropInRequest.amount(String.valueOf(threeDSecureOptions.getDouble("amount")))
          .requestThreeDSecureVerification(true);
    }

    mPromise = promise;
    currentActivity.startActivityForResult(dropInRequest.getIntent(currentActivity), DROP_IN_REQUEST);
  }

  private final ActivityEventListener mActivityListener = new BaseActivityEventListener() {
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
      super.onActivityResult(activity, requestCode, resultCode, data);

      if (requestCode != DROP_IN_REQUEST || mPromise == null) {
        return;
      }

      if (resultCode == Activity.RESULT_OK) {
        DropInResult result = data.getParcelableExtra(DropInResult.EXTRA_DROP_IN_RESULT);
        PaymentMethodNonce paymentMethodNonce = result.getPaymentMethodNonce();
        String deviceData = result.getDeviceData();

        if (isVerifyingThreeDSecure && paymentMethodNonce instanceof CardNonce) {
          CardNonce cardNonce = (CardNonce) paymentMethodNonce;
          ThreeDSecureInfo threeDSecureInfo = cardNonce.getThreeDSecureInfo();
          if (!threeDSecureInfo.isLiabilityShiftPossible()) {
            mPromise.reject("3DSECURE_NOT_ABLE_TO_SHIFT_LIABILITY", "3D Secure liability cannot be shifted");
          } else if (!threeDSecureInfo.isLiabilityShifted()) {
            mPromise.reject("3DSECURE_LIABILITY_NOT_SHIFTED", "3D Secure liability was not shifted");
          } else {
            resolvePayment(paymentMethodNonce, deviceData);
          }
        } else {
          resolvePayment(paymentMethodNonce, deviceData);
        }
      } else if (resultCode == Activity.RESULT_CANCELED) {
        mPromise.reject("USER_CANCELLATION", "The user cancelled");
      } else {
        Exception exception = (Exception) data.getSerializableExtra(DropInActivity.EXTRA_ERROR);
        mPromise.reject(exception.getMessage(), exception.getMessage());
      }

      mPromise = null;
    }

  };

  private final void resolvePayment(PaymentMethodNonce paymentMethodNonce, String deviceData) {
    WritableMap jsResult = Arguments.createMap();
    jsResult.putString("nonce", paymentMethodNonce.getNonce());
    jsResult.putString("type", paymentMethodNonce.getTypeLabel());
    jsResult.putString("description", paymentMethodNonce.getDescription());
    jsResult.putBoolean("isDefault", paymentMethodNonce.isDefault());
    jsResult.putString("deviceData", deviceData);

    mPromise.resolve(jsResult);
  }

  @Override
  public String getName() {
    return "RNBraintreeDropIn";
  }

}
