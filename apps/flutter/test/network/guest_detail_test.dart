import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:c001apk_flutter/logic/network/request.dart';
import 'package:c001apk_flutter/logic/state/loading_state.dart';
import 'package:c001apk_flutter/pages/feed/feed_controller.dart';
import 'package:c001apk_flutter/utils/global_data.dart';
import 'package:c001apk_flutter/utils/storage_util.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive/hive.dart';

// Only the external transport is replaced; requests, headers, models and the
// controller all run normally. Content and device data are synthetic.
class GuestDetailAdapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  Map<String, dynamic> apiBody = {
    'code': 403,
    'messageStatus': 'err_request_captcha_v2',
    'message': '需要验证码',
  };
  dynamic webBody = {
    'status': 0,
    'error': 0,
    'messageStatus': 0,
    'data': {
      'id': 123,
      'entityType': 'feed',
      'uid': 10,
      'userInfo': {'uid': 10, 'username': '合成作者'},
      'message': '完整的合成动态正文',
      'message_raw_output': '[]',
      'replynum': 0,
    },
  };

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    requests.add(options);
    final isWeb = options.uri.host == 'www.coolapk.com';
    final body = isWeb
        ? webBody
        : options.uri.path == '/v6/feed/replyList'
            ? {'data': []}
            : apiBody;
    return ResponseBody.fromString(
        body is String ? body : jsonEncode(body), 200,
        headers: {
          Headers.contentTypeHeader: [isWeb ? 'text/html' : 'application/json']
        });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late Directory temp;
  late GuestDetailAdapter adapter;
  setUpAll(() async {
    temp = await Directory.systemTemp.createTemp('c001apk-guest-detail-');
    Hive.init(temp.path);
    GStorage.settings = await Hive.openBox('settings');
    GStorage.blackList = await Hive.openBox('blackList');
    GStorage.favFeed = await Hive.openBox('favFeed');
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
    GlobalData().isLogin = false;
    GlobalData().SESSID = 'SESSID=synthetic-session';
    adapter = GuestDetailAdapter();
    Request.dio.httpClientAdapter = adapter;
  });
  tearDown(() {
    Request().captchaVerifier = null;
    GlobalData().isLogin = false;
    GlobalData().SESSID = '';
  });
  tearDownAll(() async {
    await Hive.close();
    await temp.delete(recursive: true);
  });

  test('guest opens complete feed through public page when API needs captcha',
      () async {
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Success>());
    expect(
        (controller.feedState.value as Success).response.message, '完整的合成动态正文');
    final web = adapter.requests
        .singleWhere((request) => request.uri.host == 'www.coolapk.com');
    expect(web.uri.path, '/feed/123');
    expect(web.method, 'GET');
    expect(web.headers['X-App-Token'], startsWith('v3'));
    expect(web.headers, isNot(contains('Cookie')));
    expect(controller.articleList, isEmpty);
  });

  test('guest article renders all structured text and images', () async {
    final data = adapter.webBody['data'] as Map<String, dynamic>;
    data['title'] = '合成内核文章';
    data['message_raw_output'] = jsonEncode([
      {'type': 'text', 'message': '开头'},
      {'type': 'image', 'url': 'https://image.coolapk.com/synthetic.jpg'},
      {'type': 'text', 'message': '文章末尾'},
    ]);
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Success>());
    expect(controller.articleList!.map((block) => block.type),
        ['title', 'text', 'image', 'text']);
    expect(controller.articleList!.last.message, '文章末尾');
    expect(
        controller.articleImgList, ['https://image.coolapk.com/synthetic.jpg']);
  });

  test('public feed with a summary title keeps its full normal body and photos',
      () async {
    final data = adapter.webBody['data'] as Map<String, dynamic>;
    data['title'] = '分享页摘要标题';
    data['picArr'] = ['https://image.coolapk.com/synthetic-feed.jpg'];
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Success>());
    expect(controller.articleList, isEmpty);
    final feed = (controller.feedState.value as Success).response;
    expect(feed.message, '完整的合成动态正文');
    expect(feed.picArr, ['https://image.coolapk.com/synthetic-feed.jpg']);
  });

  test('successful API detail does not request the public page', () async {
    adapter.apiBody = {'data': adapter.webBody['data']};
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Success>());
    expect(adapter.requests.where((r) => r.uri.host == 'www.coolapk.com'),
        isEmpty);
  });

  test('normal feed without structured article output remains readable',
      () async {
    (adapter.webBody['data'] as Map<String, dynamic>)
        .remove('message_raw_output');
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Success>());
    expect(controller.articleList, isEmpty);
  });

  test('malformed structured article reports an error without leaving loading',
      () async {
    (adapter.webBody['data'] as Map<String, dynamic>)['message_raw_output'] =
        '{broken';
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Error>());
    expect((controller.feedState.value as Error).errMsg, contains('解析'));
  });

  test('removed content is not retried as a public page', () async {
    adapter.apiBody = {'code': 404, 'message': '动态不存在'};
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Error>());
    expect(adapter.requests, hasLength(1));
  });

  test(
      'logged in captcha reads public body without changing login or like state',
      () async {
    GlobalData().isLogin = true;
    GlobalData().uid = 'synthetic-uid';
    GlobalData().token = 'synthetic-token';
    (adapter.webBody['data'] as Map<String, dynamic>)['userAction'] = {
      'like': 1
    };
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Success>());
    final result = controller.feedState.value as Success;
    expect(result.response.message, '完整的合成动态正文');
    expect(result.response.userAction, isNull);
    expect(GlobalData().isLogin, isTrue);
    expect(GlobalData().uid, 'synthetic-uid');
    expect(GlobalData().token, 'synthetic-token');
    final web =
        adapter.requests.singleWhere((r) => r.uri.host == 'www.coolapk.com');
    expect(web.headers, isNot(contains('Cookie')));
  });

  test('guest login requirement can read the official public representation',
      () async {
    adapter.apiBody = {'code': 401, 'messageStatus': 'err_not_login'};
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Success>());
    expect((controller.feedState.value as Success).response.id, 123);
  });

  test('rejected verified detail preserves public body and reports the failure',
      () async {
    GlobalData().isLogin = true;
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    final previous = controller.feedState.value;
    Request().captchaVerifier =
        (challenge) async => challenge.proof('synthetic-validate');
    await controller.getFeedData(verify: true);
    expect(controller.feedState.value, same(previous));
    expect((controller as dynamic).accountDetailNotice.value, contains('仍未'));
    expect(adapter.requests.where((r) => r.uri.host == 'www.coolapk.com'),
        hasLength(1));
  });

  test('cancelled verification keeps public body and explains cancellation',
      () async {
    GlobalData().isLogin = true;
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    final previous = controller.feedState.value;
    Request().captchaVerifier = (_) async => null;
    await controller.getFeedData(verify: true);
    expect(controller.feedState.value, same(previous));
    expect((controller as dynamic).accountDetailNotice.value, contains('取消'));
  });

  test('verified account detail replaces public body and clears the notice',
      () async {
    GlobalData().isLogin = true;
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    Request().captchaVerifier = (challenge) async {
      adapter.apiBody = {
        'data': {
          ...(adapter.webBody['data'] as Map<String, dynamic>),
          'userAction': {'like': 1},
        }
      };
      return challenge.proof('synthetic-validate');
    };
    await controller.getFeedData(verify: true);
    final result = controller.feedState.value as Success;
    expect(result.isPublic, isFalse);
    expect(result.response.userAction.like, 1);
    expect((controller as dynamic).accountDetailNotice.value, isEmpty);
  });

  test('invalid account article keeps previously readable public article',
      () async {
    (adapter.webBody['data'] as Map<String, dynamic>)['message_raw_output'] =
        jsonEncode([
      {'type': 'text', 'message': '公开文章完整正文'}
    ]);
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    final previous = controller.feedState.value;
    adapter.apiBody = {
      'data': {
        ...(adapter.webBody['data'] as Map<String, dynamic>),
        'message_raw_output': '{broken',
      }
    };
    await controller.getFeedData(verify: true);
    expect(controller.feedState.value, same(previous));
    expect(controller.articleList, hasLength(1));
    expect(controller.articleList!.single.message, '公开文章完整正文');
  });

  test('repeated detail retry taps open only one verification request',
      () async {
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    final started = Completer<void>();
    final proof = Completer<String?>();
    var prompts = 0;
    Request().captchaVerifier = (_) {
      prompts++;
      started.complete();
      return proof.future;
    };
    final pending = controller.getFeedData(verify: true);
    await started.future;
    await controller.getFeedData(verify: true);
    expect(prompts, 1);
    expect(adapter.requests.where((r) => r.uri.path == '/v6/feed/detail'),
        hasLength(2));
    proof.complete(null);
    await pending;
    expect((controller as dynamic).isLoadingDetail.value, isFalse);
  });

  test('invalid feed identifier never becomes an external request', () async {
    final controller = FeedController(id: '../account', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Error>());
    expect(adapter.requests, isEmpty);
  });

  test('public HTML landing page remains an error instead of fake content',
      () async {
    adapter.webBody = '<html>请下载客户端</html>';
    final controller = FeedController(id: '123', recordHistory: false);
    await controller.getFeedData();
    expect(controller.feedState.value, isA<Error>());
    expect((controller.feedState.value as Error).errMsg, contains('响应'));
    expect(adapter.requests, hasLength(2));
  });
}
