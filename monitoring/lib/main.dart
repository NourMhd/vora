import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:intl/date_symbol_data_local.dart';

import 'app/app.dart';
import 'core/storage/local_storage.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await initializeDateFormatting('fr_FR');
  final storage = await LocalStorage.create();
  Get.put<LocalStorage>(storage, permanent: true);

  runApp(const VoraApp());
}
