import 'dart:io';

Future<String> resolveHivePath(Directory supportDirectory,
    {required bool windows}) async {
  if (windows) {
    // Windows path_provider derives this directory from ProductName. Keep
    // existing account/settings/history in place when the display name changes.
    final legacy =
        Directory('${supportDirectory.parent.path}/c001apk-flutter/hive');
    if (await legacy.exists()) return legacy.path;
  }
  return '${supportDirectory.path}/hive';
}
