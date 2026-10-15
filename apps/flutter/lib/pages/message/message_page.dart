import 'package:flutter/material.dart';
import 'package:get/get.dart' hide Response;

import '../../components/cards/message_first_card.dart';
import '../../components/cards/message_header_card.dart';
import '../../components/cards/message_second_card.dart';
import '../../components/cards/message_third_card.dart';
import '../../components/footer.dart';
import '../../components/item_card.dart';
import '../../logic/model/feed/datum.dart';
import '../../logic/state/loading_state.dart';
import '../../pages/message/message_controller.dart';
import '../../pages/noitfication/notification_page.dart';
import '../../utils/extensions.dart';
import '../../utils/global_data.dart';

class MessagePage extends StatefulWidget {
  const MessagePage({super.key});

  @override
  State<MessagePage> createState() => _MessagePageState();
}

class _MessagePageState extends State<MessagePage>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  final _backgroundList = [
    0xFF2196f3,
    0xFF00bcd4,
    0xFF4caf50,
    0xFFf44336,
    0xFFff9800,
  ];
  final _titleList = ['@我的动态', '@我的评论', '我收到的赞', '好友关注', '私信'];
  final _iconList = [
    Icons.alternate_email,
    Icons.message_outlined,
    Icons.thumb_up_alt_outlined,
    Icons.person_add_outlined,
    Icons.mail_outline,
  ];
  late final MessageController _messageController =
      Get.put(MessageController());

  bool _isRefreshing = false;

  @override
  void dispose() {
    _messageController.scrollController?.dispose();
    Get.delete<MessageController>();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    if (GlobalData().isLogin) {
      _onRefresh();
    }
  }

  Future<void> _onRefresh() async {
    if (!mounted || !GlobalData().isLogin) return;
    final generation = GlobalData().sessionGeneration;
    _messageController.onReset();
    await _messageController.getProfile();
    if (!mounted || generation != GlobalData().sessionGeneration) return;
    await _messageController.checkCount();
    if (!mounted || generation != GlobalData().sessionGeneration) return;
    await _messageController.onGetData();
    _isRefreshing = false;
  }

  Widget _buildMessage(LoadingState loadingState) {
    switch (loadingState) {
      case Empty():
        return SliverToBoxAdapter(
          child: Container(
            height: 80,
            alignment: Alignment.center,
            padding: const EdgeInsets.all(10.0),
            child: const Text('暂无内容'),
          ),
        );
      case Error():
        return SliverToBoxAdapter(
          child: GestureDetector(
            onTap: () {
              _messageController
                  .setLoadingState(loadingState = LoadingState.loading());
              _onRefresh();
            },
            child: Container(
              height: 80,
              alignment: Alignment.center,
              padding: const EdgeInsets.all(10.0),
              child: Text(loadingState.errMsg),
            ),
          ),
        );
      case Success():
        List<Datum> dataList = loadingState.response as List<Datum>;
        return SliverPadding(
          padding: const EdgeInsets.only(left: 10, right: 10, bottom: 10),
          sliver: SliverList.separated(
            itemCount: dataList.length + 1,
            itemBuilder: (_, index) {
              if (index == dataList.length) {
                if (!_isRefreshing &&
                    !_messageController.isEnd &&
                    !_messageController.isLoading) {
                  _messageController.onGetData(false);
                }
                return Obx(() =>
                    footerWidget(_messageController.footerState.value, () {
                      _messageController.isEnd = false;
                      _messageController.setFooterState(LoadingState.loading());
                      _messageController.onGetData(false);
                    }));
              } else {
                return itemCard(
                  dataList[index],
                  onBlock: _messageController.onBlock,
                  onDeleteNoti: (id) {
                    _messageController.postLikeDeleteFollow(
                      id,
                      null,
                      isNoti: true,
                    );
                  },
                );
              }
            },
            separatorBuilder: (_, index) => const SizedBox(height: 10),
          ),
        );
    }
    return SliverToBoxAdapter(
      child: Container(
        height: 80,
        alignment: Alignment.center,
        padding: const EdgeInsets.all(10.0),
        child: const CircularProgressIndicator(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      appBar: AppBar(title: const Text('我的'), actions: [
        IconButton(
            onPressed: () => Get.toNamed('/accounts'),
            icon: const Icon(Icons.switch_account_outlined),
            tooltip: '账号管理'),
        IconButton(
            onPressed: () => Get.toNamed('/settings'),
            icon: const Icon(Icons.settings_outlined),
            tooltip: '设置'),
      ]),
      body: Column(
        children: [
          const SizedBox(height: 10),
          Obx(
            () => MessageHeaderCard(
              userInfo: _messageController.userInfo.value,
              onLogin: () => Get.toNamed('/login'),
            ),
          ),
          const SizedBox(height: 10),
          const Divider(height: 1),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () async {
                if (GlobalData().isLogin) {
                  _isRefreshing = true;
                  await _onRefresh();
                } else {
                  return Future.value();
                }
              },
              child: CustomScrollView(
                physics: const AlwaysScrollableScrollPhysics(
                  parent: BouncingScrollPhysics(),
                ),
                slivers: [
                  SliverPadding(
                    padding: const EdgeInsets.all(10),
                    sliver: SliverList.separated(
                      itemCount: GlobalData().isLogin ? 7 : 6,
                      itemBuilder: (context, itemIndex) {
                        final index =
                            itemIndex + (GlobalData().isLogin ? 0 : 1);
                        if (index == 0) {
                          return Obx(
                            () => messageFirstCard(
                              GlobalData().isLogin,
                              context,
                              _messageController.firstList,
                            ),
                          );
                        } else if (index == 1) {
                          return messageSeconfCard(
                              GlobalData().isLogin, context);
                        } else if (index >= 2 && index <= 6) {
                          return Obx(
                            () => messageThirdCard(
                              context,
                              _backgroundList[index - 2],
                              _iconList[index - 2],
                              _titleList[index - 2],
                              _messageController.thirdList.getOrNull(index - 2),
                              () {
                                if (GlobalData().isLogin) {
                                  Get.toNamed('/notification', arguments: {
                                    'type': NotificationType.values[index - 2]
                                  });
                                  int? count = _messageController.thirdList
                                      .getOrNull(index - 2);
                                  if (count != null && count > 0) {
                                    _messageController.thirdList[index - 2] =
                                        null;
                                  }
                                }
                              },
                            ),
                          );
                        }
                        return const SizedBox();
                      },
                      separatorBuilder: (_, index) =>
                          const SizedBox(height: 10),
                    ),
                  ),
                  if (GlobalData().isLogin)
                    Obx(() =>
                        _buildMessage(_messageController.loadingState.value)),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
