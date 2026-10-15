// COOLAPK_GUEST_DETAIL_PROBE=1 flutter test test/live/guest_detail_test.dart
// Explicit guest GETs only. Save structural evidence, never response bodies.
import 'dart:convert';
import 'dart:io';

import 'package:c001apk_flutter/constants/constants.dart';
import 'package:c001apk_flutter/logic/network/request.dart';
import 'package:c001apk_flutter/logic/state/loading_state.dart';
import 'package:c001apk_flutter/pages/feed/feed_controller.dart';
import 'package:c001apk_flutter/utils/global_data.dart';
import 'package:c001apk_flutter/utils/storage_util.dart';
import 'package:c001apk_flutter/utils/token_util.dart';
import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive/hive.dart';

import 'api_probe_test.dart' show ProbeInterceptor, findEntity;

void main() {
  test('RMX3031 guest feed and article load complete detail and replies',
      () async {
    final temp = await Directory.systemTemp.createTemp('guest-detail-live-');
    Hive.init(temp.path);
    GStorage.settings = await Hive.openBox('settings');
    GStorage.blackList = await Hive.openBox('blackList');
    GStorage.favFeed = await Hive.openBox('favFeed');
    GlobalData().isLogin = false;
    GlobalData().SESSID = '';
    await GStorage.settings.putAll({
      SettingsBoxKey.versionName: Constants.VERSION_NAME,
      SettingsBoxKey.versionCode: Constants.VERSION_CODE,
      SettingsBoxKey.apiVersion: Constants.API_VERSION,
      SettingsBoxKey.sdkInt: '33',
      SettingsBoxKey.xAppDevice: await TokenUtils.encodeDevice(
          '00000000000000000000000000000000; ; ; 02:00:00:00:00:01; Google; google; Pixel 7; Build/TQ3A.230805.001; null'),
      SettingsBoxKey.userAgent:
          'Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230805.001) +CoolMarket/16.2.0-2604201-universal',
    });
    Request();
    Request.dio.interceptors.clear();
    Request.dio.interceptors.add(ProbeInterceptor());
    Request.dio.options.connectTimeout = const Duration(seconds: 20);
    Request.dio.options.receiveTimeout = const Duration(seconds: 20);
    Request.dio.httpClientAdapter = IOHttpClientAdapter(
        createHttpClient: () => HttpClient()
          ..findProxy = (uri) => HttpClient.findProxyFromEnvironment(uri));
    final requests = <Map<String, dynamic>>[];
    Request.dio.interceptors.add(InterceptorsWrapper(onResponse: (r, handler) {
      dynamic body = r.data;
      if (body is String) {
        try {
          body = jsonDecode(body);
        } on FormatException {
          body = null;
        }
      }
      requests.add({
        'host': r.requestOptions.uri.host,
        'path': r.requestOptions.uri.path,
        'method': r.requestOptions.method,
        'httpStatus': r.statusCode,
        'contentType': r.headers.value(Headers.contentTypeHeader),
        'json': body is Map,
        'messageStatus': body is Map ? body['messageStatus'] : null,
        'cookieAttached': r.requestOptions.headers.keys.any((key) =>
            key.toLowerCase() == 'cookie' &&
            '${r.requestOptions.headers[key]}'.isNotEmpty),
      });
      handler.next(r);
    }));
    final results = <Map<String, dynamic>>[];
    try {
      final search = await Request()
          .get('https://api.coolapk.com/v6/search', queryParameters: {
        'type': 'feed',
        'feedType': 'all',
        'sort': 'dateline',
        'searchValue': 'RMX3031',
        'isStrict': 0,
        'page': 1,
        'showAnonymous': -1,
      });
      expect(findEntity(search.data, 'feed'), isNotNull);
      for (final id in ['74194711', '73760194']) {
        final controller = FeedController(id: id, recordHistory: false);
        await controller.getFeedData();
        expect(controller.feedState.value, isA<Success>());
        final data = (controller.feedState.value as Success).response;
        expect('${data.id}', id);
        expect(data.message, isNotEmpty);
        if (id == '74194711') {
          expect(data.message, contains('cos17'));
          expect(controller.articleList, isEmpty);
          expect(data.picArr, isNotEmpty);
        } else {
          expect(data.title, contains('6.6 ACK'));
          expect(controller.articleList!.length, greaterThan(2));
          expect(controller.articleList!.last.type, 'text');
          expect(controller.articleList!.last.message, isNotEmpty);
        }
        await (() async {
          while (controller.isLoading) {
            await Future<void>.delayed(const Duration(milliseconds: 20));
          }
        })()
            .timeout(const Duration(seconds: 25));
        expect(
            controller.loadingState.value, anyOf(isA<Success>(), isA<Empty>()));
        results.add({
          'id': id,
          'detail': 'success',
          'messageLength': data.message.length,
          'articleBlocks': controller.articleList?.length ?? 0,
          'articleImages': controller.articleImgList?.length ?? 0,
          'replies':
              controller.loadingState.value is Success ? 'success' : 'empty',
        });
      }
    } finally {
      Directory('.local/reports').createSync(recursive: true);
      File('.local/reports/guest-detail-probe-results.json')
          .writeAsStringSync('${const JsonEncoder.withIndent('  ').convert({
            'checkedAtUtc': DateTime.now().toUtc().toIso8601String(),
            'guest': true,
            'syntheticDevice': true,
            'requests': requests,
            'results': results,
          })}\n');
      Request.dio.close(force: true);
      await Hive.close();
      await temp.delete(recursive: true);
    }
  },
      skip: Platform.environment['COOLAPK_GUEST_DETAIL_PROBE'] != '1',
      timeout: const Timeout(Duration(minutes: 2)));
}
