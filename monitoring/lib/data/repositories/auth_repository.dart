import '../../core/network/api_client.dart';
import '../models/auth_response.dart';

class AuthRepository {
  const AuthRepository(this._apiClient);

  final ApiClient _apiClient;

  Future<AuthResponse> login({
    required String username,
    required String password,
  }) async {
    final data = await _apiClient.post(
      '/api/auth/login',
      data: {
        'username': username,
        'password': password,
      },
    );
    return AuthResponse.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<AuthResponse> me() async {
    final data = await _apiClient.get('/api/auth/me');
    return AuthResponse.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<void> logout() async {
    await _apiClient.post('/api/auth/logout');
  }
}
