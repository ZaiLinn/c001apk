import 'package:flutter/material.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:hive_flutter/hive_flutter.dart';

import '../../components/common_body.dart';
import '../../components/network_image.dart';
import '../../logic/local_follow/local_follow_store.dart';
import '../../utils/global_data.dart';
import '../../utils/storage_util.dart';
import '../home/feed/home_feed_page.dart';
import '../home/home_page.dart' show TabType;
import 'local_follow_controller.dart';

String get localFollowLabel => GlobalData().isLogin ? '悄悄关注' : '本地关注';

/// The main timeline keeps server follows and local follows separate.
class FollowFeedPage extends StatefulWidget {
  const FollowFeedPage({super.key});
  @override
  State<FollowFeedPage> createState() => _FollowFeedPageState();
}

class _FollowFeedPageState extends State<FollowFeedPage>
    with AutomaticKeepAliveClientMixin {
  bool _local = false;
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        title: const Text('动态'),
        actions: [
          IconButton(
            onPressed: () => Get.toNamed('/settings'),
            icon: const Icon(Icons.settings_outlined),
            tooltip: '设置',
          ),
          IconButton(
            onPressed: () => Get.toNamed('/search'),
            icon: const Icon(Icons.search),
            tooltip: '搜索',
          ),
        ],
      ),
      body: ValueListenableBuilder(
        valueListenable:
            GStorage.settings.listenable(keys: [SettingsBoxKey.isLogin]),
        builder: (context, _, __) => Column(children: [
          if (GlobalData().isLogin)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(value: false, label: Text('账号关注')),
                  ButtonSegment(value: true, label: Text('悄悄关注')),
                ],
                selected: {_local},
                onSelectionChanged: (value) =>
                    setState(() => _local = value.first),
              ),
            ),
          Expanded(
              child: !GlobalData().isLogin || _local
                  ? const LocalFollowFeedView()
                  : const HomeFeedPage(tabType: TabType.FOLLOW)),
        ]),
      ),
    );
  }
}

class LocalFollowFeedView extends StatefulWidget {
  const LocalFollowFeedView({super.key});
  @override
  State<LocalFollowFeedView> createState() => _LocalFollowFeedViewState();
}

class _LocalFollowFeedViewState extends State<LocalFollowFeedView> {
  final _tag = UniqueKey().toString();
  late final LocalFollowController _controller;

  @override
  void initState() {
    super.initState();
    _controller = Get.put(LocalFollowController(), tag: _tag);
  }

  @override
  void dispose() {
    Get.delete<LocalFollowController>(tag: _tag);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Column(children: [
        ListTile(
          dense: true,
          title: Text('$localFollowLabel动态'),
          subtitle: const Text('读取本机名单中的用户动态，不会同步账号关注'),
          trailing: TextButton(
            onPressed: () => Get.to(() => const LocalFollowListPage()),
            child: const Text('管理'),
          ),
        ),
        Obx(() => _controller.warning.value == null
            ? const SizedBox.shrink()
            : ListTile(
                dense: true,
                title: Text(_controller.warning.value!),
                trailing: TextButton(
                    onPressed: () {
                      _controller.isEnd = false;
                      _controller.onGetData(false);
                    },
                    child: const Text('重试')),
              )),
        Expanded(
            child: ValueListenableBuilder(
          valueListenable:
              GStorage.settings.listenable(keys: [LocalFollowStore.usersKey]),
          builder: (context, _, __) => LocalFollowStore().users.isEmpty
              ? Center(
                  child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text(
                      GlobalData().isLogin
                          ? '还没有悄悄关注的用户\n在设置中开启后，长按用户主页的关注按钮即可添加'
                          : '还没有本地关注的用户\n在用户主页点击“本地关注”，即可在这里查看动态',
                      textAlign: TextAlign.center),
                ))
              : commonBody(_controller),
        )),
      ]);
}

class LocalFollowListPage extends StatelessWidget {
  const LocalFollowListPage({super.key});

  Future<void> _remove(String uid) async {
    try {
      await LocalFollowStore().unfollow(uid);
      SmartDialog.showToast('已取消$localFollowLabel，仅修改本机名单');
    } catch (_) {
      SmartDialog.showToast('本地关注保存失败，请重试');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text('管理$localFollowLabel')),
        body: ValueListenableBuilder(
          valueListenable:
              GStorage.settings.listenable(keys: [LocalFollowStore.usersKey]),
          builder: (context, _, __) {
            final users = LocalFollowStore().users;
            return Column(children: [
              const ListTile(
                  subtitle: Text('名单仅保存在本机，登录、退出或关闭悄悄关注开关后仍会保留。不会自动同步到账号。')),
              Expanded(
                  child: users.isEmpty
                      ? const Center(child: Text('还没有关注的用户'))
                      : ListView.builder(
                          itemCount: users.length,
                          itemBuilder: (context, index) {
                            final user = users[index];
                            return ListTile(
                              leading: clipNetworkImage(user.avatar,
                                  isAvatar: true, width: 40, height: 40),
                              title: Text(user.name.isEmpty
                                  ? '用户 ${user.uid}'
                                  : user.name),
                              subtitle: Text('UID：${user.uid}'),
                              onTap: () => Get.toNamed('/u/${user.uid}'),
                              trailing: IconButton(
                                tooltip: '取消$localFollowLabel',
                                onPressed: () => _remove(user.uid),
                                icon: const Icon(Icons.person_remove_outlined),
                              ),
                            );
                          },
                        )),
            ]);
          },
        ),
      );
}
