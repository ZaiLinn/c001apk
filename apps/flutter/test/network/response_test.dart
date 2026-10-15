import 'package:c001apk_flutter/logic/network/network_repo.dart';
import 'package:c001apk_flutter/logic/network/api_failure.dart';
import 'package:c001apk_flutter/logic/state/loading_state.dart';
import 'package:c001apk_flutter/utils/storage_util.dart';
import 'dart:io';
import 'package:hive/hive.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

Response<dynamic> response(dynamic data, {int status = 200}) => Response(
      requestOptions: RequestOptions(path: '/v6/test'),
      statusCode: status,
      data: data,
    );

void main() {
  late Directory temp;
  setUpAll(() async {
    temp = await Directory.systemTemp.createTemp('c001apk-response-test-');
    Hive.init(temp.path);
    GStorage.blackList = await Hive.openBox('blackList');
  });
  tearDownAll(() async {
    await Hive.close();
    await temp.delete(recursive: true);
  });
  test(
      'search listCard containers expose renderable children and keep blocking',
      () async {
    await GStorage.blackList.put(BlackListBoxKey.userBlackList, <String>['99']);
    final state = await NetworkRepo.handleListResponse(response({
      'data': [
        {
          'entityType': 'card',
          'entityTemplate': 'listCard',
          'entities': [
            {'entityType': 'feed', 'id': '1', 'uid': '10', 'message': '合成内容'},
            {'entityType': 'feed', 'id': '2', 'uid': '99'},
            {
              'entityType': 'card',
              'entityTemplate': 'listCard',
              'entities': [
                {'entityType': 'apk', 'id': '3', 'title': '合成应用'}
              ]
            },
          ]
        },
      ]
    }));
    expect(state, isA<Success>());
    expect((state as Success).response.map((item) => item.id).toList(),
        ['1', '3']);
    await GStorage.blackList.clear();
  });
  test('200 with negative status is an error even without message', () async {
    expect(await NetworkRepo.handleDataResponse(response({'status': -1})),
        isA<Error>());
  });
  test('network risk rejection remains distinguishable from business failure',
      () {
    expect(
        () => readApiObject(response({
              'status': -10,
              'messageStatus': -10,
              'message': '检测到您的网络环境可能异常，请关闭网络优化软件后再试',
              'data': null
            })),
        throwsA(isA<ApiFailure>()
            .having((e) => e.message, 'message', contains('网络环境'))));
  });
  test('server upgrade requirement explains that the client was rejected', () {
    expect(
        () => readApiObject(response({
              'status': 500,
              'messageStatus': 'err_request_need_upgrade_new_version',
              'data': null
            })),
        throwsA(isA<ApiFailure>()
            .having((e) => e.message, 'message', contains('客户端'))));
  });
  for (final entry in <String, ApiFailureKind>{
    'err_request_captcha_v2': ApiFailureKind.captcha,
    'err_not_login': ApiFailureKind.authentication,
    'err_rate_limit': ApiFailureKind.rateLimit,
  }.entries) {
    test('business ${entry.key} remains distinguishable', () {
      expect(
          () => readApiObject(
              response({'messageStatus': entry.key, 'data': null})),
          throwsA(isA<ApiFailure>()
              .having((error) => error.kind, 'kind', entry.value)));
    });
  }
  test('successful informational message does not discard valid data',
      () async {
    final state = await NetworkRepo.handleDataResponse(response({
      'status': 1,
      'code': 200,
      'message': 'ok',
      'data': {'id': 1}
    }));
    expect(state, isA<Success>());
  });
  test('missing data envelope is invalid rather than empty', () {
    expect(
        () => readApiObject(response({})),
        throwsA(isA<ApiFailure>().having(
            (error) => error.kind, 'kind', ApiFailureKind.invalidResponse)));
  });
  test('message-only failure with null data is not a valid empty detail',
      () async {
    final state = await NetworkRepo.handleDataResponse(
        response({'message': '请先登录', 'data': null}));
    expect(state, isA<Error>());
    expect((state as Error).errMsg, contains('登录'));
    expect(
        await NetworkRepo.handleListResponse(
            response({'message': '请先登录', 'data': null})),
        isA<Error>());
  });
  test('200 HTML produces a Chinese response error', () async {
    final state = await NetworkRepo.getData(
        () async => response('<html>access denied</html>'));
    expect(state, isA<Error>());
    expect((state as Error).errMsg, contains('响应'));
    expect(state.errMsg, isNot(contains('TypeError')));
  });
  test('malformed detail is an error rather than an uncaught cast', () async {
    final state = await NetworkRepo.getData(() async => response({
          'data': {'title': <String>[]}
        }));
    expect(state, isA<Error>());
    expect((state as Error).errMsg, contains('解析'));
  });
  test('HTTP status remains distinguishable from empty results', () async {
    final state = await NetworkRepo.handleDataResponse(
        response({'data': null}, status: 403));
    expect(state, isA<Error>());
    expect((state as Error).errMsg, contains('403'));
    expect(state.errMsg, contains('请求'));
  });
  test('valid empty list remains empty', () async {
    expect(await NetworkRepo.handleListResponse(response({'data': []})),
        isA<Empty>());
  });
  test('transport errors do not expose request or credentials', () async {
    final state = await NetworkRepo.getData(() async => throw DioException(
          requestOptions: RequestOptions(
            path: 'https://example.invalid/?password=synthetic-secret',
          ),
          type: DioExceptionType.connectionTimeout,
        ));
    expect((state as Error).errMsg, contains('超时'));
    expect(state.errMsg, isNot(contains('synthetic-secret')));
  });
}
