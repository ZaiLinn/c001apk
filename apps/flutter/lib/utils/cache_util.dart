import 'package:flutter/foundation.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';

/// Uses the same manager as CachedNetworkImage. On Windows, the platform's
/// temporary directory is shared with other applications; never enumerate or
/// recursively delete it as though it belonged to this app.
class CacheManage {
  CacheManage._internal();

  static final CacheManage cacheManage = CacheManage._internal();
  factory CacheManage() => cacheManage;

  Future<String> loadApplicationCache() async {
    try {
      final manager = DefaultCacheManager();
      // Await the manager's repository initialization without fetching a URL.
      await manager.getFileFromCache('__c001apk_cache_size__');
      final entries = await manager.config.repo.getAllObjects();
      double size = 0;
      for (final entry in entries) {
        final file =
            await manager.config.fileSystem.createFile(entry.relativePath);
        if (await file.exists()) size += await file.length();
      }
      return formatSize(size);
    } catch (error) {
      debugPrint('cache.size: ${error.runtimeType}');
      return '暂无法读取';
    }
  }

  static String formatSize(double value) {
    const units = ['B', 'K', 'M', 'G'];
    int index = 0;
    while (value > 1024 && index < units.length - 1) {
      index++;
      value /= 1024;
    }
    return '${value.toStringAsFixed(2)}${units[index]}';
  }

  Future<bool> clearCacheAll() async {
    try {
      final manager = DefaultCacheManager();
      await manager.getFileFromCache('__c001apk_cache_size__');
      final entries = await manager.config.repo.getAllObjects();
      for (final entry in entries) {
        final file =
            await manager.config.fileSystem.createFile(entry.relativePath);
        // In the pinned manager, removal uses io.File(relativePath). Resolve
        // through its file system first so deletion never depends on the CWD.
        await manager.store
            .removeCachedFile(entry.copyWith(relativePath: file.path));
      }
      return true;
    } catch (error) {
      debugPrint('cache.clear: ${error.runtimeType}');
      SmartDialog.showToast('缓存清理失败，请稍后重试');
      return false;
    }
  }
}
