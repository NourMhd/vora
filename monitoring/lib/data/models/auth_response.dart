class AuthResponse {
  const AuthResponse({
    required this.authenticated,
    this.username,
    this.scope,
    this.planningAccess = false,
  });

  final bool authenticated;
  final String? username;
  final String? scope;
  final bool planningAccess;

  bool get isIndependent => scope == 'INDEPENDENT';

  String get accessLabel {
    if (scope == 'INDEPENDENT') return 'CLIENTS INDÉPENDANTS';
    if (scope == 'OOREDOO') return 'TECHNICIEN OOREDOO';
    if (scope == 'PROJECT') return 'TECHNICIEN PROJET';
    return 'ACCÈS PLATEFORME';
  }

  factory AuthResponse.fromJson(Map<String, dynamic> json) {
    return AuthResponse(
      authenticated: json['authenticated'] == true,
      username: json['username']?.toString(),
      scope: json['scope']?.toString(),
      planningAccess: json['planningAccess'] == true,
    );
  }
}
