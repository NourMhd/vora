import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/routes/app_routes.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/storage/local_storage.dart';
import '../../../data/models/auth_response.dart';
import '../../../data/repositories/auth_repository.dart';

class AuthController extends GetxController {
  AuthController({
    required AuthRepository repository,
    required LocalStorage storage,
    required ApiClient apiClient,
  })  : _repository = repository,
        _storage = storage,
        _apiClient = apiClient;

  final AuthRepository _repository;
  final LocalStorage _storage;
  final ApiClient _apiClient;

  final isLoading = false.obs;
  final isBootstrapping = true.obs;
  final user = Rxn<AuthResponse>();

  bool get isLoggedIn => user.value?.authenticated == true;
  String get username => user.value?.username ?? _storage.username ?? 'Technicien';
  String get accessLabel {
    final currentUser = user.value;
    if (currentUser != null) return currentUser.accessLabel;
    final storedScope = _storage.scope;
    if (storedScope == 'INDEPENDENT') return 'CLIENTS INDÉPENDANTS';
    if (storedScope == 'PROJECT') return 'TECHNICIEN PROJET';
    return 'ACCÈS PLATEFORME';
  }

  @override
  void onInit() {
    super.onInit();
    bootstrap();
  }

  Future<void> bootstrap() async {
    try {
      isBootstrapping.value = true;
      if (!_storage.isLoggedIn) {
        if (Get.currentRoute != AppRoutes.login && Get.currentRoute.isNotEmpty) {
          Get.offAllNamed(AppRoutes.login);
        }
        return;
      }

      final me = await _repository.me();
      if (me.authenticated) {
        user.value = me;
        await _storage.saveSession(username: me.username ?? 'technicien', scope: me.scope);
        if (Get.currentRoute == AppRoutes.login) {
          Get.offAllNamed(AppRoutes.dashboard);
        }
      } else {
        await _storage.clearSession();
        if (Get.currentRoute != AppRoutes.login) {
          Get.offAllNamed(AppRoutes.login);
        }
      }
    } on ApiException catch (e) {
      if (e.isUnauthorized) {
        await _storage.clearSession();
        if (Get.currentRoute != AppRoutes.login) {
          Get.offAllNamed(AppRoutes.login);
        }
      }
    } finally {
      isBootstrapping.value = false;
    }
  }

  Future<void> login({
    required String username,
    required String password,
  }) async {
    if (username.trim().isEmpty || password.isEmpty) {
      _showError('Champs requis', 'Username et password sont obligatoires.');
      return;
    }

    try {
      isLoading.value = true;
      final response = await _repository.login(
        username: username.trim(),
        password: password,
      );

      if (!response.authenticated) {
        throw const ApiException('Identifiants invalides');
      }

      user.value = response;
      await _storage.saveSession(username: response.username ?? username.trim(), scope: response.scope);
      Get.offAllNamed(AppRoutes.dashboard);
    } on ApiException catch (e) {
      _showError('Connexion refusée', e.message);
    } catch (_) {
      _showError('Connexion impossible', 'Vérifie que le service VORA est disponible.');
    } finally {
      isLoading.value = false;
    }
  }

  Future<void> logout() async {
    try {
      await _repository.logout();
    } catch (_) {
      // La session locale doit être nettoyée même si le serveur est indisponible.
    } finally {
      user.value = null;
      _apiClient.clearSession();
      await _storage.clearSession();
      Get.offAllNamed(AppRoutes.login);
    }
  }

  void _showError(String title, String message) {
    Get.snackbar(
      title,
      message,
      snackPosition: SnackPosition.BOTTOM,
      margin: const EdgeInsets.all(16),
      borderRadius: 16,
      duration: const Duration(seconds: 4),
    );
  }
}
