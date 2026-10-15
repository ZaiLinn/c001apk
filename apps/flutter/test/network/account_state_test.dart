import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:c001apk_flutter/logic/network/request.dart';
import 'package:c001apk_flutter/pages/main/main_controller.dart';
import 'package:c001apk_flutter/pages/message/message_controller.dart';
import 'package:c001apk_flutter/utils/global_data.dart';
import 'package:c001apk_flutter/utils/storage_util.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive/hive.dart';

class AccountAdapter implements HttpClientAdapter {
  dynamic body = {
    'data': {'uid': '123', 'username': '合成用户'}
  };
  bool offline = false;
  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? stream,
      Future<void>? cancelFuture) async {
    if (offline) {
      throw DioException(
          requestOptions: options, type: DioExceptionType.connectionError);
    }
    return ResponseBody.fromString(jsonEncode(body), 200, headers: {
      Headers.contentTypeHeader: ['application/json']
    });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late Directory temp;
  late AccountAdapter adapter;
  setUpAll(() async {
    temp = await Directory.systemTemp.createTemp('c001apk-account-test-');
    Hive.init(temp.path);
    GStorage.settings = await Hive.openBox('settings');
    Request();
    // These tests inspect controller errors without presenting transient UI.
    Request.dio.interceptors.clear();
  });
  setUp(() async {
    await GStorage.settings.clear();
    GStorage.setUid('123');
    GStorage.setUsername('saved-user');
    GStorage.setToken('synthetic-saved-token');
    GStorage.setIsLogin(true);
    adapter = AccountAdapter();
    Request.dio.httpClientAdapter = adapter;
  });
  tearDownAll(() async {
    await Hive.close();
    await temp.delete(recursive: true);
  });
  test('sparse login check preserves existing token and session', () async {
    await MainController().checkLoginInfo();
    expect(GlobalData().token, 'synthetic-saved-token');
    expect(GStorage.token, 'synthetic-saved-token');
    expect(GlobalData().isLogin, isTrue);
  });
  test('offline login check completes without an unhandled future', () async {
    adapter.offline = true;
    await MainController().checkLoginInfo();
    expect(GlobalData().token, 'synthetic-saved-token');
    expect(GlobalData().isLogin, isTrue);
  });
  test('captcha profile preserves saved account and cached profile', () async {
    adapter.body = {
      'code': 403,
      'messageStatus': 'err_request_captcha_v2',
      'data': null
    };
    GStorage.setUserAvatar('https://example.invalid/saved.jpg');
    final controller = MessageController();
    await controller.getProfile();
    expect(GStorage.username, 'saved-user');
    expect(GStorage.userAvatar, 'https://example.invalid/saved.jpg');
    expect(GlobalData().isLogin, isTrue);
  });
}
