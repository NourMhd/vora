import 'package:dio/browser.dart';
import 'package:dio/dio.dart';

void configureDioForCurrentPlatform(Dio dio) {
  dio.httpClientAdapter = BrowserHttpClientAdapter()..withCredentials = true;
}
