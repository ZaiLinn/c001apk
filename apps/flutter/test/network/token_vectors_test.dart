import 'dart:convert';
import 'dart:io';

import 'package:c001apk_flutter/utils/token_util.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('V3 matches independent Node bcryptjs fixed-time vectors', () {
    final vectors =
        jsonDecode(File('test/fixtures/token_v3.json').readAsStringSync())
            as List;
    for (final vector in vectors) {
      expect(
          TokenUtils.getTokenV3(vector['device'],
              timestamp: vector['timestamp']),
          vector['token']);
    }
  });
  test('signature follows time and app code changes', () {
    final token =
        TokenUtils.getTokenV3('synthetic-test-device', timestamp: 1770000000);
    expect(
        TokenUtils.getTokenV3('synthetic-test-device', timestamp: 1770000001),
        isNot(token));
    expect(
        TokenUtils.getTokenV3('synthetic-test-device',
            timestamp: 1770000000, appCode: 2604202),
        isNot(token));
  });
}
