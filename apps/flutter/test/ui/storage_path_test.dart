import 'dart:io';

import 'package:c001apk_flutter/utils/storage_path.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late Directory temp;
  setUp(() async =>
      temp = await Directory.systemTemp.createTemp('c001apk-path-'));
  tearDown(() async => temp.delete(recursive: true));

  test('Windows rename continues using existing history and settings folder',
      () async {
    final legacy = await Directory('${temp.path}/c001apk-flutter/hive')
        .create(recursive: true);
    await File('${legacy.path}/settings.hive').writeAsString('synthetic data');
    final current = Directory('${temp.path}/c001apk');
    final resolved = await resolveHivePath(current, windows: true);
    expect(resolved, legacy.path);
    expect(
        await File('$resolved/settings.hive').readAsString(), 'synthetic data');
  });

  test('fresh installation uses c001apk folder', () async {
    final current = Directory('${temp.path}/c001apk');
    expect(
        await resolveHivePath(current, windows: true), '${current.path}/hive');
  });

  test('other platforms use their package-specific support folder', () async {
    await Directory('${temp.path}/c001apk-flutter/hive')
        .create(recursive: true);
    final current = Directory('${temp.path}/c001apk');
    expect(
        await resolveHivePath(current, windows: false), '${current.path}/hive');
  });
}
