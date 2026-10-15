import 'dart:io';

import 'package:c001apk_flutter/utils/cache_util.dart';
import 'package:flutter/services.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path_provider/path_provider.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory root;
  late Directory temp;
  late Directory documents;
  late Directory support;
  late File unrelated;
  late File settings;
  late File unregistered;
  late String cachedPath;
  late DefaultCacheManager manager;

  setUpAll(() async {
    root = await Directory.systemTemp.createTemp('c001apk-cache-safety-');
    temp = await Directory('${root.path}/temp').create();
    documents = await Directory('${root.path}/documents').create();
    support = await Directory('${root.path}/support').create();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
            const MethodChannel('plugins.flutter.io/path_provider'),
            (call) async => switch (call.method) {
                  'getTemporaryDirectory' => temp.path,
                  'getApplicationDocumentsDirectory' => documents.path,
                  'getApplicationSupportDirectory' => support.path,
                  _ => null,
                });
    // Prove that destructive regression checks cannot reach the real temp dir.
    expect((await getTemporaryDirectory()).path, temp.path);
    unrelated = await File('${temp.path}/other-app.txt').writeAsString('keep');
    settings =
        await File('${documents.path}/settings.hive').writeAsString('keep');
    manager = DefaultCacheManager();
    cachedPath = (await manager.putFile('https://synthetic.invalid/image.png',
            Uint8List.fromList([1, 2, 3]),
            fileExtension: 'png'))
        .path;
    await manager.getFileFromCache('https://synthetic.invalid/image.png');
    unregistered =
        await File('${File(cachedPath).parent.path}/unregistered.png')
            .writeAsString('keep');
  });

  tearDownAll(() async {
    await manager.dispose();
    await root.delete(recursive: true);
  });

  test('cache size excludes shared temp files and application settings',
      () async {
    expect(await CacheManage().loadApplicationCache(), '3.00B');
  });

  test('clearing registered image cache preserves unrelated files and settings',
      () async {
    expect(await CacheManage().clearCacheAll(), isTrue);
    expect(await File(cachedPath).exists(), isFalse);
    expect(await unrelated.readAsString(), 'keep');
    expect(await settings.readAsString(), 'keep');
    expect(await unregistered.readAsString(), 'keep');
    expect(
        await manager.getFileFromCache('https://synthetic.invalid/image.png'),
        isNull);
    expect(await CacheManage().loadApplicationCache(), '0.00B');
  });
}
