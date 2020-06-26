package tech.power.RNScanCard;

import android.app.Activity;
import android.content.Intent;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;

import cards.pay.paycardsrecognizer.sdk.Card;
import cards.pay.paycardsrecognizer.sdk.ScanCardIntent;

public class RNScanCardModule extends ReactContextBaseJavaModule {
  private Promise mPromise;

  static final int REQUEST_CODE_SCAN_CARD = 999;

  RNScanCardModule(ReactApplicationContext reactContext) {
    super(reactContext);
    reactContext.addActivityEventListener(mActivityListener);
  }

  @ReactMethod
  public void show(final Promise promise) {
    Activity currentActivity = getCurrentActivity();
    if (currentActivity == null) {
      promise.reject("NO_ACTIVITY", "There is no current activity");
      return;
    }

    mPromise = promise;
    Intent intent = new ScanCardIntent.Builder(this.getCurrentActivity()).build();
    intent.setFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME);
    currentActivity.startActivityForResult(intent, REQUEST_CODE_SCAN_CARD);
  }

  private final ActivityEventListener mActivityListener = new BaseActivityEventListener() {
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
      super.onActivityResult(requestCode, resultCode, data);

      if (requestCode != REQUEST_CODE_SCAN_CARD || mPromise == null) {
        mPromise.reject("WRONG_REQUEST_CODE", "Wrong request code");;
      }

      if (requestCode == REQUEST_CODE_SCAN_CARD) {
        if (resultCode == Activity.RESULT_OK) {
          Card card = data.getParcelableExtra(ScanCardIntent.RESULT_PAYCARDS_CARD);
          String cardData = "Card number: " + card.getCardNumberRedacted() + "\n" + "Card holder: "
                  + card.getCardHolderName() + "\n" + "Card expiration date: " + card.getExpirationDate();
          resolveCard(card);
        } else if (resultCode == Activity.RESULT_CANCELED) {
          mPromise.reject("CANCELLED_BY_USER", "Cancelled by user");
        } else {
          mPromise.reject("ERROR", "Something went wrong");
        }
      }
      mPromise = null;
    }
  };

  private final void resolveCard(Card card) {
    WritableMap jsResult = Arguments.createMap();
    jsResult.putString("name", card.getCardHolderName());
    jsResult.putString("expiry", card.getExpirationDate());
    jsResult.putString("cardNumber", card.getCardNumber());
    jsResult.putString("redacted", card.getCardNumberRedacted());
    jsResult.putString("card", card.toString());

    mPromise.resolve(jsResult);
  }

  @Override
  public String getName() {
    return "RNScanCard";
  }
}


