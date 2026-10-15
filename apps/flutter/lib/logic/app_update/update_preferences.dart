import 'package:hive/hive.dart';

/// Separate from checkUpdate, which controls other installed Android apps.
class UpdatePreferences {
  UpdatePreferences(this.box);
  final Box<dynamic> box;
  static const enabledKey = 'autoCheckAppUpdate';
  static const _lastCheckKey = 'lastAppUpdateCheck';
  static const _snoozeKey = 'appUpdateSnoozeUntil';
  static const _skipKey = 'skippedAppUpdateVersion';
  static const interval = Duration(hours: 24);

  bool get enabled => box.get(enabledKey, defaultValue: true) == true;
  Future<void> setEnabled(bool value) => box.put(enabledKey, value);
  Future<void> recordCheck(DateTime now) =>
      box.put(_lastCheckKey, now.millisecondsSinceEpoch);
  Future<void> snooze(DateTime now) =>
      box.put(_snoozeKey, now.add(interval).millisecondsSinceEpoch);
  Future<void> skip(String version) => box.put(_skipKey, version);
  bool skipped(String version) => box.get(_skipKey) == version;

  bool automaticAllowed(DateTime now) {
    if (!enabled) return false;
    final timestamp = now.millisecondsSinceEpoch;
    final snooze = box.get(_snoozeKey);
    if (snooze is int &&
        snooze > timestamp &&
        snooze - timestamp <= interval.inMilliseconds) return false;
    final last = box.get(_lastCheckKey);
    // A backwards clock correction must not suppress checks indefinitely.
    return last is! int ||
        timestamp < last ||
        timestamp - last >= interval.inMilliseconds;
  }
}
