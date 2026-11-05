import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'api_exception.dart';
import 'platform_http_adapter_stub.dart'
    if (dart.library.html) 'platform_http_adapter_web.dart';

class MultipartFilePayload {
  const MultipartFilePayload({
    required this.fieldName,
    required this.bytes,
    required this.fileName,
  });

  final String fieldName;
  final Uint8List bytes;
  final String fileName;
}

class ApiClient {
  ApiClient() {
    _dio = Dio(
      BaseOptions(
        baseUrl: baseUrl,
        connectTimeout: const Duration(seconds: 60),
        receiveTimeout: const Duration(seconds: 300),
        sendTimeout: const Duration(seconds: 60),
        validateStatus: (_) => true,
        headers: const {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
      ),
    );

    configureDioForCurrentPlatform(_dio);

    _dio.interceptors.add(
      InterceptorsWrapper(
        onResponse: (response, handler) {
          _storeSessionCookie(response);
          handler.next(response);
        },
      ),
    );
  }

  static const String _configuredBaseUrl = String.fromEnvironment('VORA_API_BASE_URL');
  static String get baseUrl => _configuredBaseUrl.isNotEmpty
      ? _configuredBaseUrl
      : (kIsWeb ? Uri.base.origin : 'http://localhost:9090');

  late final Dio _dio;
  String? _sessionCookie;

  Future<dynamic> get(
    String path, {
    Map<String, dynamic>? queryParameters,
  }) async {
    final response = await _dio.get<dynamic>(
      path,
      queryParameters: queryParameters,
      options: _options(),
    );
    return _read(response);
  }


  Future<Uint8List> getBytes(
    String path, {
    Map<String, dynamic>? queryParameters,
  }) async {
    final response = await _dio.get<List<int>>(
      path,
      queryParameters: queryParameters,
      options: _options().copyWith(
        responseType: ResponseType.bytes,
        headers: {
          ...?_options().headers,
          'Accept': 'application/pdf',
        },
      ),
    );

    _storeSessionCookie(response);
    final status = response.statusCode ?? 0;
    if (status >= 200 && status < 300) {
      final data = response.data;
      if (data is List<int>) {
        return Uint8List.fromList(data);
      }
      return Uint8List(0);
    }

    throw ApiException(
      _extractErrorMessage(response.data, fallback: 'Erreur API VORA'),
      statusCode: response.statusCode,
    );
  }

  Future<dynamic> post(
    String path, {
    dynamic data,
  }) async {
    final response = await _dio.post<dynamic>(
      path,
      data: data,
      options: _options(),
    );
    return _read(response);
  }

  Future<dynamic> put(
    String path, {
    dynamic data,
  }) async {
    final response = await _dio.put<dynamic>(
      path,
      data: data,
      options: _options(),
    );
    return _read(response);
  }

  Future<dynamic> delete(String path, {Map<String, dynamic>? queryParameters}) async {
    final response = await _dio.delete<dynamic>(
      path,
      queryParameters: queryParameters,
      options: _options(),
    );
    return _read(response);
  }

  Future<dynamic> putMultipart(
    String path, {
    required Map<String, dynamic> fields,
    Uint8List? fileBytes,
    String? fileFieldName,
    String? fileName,
    List<MultipartFilePayload> files = const [],
  }) async {
    final formData = FormData();

    for (final entry in fields.entries) {
      final value = entry.value;
      if (value == null) continue;

      if (value is Iterable && value is! String) {
        for (final item in value) {
          if (item != null) {
            formData.fields.add(MapEntry(entry.key, item.toString()));
          }
        }
      } else {
        formData.fields.add(MapEntry(entry.key, value.toString()));
      }
    }

    if (fileBytes != null && fileBytes.isNotEmpty && fileFieldName != null) {
      formData.files.add(MapEntry(
        fileFieldName,
        MultipartFile.fromBytes(
          fileBytes,
          filename: fileName == null || fileName.isEmpty ? 'site-photo.jpg' : fileName,
        ),
      ));
    }

    for (final file in files) {
      if (file.bytes.isEmpty) continue;
      formData.files.add(MapEntry(
        file.fieldName,
        MultipartFile.fromBytes(
          file.bytes,
          filename: file.fileName.isEmpty ? 'site-photo.jpg' : file.fileName,
        ),
      ));
    }

    final response = await _dio.put<dynamic>(
      path,
      data: formData,
      options: _options(contentType: 'multipart/form-data'),
    );
    return _read(response);
  }

  void clearSession() {
    _sessionCookie = null;
  }

  Options _options({String? contentType}) {
    final headers = <String, dynamic>{};
    if (!kIsWeb && _sessionCookie != null) {
      headers['Cookie'] = _sessionCookie;
    }

    return Options(
      headers: headers.isEmpty ? null : headers,
      contentType: contentType,
      extra: const {'withCredentials': true},
    );
  }

  dynamic _read(Response<dynamic> response) {
    _storeSessionCookie(response);

    final status = response.statusCode ?? 0;
    if (status >= 200 && status < 300) {
      return response.data;
    }

    throw ApiException(
      _extractErrorMessage(response.data, fallback: 'Erreur API VORA'),
      statusCode: response.statusCode,
    );
  }

  void _storeSessionCookie(Response<dynamic> response) {
    final cookies = response.headers['set-cookie'];
    if (cookies == null || cookies.isEmpty) return;

    String? jsession;
    for (final rawCookie in cookies) {
      final cookie = rawCookie.split(';').first.trim();
      if (cookie.startsWith('JSESSIONID=')) {
        jsession = cookie;
        break;
      }
    }

    if (jsession != null && jsession.isNotEmpty) {
      _sessionCookie = jsession;
    }
  }

  String _extractErrorMessage(dynamic data, {required String fallback}) {
    if (data is Map) {
      final map = data.cast<dynamic, dynamic>();
      for (final key in const ['message', 'error', 'detail']) {
        final value = map[key];
        if (value != null && value.toString().trim().isNotEmpty) {
          return value.toString();
        }
      }
    }
    if (data is List<int>) {
      final text = utf8.decode(data, allowMalformed: true).trim();
      if (text.isNotEmpty) {
        try {
          final decoded = jsonDecode(text);
          final decodedMessage = _extractErrorMessage(decoded, fallback: '');
          if (decodedMessage.trim().isNotEmpty) return decodedMessage;
        } catch (_) {
          // Non JSON error body: return the plain text below.
        }
        return text;
      }
    }
    if (data is String && data.trim().isNotEmpty) {
      try {
        final decoded = jsonDecode(data.trim());
        final decodedMessage = _extractErrorMessage(decoded, fallback: '');
        if (decodedMessage.trim().isNotEmpty) return decodedMessage;
      } catch (_) {
        // Non JSON string: return it as-is.
      }
      return data.trim();
    }
    return fallback;
  }
}
