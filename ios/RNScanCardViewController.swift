//
//  RnScanCardViewController.swift
//  babyio
//
//  Created by  Cacley Technologies on 6/25/20.
//  Copyright © 2020 Facebook. All rights reserved.
//

import UIKit
import PayCardsRecognizer

@objc class RnScanCardViewController: UIViewController, PayCardsRecognizerPlatformDelegate {

    var promiseResolve: RCTPromiseResolveBlock!
    var promiseReject: RCTPromiseRejectBlock!
  
   @objc
   public required convenience init(resolve: @escaping RCTPromiseResolveBlock, reject:  @escaping RCTPromiseRejectBlock) {
      self.init()
      promiseResolve = resolve
      promiseReject = reject
    }
  
   var recognizer: PayCardsRecognizer!
   
   override func viewDidLoad() {
       super.viewDidLoad()
       recognizer = PayCardsRecognizer(delegate: self, resultMode: .sync, container: self.view, frameColor: .green)
   }
   
   override func viewWillAppear(_ animated: Bool) {
       super.viewWillAppear(animated)
       recognizer.startCamera()
   }
       
   override func viewDidDisappear(_ animated: Bool) {
       super.viewDidDisappear(animated)
       recognizer.stopCamera()
   }

   func payCardsRecognizer(_ payCardsRecognizer: PayCardsRecognizer, didRecognize result: PayCardsRecognizerResult) {
      if (result.isCompleted) {
        self.dismiss(animated: true, completion: nil)
        promiseResolve(result);
      } else {
        promiseReject("FAILED", "Could not scan the card", nil)
      }
   }
  
  func payCardsRecognizer(_ payCardsRecognizer: PayCardsRecognizer, didCancel result: PayCardsRecognizerResult?) {
      self.dismiss(animated: true, completion: nil)
      promiseReject("CANCELLED", "Cancelled by user", nil)
  }
}

