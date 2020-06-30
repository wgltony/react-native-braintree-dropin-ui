package tech.power.RNBraintreeDropIn;

import androidx.appcompat.app.AppCompatActivity;

import com.braintreepayments.api.BraintreeFragment;
import com.braintreepayments.api.Card;
import com.braintreepayments.api.exceptions.BraintreeError;
import com.braintreepayments.api.exceptions.ErrorWithResponse;
import com.braintreepayments.api.interfaces.BraintreeCancelListener;
import com.braintreepayments.api.interfaces.BraintreeErrorListener;
import com.braintreepayments.api.interfaces.PaymentMethodNonceCreatedListener;
import com.braintreepayments.api.models.CardBuilder;
import com.braintreepayments.api.models.PayPalAccountNonce;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Promise;
import com.braintreepayments.api.models.PaymentMethodNonce;
import com.braintreepayments.api.models.CardNonce;
import com.google.gson.Gson;
import com.braintreepayments.api.PayPal;
import com.braintreepayments.api.models.PayPalRequest;
import java.util.HashMap;
import java.util.Map;

public class RNBraintreeDropInModule extends ReactContextBaseJavaModule {

  private Promise mPromise;
  private boolean isVerifyingThreeDSecure = false;
  private BraintreeFragment mBraintreeFragment;
  private ReadableMap threeDSecureOptions;
  private String token;

  RNBraintreeDropInModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }


  @ReactMethod
  public void paypalLogin(final ReadableMap options, Promise promise) {
    this.mPromise = promise;
    if (!options.hasKey("clientToken")) {
      mPromise.reject("NO_CLIENT_TOKEN", "You must provide a client token");
      return;
    }
    this.setToken(options.getString("clientToken"));
    createNewFragmentInstance();

    this.mBraintreeFragment.addListener(new BraintreeErrorListener() {
      @Override
      public void onError(Exception error) {
        if (error instanceof ErrorWithResponse) {
          ErrorWithResponse errorWithResponse = (ErrorWithResponse) error;
          mPromise.reject("ERROR", errorWithResponse.getMessage());
        }
      }
    });

    PayPalRequest request = new PayPalRequest().billingAgreementDescription("Your agreement description");
    PayPal.requestBillingAgreement(mBraintreeFragment, request);
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

    if (parameters.hasKey("merchantAccountId"))
      cardBuilder.merchantAccountId(parameters.getString("merchantAccountId"));

    if (parameters.hasKey("countryCode"))
      cardBuilder.countryCode(parameters.getString("countryCode"));

    Card.tokenize(this.mBraintreeFragment, cardBuilder);
  }


  private void createNewFragmentInstance() {
    try {
      AppCompatActivity currentActivity = (AppCompatActivity) getCurrentActivity();
      if (currentActivity == null) {
        mPromise.reject("NO_ACTIVITY", "There is no current activity");
        return;
      }
      this.mBraintreeFragment = BraintreeFragment.newInstance(currentActivity, this.token);
      this.mBraintreeFragment.addListener(new BraintreeCancelListener() {
        @Override
        public void onCancel(int requestCode) {
          mPromise.reject("USER_CANCELLATION", "USER_CANCELLATION");
        }
      });
      this.mBraintreeFragment.addListener(new PaymentMethodNonceCreatedListener() {
        @Override
        public void onPaymentMethodNonceCreated(PaymentMethodNonce paymentMethodNonce) {

          if (paymentMethodNonce instanceof PayPalAccountNonce) {
            resolvePaypal(paymentMethodNonce);
          } else if (paymentMethodNonce instanceof CardNonce) {
            resolveCard(paymentMethodNonce);
          }
        }
      });

    } catch (Exception e) {
      mPromise.reject("ERROR", e.getMessage());
      e.printStackTrace();
    }
  }

  private void setToken(String token) {
    this.token = token;
  }

  private void setup(final String token, Promise promise) {
    try {
      this.setToken(token);
      this.mPromise = promise;
      createNewFragmentInstance();
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

              mPromise.reject("0", gson.toJson(errors));
            } else {
              mPromise.reject("0", errorWithResponse.getErrorResponse());
            }
          }
        }
      });
    } catch (Exception e) {
      promise.reject("0", e.getMessage());
    }
  }

  private void resolvePaypal(PaymentMethodNonce paymentMethodNonce) {
    WritableMap jsResult = Arguments.createMap();
    jsResult.putString("nonce", paymentMethodNonce.getNonce());
    jsResult.putString("type", paymentMethodNonce.getTypeLabel());
    jsResult.putString("description", paymentMethodNonce.getDescription());
    jsResult.putBoolean("isDefault", paymentMethodNonce.isDefault());

    mPromise.resolve(jsResult);
  }

  private void resolveCard (PaymentMethodNonce paymentMethodNonce) {
    Gson gson = new Gson();
    final Map<String, String> data = new HashMap<>();
    if (threeDSecureOptions != null && paymentMethodNonce instanceof CardNonce) {
      CardNonce cardNonce = (CardNonce) paymentMethodNonce;
      if (!cardNonce.getThreeDSecureInfo().isLiabilityShiftPossible()) {
        mPromise.reject("2", "3DSECURE_NOT_ABLE_TO_SHIFT_LIABILITY");
      } else if (!cardNonce.getThreeDSecureInfo().isLiabilityShifted()) {
        mPromise.reject("2", "3DSECURE_LIABILITY_NOT_SHIFTED");
      } else {
        data.put("nonce", paymentMethodNonce.getNonce());
        mPromise.resolve(gson.toJson(data));
      }
    } else {
      data.put("nonce", paymentMethodNonce.getNonce());
      mPromise.resolve(gson.toJson(data));
    }
  }



  @Override
  public String getName() {
    return "RNBraintreeDropIn";
  }
}
