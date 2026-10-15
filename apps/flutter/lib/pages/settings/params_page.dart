import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../components/settings/edittext_item.dart';
import '../../utils/storage_util.dart';
import '../../utils/utils.dart';

class ParamsPage extends StatefulWidget {
  const ParamsPage({super.key});

  @override
  State<ParamsPage> createState() => _ParamsPageState();
}

class _ParamsPageState extends State<ParamsPage> {
  late final _paramsController = Get.put(ParamsController());

  final manufacturerKey = GlobalKey<EdittextItemState>();
  final brandKey = GlobalKey<EdittextItemState>();
  final modelKey = GlobalKey<EdittextItemState>();
  final buildKey = GlobalKey<EdittextItemState>();
  final sdkKey = GlobalKey<EdittextItemState>();
  final androidKey = GlobalKey<EdittextItemState>();

  @override
  void dispose() {
    Get.delete<ParamsController>();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('协议参数'),
        leading: const BackButton(),
      ),
      body: ListView(
        physics: const AlwaysScrollableScrollPhysics(
          parent: BouncingScrollPhysics(),
        ),
        children: [
          EdittextItem(
            title: '客户端版本名称',
            boxKey: SettingsBoxKey.versionName,
            needUpdateUserAgent: true,
            onChanged: _paramsController.onChanged,
          ),
          const EdittextItem(
            title: 'API 版本',
            boxKey: SettingsBoxKey.apiVersion,
          ),
          EdittextItem(
            title: '客户端版本号',
            boxKey: SettingsBoxKey.versionCode,
            needUpdateUserAgent: true,
            onChanged: _paramsController.onChanged,
          ),
          EdittextItem(
            key: manufacturerKey,
            title: '制造商',
            boxKey: SettingsBoxKey.manufacturer,
            needUpdateXAppDevice: true,
            onChanged: () => _paramsController.onChanged(true),
          ),
          EdittextItem(
            key: brandKey,
            title: '品牌',
            boxKey: SettingsBoxKey.brand,
            needUpdateUserAgent: true,
            needUpdateXAppDevice: true,
            onChanged: () => _paramsController.onChanged(true),
          ),
          EdittextItem(
            key: modelKey,
            title: '型号',
            boxKey: SettingsBoxKey.model,
            needUpdateUserAgent: true,
            needUpdateXAppDevice: true,
            onChanged: () => _paramsController.onChanged(true),
          ),
          EdittextItem(
            key: buildKey,
            title: '系统构建号',
            boxKey: SettingsBoxKey.buildNumber,
            needUpdateUserAgent: true,
            needUpdateXAppDevice: true,
            onChanged: () => _paramsController.onChanged(true),
          ),
          EdittextItem(
            key: sdkKey,
            title: 'SDK 版本',
            boxKey: SettingsBoxKey.sdkInt,
          ),
          EdittextItem(
            key: androidKey,
            title: 'Android 版本',
            boxKey: SettingsBoxKey.androidVersion,
            needUpdateUserAgent: true,
            onChanged: _paramsController.onChanged,
          ),
          Obx(
            () => ListTile(
              title: const Text('用户代理（User-Agent）'),
              subtitle: Text(_paramsController.userAgent.value),
              onTap: () => Utils.copyText(_paramsController.userAgent.value),
            ),
          ),
          Obx(
            () => ListTile(
              title: const Text('设备编码（X-App-Device）'),
              subtitle: Text(_paramsController.xAppDevice.value),
              onTap: () => Utils.copyText(_paramsController.xAppDevice.value),
            ),
          ),
          ListTile(
            title: const Text('重新生成参数'),
            onTap: () async {
              await GStorage.regenerateParams();
              manufacturerKey.currentState?.updateValue();
              brandKey.currentState?.updateValue();
              modelKey.currentState?.updateValue();
              buildKey.currentState?.updateValue();
              sdkKey.currentState?.updateValue();
              androidKey.currentState?.updateValue();
              _paramsController.onChanged(true);
            },
          ),
        ],
      ),
    );
  }
}

class ParamsController extends GetxController {
  RxString userAgent = ''.obs;
  RxString xAppDevice = ''.obs;

  void onChanged([bool updateXAppDevice = false]) {
    userAgent.value = GStorage.userAgent;
    if (updateXAppDevice) {
      xAppDevice.value = GStorage.xAppDevice;
    }
  }

  @override
  void onInit() {
    super.onInit();
    onChanged(true);
  }
}
