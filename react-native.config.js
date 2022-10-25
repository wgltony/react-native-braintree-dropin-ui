const path = require('path');

module.exports = {
  dependency: {
    platforms: {
      ios: {},
      android: {
      	packageImportPath: 'import tech.power.RNBraintreeDropIn.RNBraintreeDropInPackage;',
        packageInstance: 'new RNBraintreeDropInPackage()',
      },
    },
  },
};
