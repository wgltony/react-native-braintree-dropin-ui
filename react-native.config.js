const path = require('path');

module.exports = {
  dependency: {
    platforms: {
      ios: { podspecPath: path.join(__dirname, 'ios', 'RNBraintreeDropIn.podspec') },
      android: {
      	packageImportPath: 'import tech.power.RNBraintreeDropIn.RNBraintreeDropInPackage;',
        packageInstance: 'new RNBraintreeDropInPackage()',
      },
    },
  },
};
