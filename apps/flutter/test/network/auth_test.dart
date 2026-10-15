import 'dart:convert';
import 'dart:io';

import 'package:c001apk_flutter/logic/network/interceptor.dart';
import 'package:c001apk_flutter/utils/storage_util.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive/hive.dart';

void main() {
  late Directory directory;

  setUpAll(() async {
    directory = await Directory.systemTemp.createTemp('c001apk-auth-test-');
    Hive.init(directory.path);
    GStorage.settings = await Hive.openBox('settings');
  });
  tearDownAll(() async {
    await Hive.close();
    await directory.delete(recursive: true);
  });
  setUp(() async {
    await GStorage.settings.clear();
    await GStorage.settings.putAll({
      SettingsBoxKey.xAppDevice: 'synthetic-test-device',
      SettingsBoxKey.versionCode: '2604201',
      SettingsBoxKey.versionName: '16.2.0',
      SettingsBoxKey.apiVersion: '16',
    });
  });

  test('API token uses v3 bcrypt cost 4', () {
    final token = ApiInterceptor().token;
    expect(token, startsWith('v3'));
    expect(utf8.decode(base64.decode(base64.normalize(token.substring(2)))),
        startsWith(r'$2y$04$'));
  });

  test('device changes invalidate the next signature without restart',
      () async {
    final interceptor = ApiInterceptor();
    final before = interceptor.token;
    await GStorage.settings
        .put(SettingsBoxKey.xAppDevice, 'another-test-device');
    expect(interceptor.xAppDevice, 'another-test-device');
    expect(interceptor.token, isNot(before));
  });

  test('protocol header changes are visible without restart', () async {
    final interceptor = ApiInterceptor();
    expect(interceptor.versionName, '16.2.0');
    await GStorage.settings.put(SettingsBoxKey.versionName, '16.2.1');
    expect(interceptor.versionName, '16.2.1');
  });
}
