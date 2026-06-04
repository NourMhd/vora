class AuthResponse {
  const AuthResponse({
    required this.authenticated,
    this.username,
    this.scope,
  });

  final bool authenticated;
  final String? username;
  final String? scope;

  bool get isIndependent => scope == 'INDEPENDENT';

  String get accessLabel {
    if (scope == 'INDEPENDENT') return 'CLIENTS INDÉPENDANTS';
    if (scope == 'PROJECT') return 'TECHNICIEN PROJET';
    return 'ACCÈS PLATEFORME';
  }

  factory AuthResponse.fromJson(Map<String, dynamic> json) {
    return AuthResponse(
      authenticated: json['authenticated'] == true,
      username: json['username']?.toString(),
      scope: json['scope']?.toString(),
    );
  }
}
