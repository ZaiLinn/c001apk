import 'dart:convert';
import 'dart:io';

import 'package:c001apk_flutter/logic/network/request.dart';
import 'package:c001apk_flutter/logic/accounts/account_store.dart';
import 'package:c001apk_flutter/pages/message/message_controller.dart';
import 'package:c001apk_flutter/constants/constants.dart';
import 'package:c001apk_flutter/main.dart';
import 'package:c001apk_flutter/components/settings/drop_down_menu_item.dart';
import 'package:c001apk_flutter/utils/global_data.dart';
import 'package:c001apk_flutter/utils/storage_util.dart';
import 'package:dio/dio.dart';
import 'package:dynamic_color/samples.dart';
import 'package:dynamic_color/test_utils.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:get/get.dart';
import 'package:hive/hive.dart';

class EmptyGuestAdapter implements HttpClientAdapter {
  bool showFeed = false;
  bool accountDetail = false;
  static const feed = {
    'id': 123,
    'entityType': 'feed',
    'feedTypeName': '动态',
    'uid': 10,
    'dateline': 1760000000,
    'userInfo': {'uid': 10, 'username': '合成作者'},
    'message': '可正常阅读的合成正文',
    'message_raw_output': '[]',
    'likenum': 0,
    'replynum': 0,
  };
  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    if (showFeed &&
        (options.uri.path == '/v6/feed/detail' ||
            options.uri.path == '/feed/123')) {
      return ResponseBody.fromString(
        jsonEncode(options.uri.path == '/feed/123' || accountDetail
            ? {
                'data': {
                  ...feed,
                  if (accountDetail) 'userAction': {'like': 1},
                }
              }
            : {'code': 403, 'messageStatus': 'err_request_captcha_v2'}),
        200,
        headers: {
          Headers.contentTypeHeader: ['application/json']
        },
      );
    }
    return ResponseBody.fromString(
        options.uri.path == '/v6/account/checkLoginInfo'
            ? '{"data":null}'
            : '{"data":[]}',
        200,
        headers: {
          Headers.contentTypeHeader: ['application/json']
        });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory temp;

  setUpAll(() async {
    temp = await Directory.systemTemp.createTemp('c001apk-locale-');
    Hive.init(temp.path);
    GStorage.settings = await Hive.openBox('settings');
    GStorage.blackList = await Hive.openBox('blackList');
    GStorage.favFeed = await Hive.openBox('favFeed');
    GStorage.historyFeed = await Hive.openBox('historyFeed');
    GStorage.searchHistory = await Hive.openBox('searchHistory');
    await GStorage.settings.putAll({
      SettingsBoxKey.xAppDevice: 'synthetic-test-device',
      SettingsBoxKey.userAgent: 'SyntheticTest/1.0',
      SettingsBoxKey.versionName: '16.2.0',
      SettingsBoxKey.versionCode: '2604201',
      SettingsBoxKey.apiVersion: '16',
      SettingsBoxKey.sdkInt: '33',
    });
    GlobalData().isLogin = false;
    Request();
    Request.dio.httpClientAdapter = EmptyGuestAdapter();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
            const MethodChannel('dev.fluttercommunity.plus/package_info'),
            (_) async => {
                  'appName': 'c001apk',
                  'packageName': 'synthetic.test',
                  'version': '1.0.0',
                  'buildNumber': '1',
                });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
            const MethodChannel('plugins.flutter.io/path_provider'),
            (_) async => temp.path);
  });

  tearDown(() async {
    Get.reset();
  });
  tearDownAll(() async {
    await Hive.close();
    await temp.delete(recursive: true);
  });

  testWidgets('real app uses Simplified Chinese system controls and navigation',
      (tester) async {
    tester.view.physicalSize = const Size(500, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const C001APKAPP());
    await tester.pump(const Duration(milliseconds: 500));
    final context = tester.element(find.byType(NavigationBar));
    expect(Localizations.localeOf(context), const Locale('zh', 'CN'));
    final material = MaterialLocalizations.of(context);
    expect(material.backButtonTooltip, '返回');
    expect(material.copyButtonLabel, '复制');
    expect(material.pasteButtonLabel, '粘贴');
    expect(find.text('主页'), findsOneWidget);
    expect(
        tester
            .widget<NavigationBar>(find.byType(NavigationBar))
            .destinations
            .cast<NavigationDestination>()
            .map((item) => item.label),
        ['主页', '关注', '我的']);
    expect(find.widgetWithText(Tab, '关注'), findsNothing);
    await tester.tap(find.widgetWithText(NavigationDestination, '关注'));
    await tester.pumpAndSettle();
    expect(find.text('本地关注动态'), findsOneWidget);
    expect(find.textContaining('还没有本地关注的用户'), findsOneWidget);
    expect(find.text('Home'), findsNothing);
    tester.view.physicalSize = const Size(1400, 900);
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.byType(NavigationRail), findsOneWidget);
    expect(find.text('主页'), findsOneWidget);
    expect(
        tester
            .widget<NavigationRail>(find.byType(NavigationRail))
            .selectedIndex,
        1);
    expect(find.text('本地关注动态'), findsOneWidget);
    await tester.tap(find.descendant(
        of: find.byType(NavigationRail),
        matching: find.byIcon(Icons.person_outline)));
    await tester.pumpAndSettle();
    expect(
        tester
            .widget<NavigationRail>(find.byType(NavigationRail))
            .selectedIndex,
        2);
    expect(find.text('点击登录'), findsOneWidget);
    expect(find.text('粉丝', skipOffstage: false), findsNothing);
    final localCard = find
        .ancestor(of: find.text('本地收藏'), matching: find.byType(Material))
        .last;
    final divider = find.byType(Divider).first;
    expect(tester.getTopLeft(localCard).dy - tester.getBottomLeft(divider).dy,
        lessThan(20));
    expect(find.text('Settings'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('system dynamic palette reaches the actual app theme',
      (tester) async {
    DynamicColorTestingUtils.setMockDynamicColors(
        corePalette: SampleCorePalettes.green);
    await tester.runAsync(() => GStorage.settings.putAll({
          SettingsBoxKey.useMaterial: true,
          SettingsBoxKey.selectedTheme: 1,
        }));
    await tester.pumpWidget(const C001APKAPP());
    await tester.pump(const Duration(milliseconds: 500));
    final context = tester.element(find.byType(Scaffold).first);
    await tester.pump(const Duration(milliseconds: 300));
    expect(Theme.of(context).colorScheme.primary, const Color(0xff286c2a));
    await tester.runAsync(
        () => GStorage.settings.put(SettingsBoxKey.useMaterial, false));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(Theme.of(context).colorScheme.primary, const Color(0xff64558f));
    await tester.runAsync(
        () => GStorage.settings.put(SettingsBoxKey.useMaterial, true));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(Theme.of(context).colorScheme.primary, const Color(0xff286c2a));
    await tester
        .runAsync(() => GStorage.settings.put(SettingsBoxKey.selectedTheme, 2));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(Theme.of(context).brightness, Brightness.dark);
    await tester.pumpWidget(const SizedBox.shrink());
    DynamicColorTestingUtils.setMockDynamicColors();
  });

  testWidgets(
      'dynamic color disables the entire color picker and restores its selection',
      (tester) async {
    tester.view.physicalSize = const Size(500, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.runAsync(() => GStorage.settings.putAll({
          SettingsBoxKey.useMaterial: true,
          SettingsBoxKey.staticColor: 4,
        }));
    await tester.pumpWidget(const C001APKAPP());
    await tester.pump(const Duration(milliseconds: 500));
    await tester.tap(find.byTooltip('设置'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('外观与字号'));
    await tester.pumpAndSettle();
    final picker = find.byWidgetPredicate((widget) =>
        widget is DropDownMenuItem &&
        widget.boxKey == SettingsBoxKey.staticColor);
    final button =
        find.descendant(of: picker, matching: find.byType(DropdownButton<int>));
    final tile = find.descendant(of: picker, matching: find.byType(ListTile));
    expect(find.text('主题颜色'), findsOneWidget);
    expect(tester.widget<DropdownButton<int>>(button).onChanged, isNull);
    expect(tester.widget<ListTile>(tile).enabled, isFalse);
    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(find.text(Constants.themeType[0]), findsNothing);
    expect(GStorage.staticColor, 4);

    await tester.runAsync(
        () => GStorage.settings.put(SettingsBoxKey.useMaterial, false));
    await tester.pumpAndSettle();
    expect(tester.widget<DropdownButton<int>>(button).onChanged, isNotNull);
    expect(tester.widget<ListTile>(tile).enabled, isTrue);
    expect(tester.widget<DropdownButton<int>>(button).value, 4);
    await tester.tap(button);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text(Constants.themeType[0]), -120,
        scrollable: find.byType(Scrollable).last);
    expect(find.text(Constants.themeType[0]), findsOneWidget);
    expect(
        tester
            .widget<DropdownButton<int>>(button)
            .items!
            .every((item) => item.enabled),
        isTrue);
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    expect(GStorage.staticColor, 4);

    await tester.runAsync(
        () => GStorage.settings.put(SettingsBoxKey.useMaterial, true));
    await tester.pumpAndSettle();
    expect(tester.widget<DropdownButton<int>>(button).onChanged, isNull);
    expect(tester.widget<DropdownButton<int>>(button).value, 4);
    await tester.pumpWidget(const SizedBox.shrink());
    // Let the settings page's cache manager finish its scheduled cleanup.
    await tester.pump(const Duration(seconds: 11));
  });

  testWidgets(
      'public detail explains account status on demand and clears it on success',
      (tester) async {
    final adapter = EmptyGuestAdapter()..showFeed = true;
    Request.dio.httpClientAdapter = adapter;
    GlobalData().isLogin = true;
    await tester.runAsync(
        () => GStorage.settings.put(SettingsBoxKey.recordHistory, false));
    await tester.pumpWidget(const C001APKAPP());
    await tester.pump(const Duration(milliseconds: 500));
    Get.toNamed('/feed/123');
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.text('可正常阅读的合成正文', findRichText: true), findsOneWidget);
    expect(find.textContaining('正在显示公开正文'), findsNothing);
    expect(find.byTooltip('账号信息暂不可用'), findsOneWidget);
    await tester.tap(find.byTooltip('账号信息暂不可用'));
    await tester.pumpAndSettle();
    expect(find.textContaining('点赞状态暂不可用'), findsOneWidget);
    adapter.accountDetail = true;
    await tester.tap(find.text('重试账号详情'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.byTooltip('账号信息暂不可用'), findsNothing);
    expect(find.text('可正常阅读的合成正文', findRichText: true), findsOneWidget);
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpWidget(const SizedBox.shrink());
    GlobalData().isLogin = false;
  });

  testWidgets('switching accounts rebuilds mounted pages and closes old routes',
      (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.runAsync(() => AccountStore().saveLogin(
        uid: '321',
        username: 'synthetic-current',
        token: 'synthetic-current-token'));
    await tester.pumpWidget(const C001APKAPP());
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(NavigationDestination, '关注'));
    await tester.pumpAndSettle();
    expect(find.text('账号关注'), findsOneWidget);
    await tester.tap(find.text('悄悄关注'));
    await tester.pumpAndSettle();
    expect(find.text('悄悄关注动态'), findsOneWidget);
    await tester.tap(find.widgetWithText(NavigationDestination, '我的'));
    await tester.pumpAndSettle();
    final oldController = Get.find<MessageController>();
    oldController.firstList.value = [7, 8, 9];
    await tester.pump();
    expect(find.text('粉丝'), findsOneWidget);
    expect(find.text('7'), findsOneWidget);
    expect(find.byTooltip('账号管理'), findsOneWidget);
    await tester.tap(find.byTooltip('账号管理'));
    await tester.pumpAndSettle();
    expect(Get.currentRoute, '/accounts');
    await tester.runAsync(() async {
      await tester.tap(find.text('使用游客模式'));
      await GStorage.settings.flush();
      await Future<void>.delayed(const Duration(milliseconds: 30));
    });
    await tester.pumpAndSettle();
    expect(Get.currentRoute, '/');
    expect(GlobalData().isLogin, isFalse);
    expect(oldController.isClosed, isTrue);
    expect(identical(oldController, Get.find<MessageController>()), isFalse);
    expect(find.text('点击登录'), findsOneWidget);
    await tester.runAsync(() => AccountStore().switchTo('321'));
    await tester.pumpAndSettle();
    expect(GlobalData().uid, '321');
    expect(GlobalData().isLogin, isTrue);
    expect(find.text('点击登录'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.runAsync(() => AccountStore().useGuest());
  });
}
