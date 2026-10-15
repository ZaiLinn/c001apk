import 'dart:convert';

import 'package:dio/dio.dart';

enum ApiFailureKind {
  transport,
  http,
  business,
  authentication,
  captcha,
  rateLimit,
  riskControl,
  clientUpgrade,
  invalidResponse,
  parsing,
}

/// Only safe, local messages enter the UI. Never include a response body,
/// request URL, headers or the underlying exception's toString().
class ApiFailure implements Exception {
  const ApiFailure(this.kind, this.message, {this.httpStatus});
  final ApiFailureKind kind;
  final String message;
  final int? httpStatus;

  @override
  String toString() => message;

  static ApiFailure http(int? status) =>
      ApiFailure(ApiFailureKind.http, '请求失败（HTTP ${status ?? "未知"}），请稍后重试',
          httpStatus: status);

  static ApiFailure fromError(Object error) {
    if (error is ApiFailure) return error;
    if (error is DioException) {
      if (error.error is ApiFailure) return error.error as ApiFailure;
      switch (error.type) {
        case DioExceptionType.badResponse:
          return http(error.response?.statusCode);
        case DioExceptionType.connectionTimeout:
        case DioExceptionType.receiveTimeout:
        case DioExceptionType.sendTimeout:
          return const ApiFailure(ApiFailureKind.transport, '请求超时，请稍后重试');
        case DioExceptionType.cancel:
          return const ApiFailure(ApiFailureKind.transport, '请求已取消');
        case DioExceptionType.badCertificate:
          return const ApiFailure(ApiFailureKind.transport, '服务器证书验证失败');
        case DioExceptionType.connectionError:
          return const ApiFailure(ApiFailureKind.transport, '连接失败，请检查网络设置');
        case DioExceptionType.unknown:
          if (error.error is FormatException) {
            return const ApiFailure(
                ApiFailureKind.invalidResponse, '服务器响应格式异常，请稍后重试');
          }
          return const ApiFailure(ApiFailureKind.transport, '请求失败，请检查网络后重试');
      }
    }
    return const ApiFailure(ApiFailureKind.parsing, '数据解析失败，请更新客户端或稍后重试');
  }
}

/// Checks the envelope before generated model casts. An HTTP 200 alone says
/// nothing about login, risk controls or the shape of the payload.
Map<String, dynamic> readApiObject(Response<dynamic> response) {
  final status = response.statusCode;
  if (status == null || status < 200 || status >= 300) {
    throw ApiFailure.http(status);
  }
  dynamic body = response.data;
  if (body is String) {
    try {
      body = jsonDecode(body);
    } on FormatException {
      throw const ApiFailure(ApiFailureKind.invalidResponse, '服务器响应格式异常，请稍后重试');
    }
  }
  if (body is! Map<String, dynamic>) {
    throw const ApiFailure(ApiFailureKind.invalidResponse, '服务器响应格式异常，请稍后重试');
  }
  final marker = '${body['messageStatus'] ?? ''}'.toLowerCase();
  final message = '${body['message'] ?? ''}';
  final clues = '$marker $message ${body['error'] ?? ''}'.toLowerCase();
  final code = int.tryParse('${body['code']}');
  final businessStatus = int.tryParse('${body['status']}');
  final failed = (code != null && ![0, 1, 200].contains(code)) ||
      (businessStatus != null && ![0, 1].contains(businessStatus)) ||
      marker.startsWith('err_') ||
      (body['error'] != null &&
          !['', '0', 'false'].contains('${body['error']}')) ||
      (body['data'] == null &&
          message.isNotEmpty &&
          code == null &&
          businessStatus == null);
  if (failed) {
    if (businessStatus == -10 || clues.contains('网络环境')) {
      throw const ApiFailure(
          ApiFailureKind.riskControl, '服务端检测到网络环境异常，请检查代理、DNS 或网络过滤设置后重试');
    }
    if (marker == 'err_request_need_upgrade_new_version') {
      throw const ApiFailure(
          ApiFailureKind.clientUpgrade, '当前客户端协议未被服务端接受，请更新客户端后重试');
    }
    if (clues.contains('captcha') || clues.contains('验证码')) {
      throw const ApiFailure(ApiFailureKind.captcha, '请求需要验证码验证，请完成验证后重试');
    }
    if (clues.contains('login') ||
        clues.contains('auth') ||
        clues.contains('登录') ||
        code == 401) {
      throw const ApiFailure(
          ApiFailureKind.authentication, '登录状态已失效或需要登录，请重新登录');
    }
    if (clues.contains('rate_limit') || clues.contains('频繁') || code == 429) {
      throw const ApiFailure(ApiFailureKind.rateLimit, '请求过于频繁，请稍后重试');
    }
    throw const ApiFailure(ApiFailureKind.business, '服务器未能完成请求，请稍后重试');
  }
  if (!body.containsKey('data')) {
    throw const ApiFailure(ApiFailureKind.invalidResponse, '服务器响应缺少数据字段，请稍后重试');
  }
  return body;
}
