import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:c001apk_flutter/logic/network/network_repo.dart';
import 'package:c001apk_flutter/logic/network/request.dart';
import 'package:c001apk_flutter/utils/storage_util.dart';
import 'package:c001apk_flutter/utils/global_data.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive/hive.dart';

class RecordingAdapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  final bodies = <String>[];
  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    requests.add(options);
    bodies.add(requestStream == null
        ? ''
        : utf8.decode(await requestStream.expand((chunk) => chunk).toList()));
    return ResponseBody.fromString('{"data":[]}', 200, headers: {
      Headers.contentTypeHeader: ['application/json']
    });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late Directory directory;
  late RecordingAdapter adapter;
  setUpAll(() async {
    directory = await Directory.systemTemp.createTemp('c001apk-request-test-');
    Hive.init(directory.path);
    GStorage.settings = await Hive.openBox('settings');
    await GStorage.settings.putAll({
      SettingsBoxKey.xAppDevice: 'synthetic-test-device',
      SettingsBoxKey.versionCode: '2604201',
      SettingsBoxKey.versionName: '16.2.0',
      SettingsBoxKey.apiVersion: '16',
      SettingsBoxKey.sdkInt: '33',
      SettingsBoxKey.userAgent: 'SyntheticTest/1.0',
    });
    Request();
  });
  setUp(() {
    adapter = RecordingAdapter();
    Request.dio.httpClientAdapter = adapter;
  });
  tearDownAll(() async {
    await Hive.close();
    await directory.delete(recursive: true);
  });
  test('concurrent account and API requests select their own headers',
      () async {
    await Future.wait([
      NetworkRepo.getLoginParam('/auth/login?type=mobile'),
      Request().get('https://api.coolapk.com/v6/main/indexV8'),
    ]);
    final account = adapter.requests
        .singleWhere((r) => r.uri.host == 'account.coolapk.com');
    final api =
        adapter.requests.singleWhere((r) => r.uri.host == 'api.coolapk.com');
    expect(account.headers, isNot(contains('X-App-Token')));
    expect(account.headers['Accept'], contains('text/html'));
    expect(api.headers['X-App-Token'], startsWith('v3'));
  });
  test('password login sends URL encoded fields with matching content type',
      () async {
    await NetworkRepo.onLogin(
        'synthetic-hash', 'test+account', 'test&password', '');
    final request = adapter.requests.single;
    expect(request.contentType, Headers.formUrlEncodedContentType);
    expect(Uri.splitQueryString(adapter.bodies.single)['password'],
        'test&password');
    expect(
        Uri.splitQueryString(adapter.bodies.single)['login'], 'test+account');
  });
  test('external download request receives no API credentials', () async {
    await Request().get('https://example.invalid/image');
    expect(adapter.requests.single.headers, isNot(contains('X-App-Token')));
    expect(adapter.requests.single.headers, isNot(contains('Cookie')));
  });
  test('API captcha uses valid dimensions in the query', () async {
    await NetworkRepo.getValidateCaptcha();
    final query = adapter.requests.single.uri.queryParameters;
    expect(query['w'], '270');
    expect(query['h'], '113');
  });

  test('feed likes send trace in an encoded form and request count-only detail',
      () async {
    await NetworkRepo.postLikeDeleteFollow('/v6/feed/like', id: '123');
    final request = adapter.requests.single;
    expect(request.method, 'POST');
    expect(request.uri.queryParameters, {'id': '123', 'detail': '0'});
    expect(request.contentType, Headers.formUrlEncodedContentType);
    expect(Uri.splitQueryString(adapter.bodies.single), {'trace': ''});
  });

  test(
      'feed replies encode text and target parameters without a multipart body',
      () async {
    await NetworkRepo.postReply(
        FormData.fromMap({'message': '合成评论 + &', 'replyAndForward': 0}),
        '123',
        'feed');
    final request = adapter.requests.single;
    expect(request.method, 'POST');
    expect(request.uri.queryParameters, {'id': '123', 'type': 'feed'});
    expect(request.contentType, Headers.formUrlEncodedContentType);
    expect(Uri.splitQueryString(adapter.bodies.single)['message'], '合成评论 + &');
  });

  test(
      'private feed publishing uses publish_status without marking the feed as a draft',
      () async {
    await NetworkRepo.postCreateFeed(
        FormData.fromMap({'type': 'feed', 'message': '合成私密内容', 'status': -1}));
    expect(
        adapter.requests.single.contentType, Headers.formUrlEncodedContentType);
    final body = Uri.splitQueryString(adapter.bodies.single);
    expect(body['status'], '1');
    expect(body['publish_status'], '1');
    expect(body['message'], '合成私密内容');
  });

  test('public feed headers omit cookies even when an account is present',
      () async {
    GlobalData().isLogin = true;
    GlobalData().SESSID = 'SESSID=synthetic';
    try {
      await Request().get('https://www.coolapk.com/feed/123',
          options: Options(headers: {'Cookie': 'synthetic-cookie'}));
      final request = adapter.requests.single;
      expect(request.headers['X-App-Token'], startsWith('v3'));
      expect(request.headers, isNot(contains('Cookie')));
      expect(request.responseType, ResponseType.plain);
    } finally {
      GlobalData().isLogin = false;
      GlobalData().SESSID = '';
    }
  });

  test('public feed signatures are restricted to official HTTPS numeric GETs',
      () async {
    await Request().get('https://www.coolapk.com/account');
    await Request().get('https://www.coolapk.com/feed/not-an-id');
    await Request().get('http://www.coolapk.com/feed/123');
    await Request().get('https://www.coolapk.com.evil.invalid/feed/123');
    await Request().post('https://www.coolapk.com/feed/123');
    for (final request in adapter.requests) {
      expect(request.headers, isNot(contains('X-App-Token')));
      expect(request.headers, isNot(contains('Cookie')));
    }
  });
}
