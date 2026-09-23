// after_prepare hook: overwrite the platform's MainActivity.java with our
// gamepad-capturing version. See the comment in plugin.xml for why this has
// to be a hook instead of a <source-file> entry.
var fs = require('fs');
var path = require('path');

module.exports = function (context) {
    var pluginDir = path.join(__dirname, '..');
    var projectRoot = context.opts.projectRoot;

    var src = path.join(pluginDir, 'src', 'android', 'MainActivity.java');
    var destDir = path.join(
        projectRoot, 'platforms', 'android', 'app', 'src', 'main', 'java',
        'com', 'flats', 'mtsyntho'
    );
    var dest = path.join(destDir, 'MainActivity.java');

    if (!fs.existsSync(src)) {
        console.warn('[cordova-plugin-native-gamepad] missing source file: ' + src);
        return;
    }
    if (!fs.existsSync(destDir)) {
        // Android platform not present yet -- nothing to patch this round.
        return;
    }

    fs.copyFileSync(src, dest);
    console.log('[cordova-plugin-native-gamepad] installed custom MainActivity.java');
};
