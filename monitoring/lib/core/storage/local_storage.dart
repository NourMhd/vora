import 'package:shared_preferences/shared_preferences.dart';

class LocalStorage {
  LocalStorage._(this._prefs);

  static const _loggedInKey = 'vora_logged_in';
  static const _usernameKey = 'vora_username';
  static const _scopeKey = 'vora_scope';

  final SharedPreferences _prefs;

  static Future<LocalStorage> create() async {
    final prefs = await SharedPreferences.getInstance();
    return LocalStorage._(prefs);
  }

  bool get isLoggedIn => _prefs.getBool(_loggedInKey) ?? false;
  String? get username => _prefs.getString(_usernameKey);
  String? get scope => _prefs.getString(_scopeKey);

  Future<void> saveSession({required String username, String? scope}) async {
    await _prefs.setBool(_loggedInKey, true);
    await _prefs.setString(_usernameKey, username);
    if (scope == null || scope.isEmpty) {
      await _prefs.remove(_scopeKey);
    } else {
      await _prefs.setString(_scopeKey, scope);
    }
  }

  Future<void> clearSession() async {
    await _prefs.remove(_loggedInKey);
    await _prefs.remove(_usernameKey);
    await _prefs.remove(_scopeKey);
  }
}
