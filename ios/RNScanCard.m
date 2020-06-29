#import "RNScanCard.h"
#import <React/RCTUtils.h>

@implementation RNScanCard

RCT_EXPORT_MODULE(RNScanCard)

RCT_EXPORT_METHOD(show:(NSDictionary*)options resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
  dispatch_async(dispatch_get_main_queue(), ^(void) {
      RNScanCardViewController *controller = [[RNScanCardViewController alloc]init];
      UIViewController *rootViewController = [UIApplication sharedApplication].delegate.window.rootViewController;
      [rootViewController presentViewController:controller animated:YES completion:nil];
  });
 
}

@end

