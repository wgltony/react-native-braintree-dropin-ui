package tech.power.RNBraintreeDropIn;

import android.app.Activity;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

import com.braintreepayments.api.BraintreeFragment;
import com.braintreepayments.api.Card;
import com.braintreepayments.api.exceptions.BraintreeError;
import com.braintreepayments.api.exceptions.ErrorWithResponse;
import com.braintreepayments.api.exceptions.InvalidArgumentException;
import com.braintreepayments.api.interfaces.BraintreeCancelListener;
import com.braintreepayments.api.interfaces.BraintreeErrorListener;
import com.braintreepayments.api.interfaces.PaymentMethodNonceCreatedListener;
import com.braintreepayments.api.models.CardBuilder;
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
import com.google.gson.Gson;
import com.braintreepayments.api.PayPal;
import com.braintreepayments.api.interfaces.PayPalApprovalCallback;
import com.braintreepayments.api.interfaces.PayPalApprovalHandler;
import com.braintreepayments.api.models.ClientToken;
import com.braintreepayments.api.models.PayPalAccountNonce;
import com.braintreepayments.api.models.PayPalRequest;
import com.braintreepayments.api.models.PostalAddress;
import com.paypal.android.sdk.onetouch.core.Request;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class RNBraintreeDropInModule extends ReactContextBaseJavaModule {

  private Promise mPromise;
  // private static final int DROP_IN_REQUEST = 0x444;

  private boolean isVerifyingThreeDSecure = false;

  private BraintreeFragment mBraintreeFragment;

  private ReadableMap threeDSecureOptions;

  private String token;

  public RNBraintreeDropInModule(ReactApplicationContext reactContext) {
    super(reactContext);
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

  public void setToken(String token) {
    this.token = token;
  }

  // @ReactMethod
  private void setup(final String token, final Promise promise) {
    try {
      AppCompatActivity appCompatActivity = (AppCompatActivity) getCurrentActivity();
      this.mBraintreeFragment = BraintreeFragment.newInstance(appCompatActivity, token);
      this.mBraintreeFragment.addListener(new BraintreeCancelListener() {
        @Override
        public void onCancel(int requestCode) {
          promise.reject("1", "USER_CANCELLATION");
        }
      });
      this.mBraintreeFragment.addListener(new PaymentMethodNonceCreatedListener() {
        @Override
        public void onPaymentMethodNonceCreated(PaymentMethodNonce paymentMethodNonce) {
          Gson gson = new Gson();
          final Map<String, String> data = new HashMap<>();
          if (threeDSecureOptions != null && paymentMethodNonce instanceof CardNonce) {
            CardNonce cardNonce = (CardNonce) paymentMethodNonce;
            if (!cardNonce.getThreeDSecureInfo().isLiabilityShiftPossible()) {
              promise.reject("2", "3DSECURE_NOT_ABLE_TO_SHIFT_LIABILITY");
            } else if (!cardNonce.getThreeDSecureInfo().isLiabilityShifted()) {
              promise.reject("2", "3DSECURE_LIABILITY_NOT_SHIFTED");
            } else {
              data.put("nonce", paymentMethodNonce.getNonce());
              promise.resolve(gson.toJson(data));
            }
          } else {
            data.put("nonce", paymentMethodNonce.getNonce());
            promise.resolve(gson.toJson(data));
          }
        }
      });
      this.mBraintreeFragment.addListener(new BraintreeErrorListener() {
        @Override
        public void onError(Exception error) {
          if (error instanceof ErrorWithResponse) {
            ErrorWithResponse errorWithResponse = (ErrorWithResponse) error;
            BraintreeError cardErrors = errorWithResponse.errorFor("creditCard");
            if (cardErrors != null) {
              Gson gson = new Gson();
              final Map<String, String> errors = new HashMap<>();
              BraintreeError numberError = cardErrors.errorFor("number");
              BraintreeError cvvError = cardErrors.errorFor("cvv");
              BraintreeError expirationDateError = cardErrors.errorFor("expirationDate");
              BraintreeError postalCode = cardErrors.errorFor("postalCode");

              if (numberError != null) {
                errors.put("card_number", numberError.getMessage());
              }

              if (cvvError != null) {
                errors.put("cvv", cvvError.getMessage());
              }

              if (expirationDateError != null) {
                errors.put("expiration_date", expirationDateError.getMessage());
              }

              if (postalCode != null) {
                errors.put("postal_code", postalCode.getMessage());
              }

              promise.reject("0", gson.toJson(errors));
            } else {
              promise.reject("0", errorWithResponse.getErrorResponse());
            }
          }
        }
      });
      this.setToken(token);
    } catch (InvalidArgumentException e) {
      promise.reject("0", e.getMessage());
    }
  }

  @ReactMethod
  public void tokenize(String authorization, final ReadableMap parameters, final Promise promise) {
    setup(authorization, promise);

    CardBuilder cardBuilder = new CardBuilder().validate(true);

    if (parameters.hasKey("number"))
      cardBuilder.cardNumber(parameters.getString("number"));

    if (parameters.hasKey("cvv"))
      cardBuilder.cvv(parameters.getString("cvv"));

    // In order to keep compatibility with iOS implementation, do not accept
    // expirationMonth and exporationYear,
    // accept rather expirationDate (which is combination of
    // expirationMonth/expirationYear)
    if (parameters.hasKey("expirationDate"))
      cardBuilder.expirationDate(parameters.getString("expirationDate"));

    if (parameters.hasKey("expirationYear"))
      cardBuilder.expirationYear(parameters.getString("expirationYear"));

    if (parameters.hasKey("expirationMonth"))
      cardBuilder.expirationMonth(parameters.getString("expirationMonth"));

    if (parameters.hasKey("cardholderName"))
      cardBuilder.cardholderName(parameters.getString("cardholderName"));

    if (parameters.hasKey("firstName"))
      cardBuilder.firstName(parameters.getString("firstName"));

    if (parameters.hasKey("lastName"))
      cardBuilder.lastName(parameters.getString("lastName"));

    if (parameters.hasKey("company"))
      cardBuilder.company(parameters.getString("company"));

    if (parameters.hasKey("locality"))
      cardBuilder.locality(parameters.getString("locality"));

    if (parameters.hasKey("postalCode"))
      cardBuilder.postalCode(parameters.getString("postalCode"));

    if (parameters.hasKey("region"))
      cardBuilder.region(parameters.getString("region"));

    if (parameters.hasKey("streetAddress"))
      cardBuilder.streetAddress(parameters.getString("streetAddress"));

    if (parameters.hasKey("extendedAddress"))
      cardBuilder.extendedAddress(parameters.getString("extendedAddress"));

    Card.tokenize(this.mBraintreeFragment, cardBuilder);
  }

  @Override
  public String getName() {
    return "RNBraintreeDropIn";
  }
}
