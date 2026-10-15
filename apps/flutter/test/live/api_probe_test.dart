// Explicit opt-in: COOLAPK_LIVE_PROBE=1 flutter test test/live/api_probe_test.dart
// Guest GETs only. No response content, account cookies or signatures are saved.
import 'dart:convert';
import 'dart:io';

import 'package:c001apk_flutter/constants/constants.dart';
import 'package:c001apk_flutter/logic/network/api_failure.dart';
import 'package:c001apk_flutter/logic/network/interceptor.dart';
import 'package:c001apk_flutter/logic/network/network_repo.dart';
import 'package:c001apk_flutter/logic/network/request.dart';
import 'package:c001apk_flutter/logic/state/loading_state.dart';
import 'package:c001apk_flutter/utils/storage_util.dart';
import 'package:c001apk_flutter/utils/token_util.dart';
import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive/hive.dart';

class ProbeInterceptor extends ApiInterceptor {
  @override
  void onError(DioException err, ErrorInterceptorHandler handler) =>
      handler.next(err);
}

Map<String, dynamic>? findEntity(dynamic value, String type) {
  if (value is Map<String, dynamic>) {
    if (value['entityType'] == type && value['id'] != null) return value;
    for (final child in value.values) {
      final found = findEntity(child, type);
      if (found != null) return found;
    }
  } else if (value is List) {
    for (final child in value) {
      final found = findEntity(child, type);
      if (found != null) return found;
    }
  }
  return null;
}

void main() {
  test('guest read-only API and model verification', () async {
    final temp = await Directory.systemTemp.createTemp('c001apk-live-probe-');
    Hive.init(temp.path);
    GStorage.settings = await Hive.openBox('settings');
    GStorage.blackList = await Hive.openBox('blackList');
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
    Request.dio.httpClientAdapter = IOHttpClientAdapter(createHttpClient: () {
      return HttpClient()
        ..findProxy = (uri) => HttpClient.findProxyFromEnvironment(uri);
    });
    final results = <Map<String, dynamic>>[];
    Future<dynamic> probe(String name, String path, Map<String, dynamic> params,
        {bool list = true, String host = Constants.URL_API_SERVICE}) async {
      final row = <String, dynamic>{
        'name': name,
        'path': path,
        'host': Uri.parse(host).host,
        'method': 'GET',
        'guest': true
      };
      results.add(row);
      try {
        final response =
            await Request().get('$host$path', queryParameters: params);
        row['httpStatus'] = response.statusCode;
        row['contentType'] = response.headers.value(Headers.contentTypeHeader);
        final object = readApiObject(response);
        final state = list
            ? await NetworkRepo.handleListResponse(response)
            : await NetworkRepo.handleDataResponse(response);
        row['result'] = state is Success
            ? 'success'
            : state is Empty
                ? 'empty'
                : 'model_error';
        if (object['data'] is List) {
          row['rawCount'] = (object['data'] as List).length;
          row['entityTypes'] = (object['data'] as List)
              .whereType<Map<String, dynamic>>()
              .map((item) => item['entityType'])
              .toSet()
              .toList();
          row['templates'] = (object['data'] as List)
              .whereType<Map<String, dynamic>>()
              .map((item) => item['entityTemplate'])
              .toSet()
              .toList();
          if (state is Success) {
            row['visibleCount'] = (state.response as List).length;
            if ((object['data'] as List).isNotEmpty &&
                (state.response as List).isEmpty) {
              row['result'] = 'all_filtered';
            }
          }
        }
        if (state is Error) row['failureKind'] = 'parsing_or_filtering';
        return object['data'];
      } catch (error) {
        final failure = ApiFailure.fromError(error);
        row['result'] = 'error';
        row['failureKind'] = failure.kind.name;
        row['httpStatus'] ??= failure.httpStatus;
        return null;
      }
    }

    try {
      final home = await probe(
          'home',
          '/v6/main/indexV8',
          {
            'page': 1,
            'firstLaunch': 1,
            'installTime': '1791921600000',
            'ids': ''
          },
          host: Constants.URL_API2_SERVICE);
      final feed = findEntity(home, 'feed');
      if (feed != null) {
        await probe('feed-detail', '/v6/feed/detail', {'id': feed['id']},
            list: false);
        await probe('feed-replies', '/v6/feed/replyList', {
          'id': feed['id'],
          'page': 1,
          'listType': 'lastupdate_desc',
          'discussMode': 0,
          'feedType': 'feed',
          'blockStatus': 0,
          'fromFeedAuthor': 0
        });
        if (feed['uid'] != null) {
          await probe('user-space', '/v6/user/space', {'uid': feed['uid']},
              list: false);
          await probe('user-feeds', '/v6/user/feedList', {
            'uid': feed['uid'],
            'page': 1,
            'showAnonymous': 0,
            'isIncludeTop': 1,
            'showDoing': 0
          });
        }
      }
      await probe('search', '/v6/search', {
        'type': 'all',
        'feedType': '',
        'sort': '',
        'searchValue': '酷安',
        'isStrict': 0,
        'page': 1,
        'showAnonymous': -1
      });
      await probe('app-detail', '/v6/apk/detail',
          {'id': 'com.coolapk.market', 'installed': 1},
          list: false);
      await probe('topic-page', '/v6/page/dataList',
          {'url': '#/topic/tagList', 'title': '话题', 'subTitle': '', 'page': 1});
      await probe('topic-directory', '/v6/page/dataList',
          {'url': 'V11_VERTICAL_TOPIC', 'title': '话题', 'page': 1});
      await probe('product-categories', '/v6/product/categoryList', {});
      await probe('topic-detail', '/v6/topic/newTagDetail', {'tag': '酷安'},
          list: false);
      await probe('pictures', '/v6/picture/list',
          {'tag': '壁纸', 'type': 'recommend', 'page': 1});
      for (final type in [
        'feed',
        'apk',
        'game',
        'user',
        'feedTopic',
        'product'
      ]) {
        await probe('search-$type', '/v6/search', {
          'type': type,
          'feedType': 'all',
          'sort': 'default',
          'searchValue': '酷安',
          'isStrict': 0,
          'page': 1,
          'showAnonymous': -1,
        });
      }
      final report = {
        'checkedAtUtc': DateTime.now().toUtc().toIso8601String(),
        'protocol': {
          'version': Constants.VERSION_NAME,
          'code': Constants.VERSION_CODE,
          'api': Constants.API_VERSION,
          'signature': 'V3/cost4'
        },
        'results': results
      };
      Directory('.local/reports').createSync(recursive: true);
      File('.local/reports/api-probe-results.json').writeAsStringSync(
          '${const JsonEncoder.withIndent('  ').convert(report)}\n');
      for (final result in results) {
        // ignore: avoid_print
        print(jsonEncode(result));
      }
      expect(
          results.where((row) => !['success', 'empty'].contains(row['result'])),
          isEmpty,
          reason:
              'See .local/reports/api-probe-results.json for safe structured results');
    } finally {
      Request.dio.close(force: true);
      await Hive.close();
      await temp.delete(recursive: true);
    }
  },
      skip: Platform.environment['COOLAPK_LIVE_PROBE'] != '1',
      timeout: const Timeout(Duration(minutes: 4)));
}
