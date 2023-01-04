Pod::Spec.new do |s|
  s.name         = "RNBraintreeDropIn"
  s.version      = "1.1.3"
  s.summary      = "RNBraintreeDropIn"
  s.description  = <<-DESC
                  RNBraintreeDropIn
                   DESC
  s.homepage     = "https://github.com/bamlab/react-native-braintree-payments-drop-in"
  s.license      = "MIT"
  # s.license      = { :type => "MIT", :file => "./LICENSE" }
  s.author             = { "author" => "lagrange.louis@gmail.com" }
  s.platform     = :ios, "9.0"
  s.source       = { :git => "https://github.com/BradyShober/react-native-braintree-dropin-ui.git", :tag => "master" }
  s.source_files  = "ios/**/*.{h,m}"
  s.requires_arc = true
  s.dependency    'React'
  s.dependency    'Braintree'
  s.dependency    'BraintreeDropIn'
  s.dependency    'Braintree/DataCollector'
  s.dependency    'Braintree/Apple-Pay'
  s.dependency    'Braintree/Venmo'
end
